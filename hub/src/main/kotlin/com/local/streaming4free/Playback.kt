package com.local.streaming4free

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.StuckPlayerException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.ui.PlayerView
import java.util.Locale

/**
 * The one player of IPTV4FREE (HLS, DASH, MPEG-TS, MP4), shown full screen or in the preview box of the menu, trying
 * a channel's known sources in turn. Hardware decoders first, quality chosen from the bandwidth, audio and subtitle
 * tracks in the player's settings. No browser user agent, no Referer, no DRM: a stream that needs them is reported
 * as unavailable. Moved unchanged from the hub's PlayerActivity, minus the activity.
 */
@OptIn(UnstableApi::class)
class Playback(private val a: Activity, private val view: PlayerView, private val message: TextView, private val onEnded: () -> Unit) {
    companion object {
        private const val STARTUP_MS = 20_000L
        private val PLAYERS = listOf("org.videolan.vlc" to "VLC", "is.xyz.mpv" to "mpv") // for codecs this TV lacks
    }

    /** The channel opened, kept while the activity is stopped so it can start again. */
    var channel: Channel? = null
        private set
    private var player: ExoPlayer? = null
    private var urls: List<String> = emptyList()
    private var recovery = Recovery(1)
    private var mime: String? = null // forced to HLS when the URL does not tell the format
    @Volatile var started = false // the current source has played at least once
        private set
    var failed = false
        private set
    private var waiting = false // offline: waiting for the network or for OK
    private var watching = false
    private var notice: String? = null // a track this TV cannot decode
    private var external: Pair<String, String>? = null // VLC or mpv, offered after a codec failure
    private var provider: App? = null // official app handoff, not an IPTV URL
    private val handler = Handler(Looper.getMainLooper())
    private val watchdog = Runnable { player?.let { it.stop(); fail(if (offline()) Kind.LOCAL_NETWORK else Kind.TIMEOUT, "") } }

    /** Before the first picture, a refused or missing playlist fails at once so the next source is tried; once playing, Media3 switches variant itself. */
    private val loadErrors = object : DefaultLoadErrorHandlingPolicy(6) {
        override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
            val code = (info.exception as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            return if (!started && code != null && code in 400..499 && code != 408 && code != 429 && info.mediaLoadData.dataType == C.DATA_TYPE_MANIFEST) C.TIME_UNSET
                else super.getRetryDelayMsFor(info)
        }
    }

    private val connectivity get() = a.getSystemService(ConnectivityManager::class.java)
    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { handler.post(::online) }
        override fun onCapabilitiesChanged(n: Network, caps: NetworkCapabilities) { handler.post(::online) }
    }

    init {
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
        view.setShowSubtitleButton(true)
        view.setShowPreviousButton(false) // zapping is done by the menu's list, not by a playlist
        view.setShowNextButton(false)
        view.controllerAutoShow = false
    }

    val playing get() = player != null
    /** Stopped on an error or waiting for the network: OK means "try again" (see [ok]). */
    val stuck get() = failed || waiting
    val paused get() = player?.playWhenReady == false

    fun togglePause() { player?.let { it.playWhenReady = !it.playWhenReady } }

    /** Opens [ch] from its first source, with every counter reset. */
    fun open(ch: Channel) {
        release()
        channel = ch
        restart()
    }

    /** Stops and forgets the channel. */
    fun stop() {
        release()
        channel = null
        message.visibility = View.GONE
    }

    /** Activity stopped: the player goes, the channel stays for [resume]. */
    fun suspend() = release()

    fun resume() { if (player == null && channel != null) restart() }

    /** From the first source, with every counter reset: on opening, and on OK after a final error. */
    private fun restart() {
        val ch = channel ?: return
        val m3upt = Playlists.SOURCES.first { it.name == "M3UPT" }
        urls = candidates(ch, Catalog.all(a), Playlists.loaded(m3upt), Store::health, System.currentTimeMillis())
        recovery = Recovery(urls.size)
        failed = false
        waiting = false
        external = null
        provider = null
        mime = null
        play()
    }

    private fun play() {
        val ch = channel ?: return
        show(a.getString(R.string.opening, ch.title))
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true) // many lists still point to http:// that redirects to https://
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(15_000)
        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(a, http))
            .setLoadErrorHandlingPolicy(loadErrors)
        val p = ExoPlayer.Builder(a, DefaultRenderersFactory(a).setEnableDecoderFallback(true))
            .setMediaSourceFactory(sources)
            .setStuckBufferingDetectionTimeoutMs(30_000) // the default is 10 minutes
            .build()
        val langs = listOf(Store.lang, Locale.getDefault().language, "pt", "fr").filter { it.isNotEmpty() }
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguages(*langs.distinct().toTypedArray())
            .build()
        p.addListener(listener)
        view.player = p
        player = p
        load()
        p.playWhenReady = true
    }

    /** Opens the current source, at its resume point for a film or replay. */
    private fun load() {
        val p = player ?: return
        val url = urls[recovery.source]
        started = false
        notice = null
        quality(0)
        Log.i(TAG, "${channel?.title}: source ${recovery.source + 1}/${urls.size} ${url.substringBefore('?')}")
        val item = MediaItem.Builder().setUri(url).setMimeType(mime).build()
        val resume = Store.position(url)
        if (resume > 0) p.setMediaItem(item, resume) else p.setMediaItem(item)
        prepare()
    }

    private fun prepare() {
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, STARTUP_MS)
        player?.prepare()
    }

    private fun retry(toLiveEdge: Boolean) {
        if (toLiveEdge) player?.seekToDefaultPosition()
        prepare()
    }

    /** 0 = the user's setting, 1 = 720p at most, 2 = SD. */
    private fun quality(level: Int) {
        val p = player ?: return
        val max = Store.maxHeight
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().apply {
            when (level) {
                0 -> if (max > 0) setMaxVideoSize(Int.MAX_VALUE, max) else clearVideoSizeConstraints()
                1 -> setMaxVideoSize(1280, if (max in 1..720) max else 720)
                else -> setMaxVideoSizeSd()
            }
        }.build()
    }

    private fun release() {
        handler.removeCallbacksAndMessages(null)
        unwatch()
        val p = player ?: return
        // films and replays resume where they stopped; live channels have no position
        if (started && !p.isCurrentMediaItemLive && p.duration != C.TIME_UNSET && p.duration > 60_000) {
            val pos = p.currentPosition
            Store.setPosition(urls[recovery.source], if (pos > 10_000 && pos < p.duration - 60_000) pos else 0)
        }
        p.removeListener(listener)
        p.release()
        player = null
        view.player = null
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                handler.removeCallbacks(watchdog)
                notice?.let(::show) ?: run { message.visibility = View.GONE }
                recovery.ready(SystemClock.elapsedRealtime())
                if (!started) {
                    started = true
                    val url = urls[recovery.source]
                    Log.i(TAG, "${channel?.title}: source ${recovery.source + 1} plays (${Uri.parse(url).host})")
                    channel?.let(Store::addHistory)
                    Store.recordOk(url)
                }
            } else if (state == Player.STATE_ENDED) {
                Store.setPosition(urls[recovery.source], 0)
                handler.post(onEnded)
            }
        }

        /** A track no decoder of this TV reads is left out silently by Media3: say so instead. */
        override fun onTracksChanged(tracks: Tracks) {
            val type = listOf(C.TRACK_TYPE_AUDIO, C.TRACK_TYPE_VIDEO).firstOrNull { tracks.containsType(it) && !tracks.isTypeSupported(it, true) } ?: return
            val format = tracks.groups.first { it.type == type }.getTrackFormat(0)
            val text = a.getString(if (type == C.TRACK_TYPE_AUDIO) R.string.no_audio_codec else R.string.no_video_codec, mimeLabel(format.sampleMimeType ?: format.codecs))
            Log.i(TAG, "${channel?.title}: $text")
            notice = text
            show(text)
        }

        override fun onPlayerError(e: PlaybackException) {
            if (player == null) return
            handler.removeCallbacks(watchdog)
            if (e.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && mime == null && !started) {
                mime = MimeTypes.APPLICATION_M3U8 // ".php", "/live" and the like are nearly always HLS
                handler.post { load() } // not from inside the player's own callback
                return
            }
            val ch = channel ?: return
            val causes = generateSequence<Throwable>(e) { it.cause }.toList()
            val http = causes.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
            val kind = classify(e.errorCode, http?.responseCode, http?.headerFields.orEmpty(), http?.responseBody,
                offline(), ch.geo && urls[recovery.source].let { it == ch.url || it in ch.alts }, causes.any { it is StuckPlayerException }, started)
            fail(kind, http?.let { "HTTP ${it.responseCode}" } ?: e.errorCodeName)
        }
    }

    private fun fail(kind: Kind, detail: String) {
        val p = player ?: return
        val url = urls[recovery.source]
        Log.i(TAG, "${channel?.title}: source ${recovery.source + 1}/${urls.size} ${url.substringBefore('?')} failed: $kind $detail")
        when (val step = recovery.fail(kind, p.isCurrentMediaItemLive, SystemClock.elapsedRealtime(), started)) {
            is Step.Retry -> {
                if (step.delayMs > 0) show(a.getString(R.string.reconnecting, recovery.tries, recovery.limit))
                if (step.quality > 0) quality(step.quality)
                handler.postDelayed({ retry(step.toLiveEdge) }, step.delayMs)
            }
            is Step.Next -> {
                if (!started) Store.recordFail(url, kind) // a source that played here is not greyed for a later drop
                show(a.getString(R.string.switching, step.source, reason(kind, detail), step.source + 1, urls.size))
                mime = null
                handler.post { load() }
            }
            Step.WaitForNetwork -> {
                waiting = true
                show(a.getString(R.string.waiting_network, channel?.title.orEmpty()))
                watch()
            }
            Step.GiveUp -> {
                if (!started) Store.recordFail(url, kind)
                giveUp(kind, detail, url)
            }
        }
    }

    /** Every source failed: say why in words, with the HTTP code and host, and what is left to try. */
    private fun giveUp(kind: Kind, detail: String, url: String) {
        val ch = channel ?: return
        failed = true
        external = if (kind == Kind.CODEC) PLAYERS.firstOrNull { installed(it.first) } else null
        // Official app handoff is opt-in. Access depends on the subscriber's actual
        // French residence and EU travel eligibility; no extracted stream is used.
        provider = if (external == null && rightsService(ch.id, ch.title) != null)
            APPS.firstOrNull { it.id == "molotov" && it.launchIntent(a) != null }
        else null
        val where = listOf(detail.takeIf { it.startsWith("HTTP") }.orEmpty(), Uri.parse(url).host.orEmpty()).filter { it.isNotEmpty() }
        val lines = mutableListOf(a.getString(R.string.play_error, ch.title, kindText(kind), where.joinToString(", ")),
            if (urls.size > 1) a.getString(R.string.all_failed, urls.size) else a.getString(R.string.no_alternative))
        if (ch.headers) lines += a.getString(R.string.headers_needed)
        rightsService(ch.id, ch.title)?.let { lines += a.getString(R.string.rights_fr, it) }
        officialApp(ch.id, ch.title)?.let { lines += a.getString(R.string.official_app, it) }
        provider?.let { lines += a.getString(R.string.molotov_portability) }
        lines += when {
            external != null -> a.getString(R.string.ok_external, external!!.second)
            provider != null -> a.getString(R.string.ok_provider, provider!!.title)
            else -> a.getString(R.string.ok_retry)
        }
        show(lines.joinToString("\n"))
    }

    private fun reason(kind: Kind, detail: String) = if (detail.startsWith("HTTP")) detail else kindText(kind)

    private fun kindText(kind: Kind) = a.getString(when (kind) {
        Kind.GEO -> R.string.kind_geo
        Kind.FORBIDDEN -> R.string.kind_forbidden
        Kind.NOT_FOUND -> R.string.kind_not_found
        Kind.NOT_MEDIA -> R.string.kind_not_media
        Kind.DRM -> R.string.kind_drm
        Kind.CODEC -> R.string.kind_codec
        Kind.SERVER -> R.string.kind_server
        Kind.TIMEOUT, Kind.BEHIND_LIVE -> R.string.kind_timeout
        Kind.NETWORK -> R.string.kind_network
        Kind.LOCAL_NETWORK -> R.string.kind_local
        Kind.OTHER -> R.string.kind_other
    })

    private fun installed(pkg: String) = try { a.packageManager.getApplicationInfo(pkg, 0); true } catch (e: PackageManager.NameNotFoundException) { false }

    /** The TV itself has no working network, as Android sees it. */
    private fun offline(): Boolean = try {
        val cm = connectivity
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true
    } catch (e: Exception) {
        false
    }

    private fun watch() {
        if (watching) return
        try {
            val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
            connectivity.registerNetworkCallback(request, network)
            watching = true
        } catch (e: Exception) {
            Log.w(TAG, "network callback: $e")
        }
    }

    private fun unwatch() {
        if (!watching) return
        watching = false
        try { connectivity.unregisterNetworkCallback(network) } catch (e: Exception) {}
    }

    /** Called on every network change while waiting; the same source resumes once Android validates the network. */
    private fun online() {
        if (!waiting || offline()) return
        waiting = false
        unwatch()
        show(a.getString(R.string.opening, channel?.title.orEmpty()))
        retry(player?.isCurrentMediaItemLive == true)
    }

    private fun show(text: String) {
        message.text = text
        message.visibility = View.VISIBLE
    }

    /** OK after a final error or while offline: the other player, the official app, or a new try. False when OK means nothing here. */
    fun ok(): Boolean {
        if (!failed && !waiting) return false
        val ext = external
        when {
            failed && ext != null -> openExternal(ext.first)
            failed && provider != null -> openProvider(provider!!)
            else -> { release(); restart() }
        }
        return true
    }

    /** Open the licensed app home screen; the provider checks entitlements itself. */
    private fun openProvider(app: App) {
        val launch = app.launchIntent(a)
        if (launch == null) {
            Toast.makeText(a, R.string.not_installed, Toast.LENGTH_LONG).show()
            return
        }
        try {
            a.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(a, R.string.not_installed, Toast.LENGTH_LONG).show()
        }
    }

    private fun openExternal(pkg: String) = try {
        a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(urls[recovery.source]), "video/*").setPackage(pkg))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(a, R.string.not_installed, Toast.LENGTH_LONG).show()
    }
}
