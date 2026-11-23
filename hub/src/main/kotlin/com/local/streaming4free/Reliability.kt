package com.local.streaming4free

import androidx.media3.common.PlaybackException
import java.util.Locale

/** Why a source failed. Pure Kotlin, so the player's decisions are unit-tested without a TV. */
enum class Kind {
    GEO, FORBIDDEN, NOT_FOUND, NOT_MEDIA, DRM, CODEC, SERVER, TIMEOUT, NETWORK, LOCAL_NETWORK, BEHIND_LIVE, OTHER;

    /** Waiting may fix it: the same source is tried again. */
    val transient get() = this == NETWORK || this == TIMEOUT || this == SERVER || this == LOCAL_NETWORK

    /** About the stream itself, so the card is greyed for a day. */
    val dead get() = this in setOf(GEO, FORBIDDEN, NOT_FOUND, NOT_MEDIA, DRM, CODEC, SERVER, TIMEOUT)
}

private val GEO_TEXT = Regex("(?i)geo.?(block|restrict|fenc)|not available in your (country|region|location)|outside (of )?(the |your )?(country|region)|territor(y|ial) restrict")

/** Reads a failure from what ExoPlayer gives: error code, HTTP status, response headers and the first bytes of the body. */
fun classify(
    code: Int, http: Int? = null, headers: Map<String, List<String>> = emptyMap(), body: ByteArray? = null,
    offline: Boolean = false, listGeo: Boolean = false, stuck: Boolean = false, played: Boolean = false,
): Kind {
    if (code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return Kind.BEHIND_LIVE
    // without a validated network, a timeout, a portal page or a 401/403/511 says nothing about the stream;
    // any other HTTP status is the server's answer (Android's check may just be blocked by a DNS filter or VPN)
    if (offline && (http == null || http == 401 || http == 403 || http == 511)) return Kind.LOCAL_NETWORK
    if (stuck) return Kind.TIMEOUT
    if (http != null) {
        val geo = listGeo || headers.any { (k, v) -> k.equals("x-errortype", true) && v.any { "geo" in it.lowercase(Locale.ROOT) } } ||
            body != null && GEO_TEXT.containsMatchIn(String(body, 0, minOf(body.size, 1024), Charsets.UTF_8))
        return when (http) {
            451 -> Kind.GEO
            401, 403 -> if (geo) Kind.GEO else Kind.FORBIDDEN
            404, 410 -> Kind.NOT_FOUND
            408 -> Kind.TIMEOUT
            429 -> Kind.SERVER
            in 500..599 -> Kind.SERVER
            else -> Kind.OTHER
        }
    }
    return when (code) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
            Kind.NETWORK
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT, PlaybackException.ERROR_CODE_TIMEOUT ->
            Kind.TIMEOUT
        // once the source has played, a bad packet or a decoder taken by another app passes: retried, not blamed on the stream
        PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> Kind.NETWORK
        PlaybackException.ERROR_CODE_DECODING_FAILED -> if (played) Kind.NETWORK else Kind.CODEC
        // an HTML or JSON page where the playlist or the video should be: portal, error or paywall page
        PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE, in 3001..3004 -> if (played) Kind.NETWORK else Kind.NOT_MEDIA
        in 4001..4006, PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED, PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> Kind.CODEC
        in 6000..6008 -> Kind.DRM
        else -> Kind.OTHER
    }
}

/** The channel's identity across lists: tvg-id without its "@feed" part. Empty means unknown; names are never matched. */
fun channelKey(tvgId: String) = tvgId.substringBefore('@').trim().lowercase(Locale.ROOT)

private val IPV4 = Regex("""\d{1,3}(\.\d{1,3}){3}""")

/** Bare-IP hosts are unofficial relays in these lists: never offered as a fallback. */
fun isBareIp(url: String): Boolean {
    val host = url.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@')
    return host.startsWith('[') || IPV4.matches(host.substringBefore(':'))
}

/**
 * The URLs to try for [chosen], in order. Alternates come from the chosen entry, the checked catalog and M3UPT only:
 * Free-TV and iptv-org runtime entries include unofficial relays, so only the URL the user picked is used from them.
 * The chosen URL stays first unless it failed recently and another candidate has played since.
 */
fun candidates(chosen: Channel, catalog: List<Channel>, runtime: List<Channel>, health: (String) -> Health? = { null }, now: Long = 0): List<String> {
    val key = channelKey(chosen.id)
    val same = if (key.isEmpty()) emptyList() else
        catalog.filter { channelKey(it.id) == key }.flatMap { listOf(it.url) + it.alts } +
            runtime.filter { it.source == "M3UPT" && channelKey(it.id) == key }.map { it.url }
    val others = (chosen.alts + same).filter { it != chosen.url && !isBareIp(it) }.distinct()
        .sortedByDescending { health(it)?.score(now) ?: 1 }
    val first = health(chosen.url)
    val demote = first != null && first.failedRecently(now) && others.any { health(it)?.succeeded(now) == true }
    return if (demote) (others + chosen.url).sortedByDescending { health(it)?.score(now) ?: 1 } else listOf(chosen.url) + others
}

/** What this TV saw of one URL. Times are wall-clock; one stored in the future (clock corrected since) is ignored. */
data class Health(val okAt: Long = 0, val failAt: Long = 0, val kind: Kind? = null, val fails: Int = 0) {
    private fun valid(t: Long, now: Long) = t in 1..now

    fun ok(now: Long) = Health(okAt = now)
    /** A failure while the TV is offline says nothing about the stream: what was known is kept. */
    fun fail(k: Kind, now: Long) = if (k == Kind.LOCAL_NETWORK) this else copy(failAt = now, kind = k, fails = fails + 1)

    fun succeeded(now: Long) = valid(okAt, now) && (!valid(failAt, now) || okAt >= failAt)
    fun failedRecently(now: Long) = valid(failAt, now) && !succeeded(now) && now - failAt < DAY
    fun dead(now: Long) = failedRecently(now) && kind?.dead == true
    /** Higher plays first: worked last time, then unknown, then failed (the more often, the later). */
    fun score(now: Long) = when { succeeded(now) -> 2; failedRecently(now) -> -fails; else -> 1 }
    /** Nothing seen for a month (or a time in the future): dropped from storage. */
    fun stale(now: Long) = maxOf(okAt, failAt).let { !valid(it, now) || now - it > 30 * DAY }

    fun encode() = "$okAt,$failAt,${kind?.name.orEmpty()},$fails"

    companion object {
        const val DAY = 24 * 3600_000L
        fun decode(s: String?): Health? = s?.split(',')?.takeIf { it.size == 4 }?.let { p ->
            Health(p[0].toLongOrNull() ?: 0, p[1].toLongOrNull() ?: 0, Kind.entries.find { it.name == p[2] }, p[3].toIntOrNull() ?: 0)
        }
    }
}

/** What the player does next after a failure. */
sealed interface Step {
    /** Same source again after [delayMs]; [quality] 0 = the user's setting, 1 = 720p at most, 2 = SD. */
    data class Retry(val delayMs: Long, val quality: Int, val toLiveEdge: Boolean) : Step
    data class Next(val source: Int) : Step
    data object WaitForNetwork : Step
    data object GiveUp : Step
}

/** Retries, quality drops and source switches for one channel. Bounded: it always ends in [Step.GiveUp]. */
class Recovery(private val sources: Int) {
    var source = 0; private set
    var tries = 0; private set
    var quality = 0; private set
    /** Retries allowed on this source: 1 before its first picture while another source is left, else [MAX_TRIES]. */
    var limit = MAX_TRIES; private set
    private var readyAt = -1L
    private val behind = ArrayDeque<Long>()

    fun ready(now: Long) { readyAt = now }

    fun fail(kind: Kind, live: Boolean, now: Long, played: Boolean = true): Step {
        // a stream that played 15 s since its last drop gets its retries back; one that drops every few seconds does not
        if (readyAt >= 0 && now - readyAt >= 15_000) tries = 0
        readyAt = -1
        var k = kind
        if (k == Kind.BEHIND_LIVE) {
            while (behind.isNotEmpty() && now - behind.first() >= 60_000) behind.removeFirst()
            if (behind.size < 3) { behind.addLast(now); return Step.Retry(0, quality, true) }
            k = Kind.TIMEOUT
        }
        if (k == Kind.LOCAL_NETWORK) return Step.WaitForNetwork
        // a host that never answered is not waited on for minutes while another source may play
        limit = if (played || source + 1 >= sources) MAX_TRIES else 1
        if (k.transient && tries < limit) {
            tries++
            if (live && tries > 2 && quality < 2) quality++
            return Step.Retry(1000L shl (tries - 1), quality, live)
        }
        return next()
    }

    fun next(): Step {
        if (source + 1 >= sources) return Step.GiveUp
        source++
        tries = 0
        quality = 0
        behind.clear()
        return Step.Next(source)
    }

    companion object { const val MAX_TRIES = 4 }
}

/** A codec name people know, for "format non lu" messages. */
fun mimeLabel(mime: String?): String = when (mime) {
    null, "" -> "?"
    "audio/eac3" -> "E-AC-3"
    "audio/eac3-joc" -> "E-AC-3 JOC"
    "audio/ac3" -> "AC-3"
    "audio/ac4" -> "AC-4"
    "audio/vnd.dts", "audio/vnd.dts.hd" -> "DTS"
    "audio/mpeg-L2" -> "MP2"
    "video/hevc" -> "HEVC"
    "video/av01" -> "AV1"
    "video/x-vnd.on2.vp9" -> "VP9"
    else -> mime.substringAfter('/').uppercase(Locale.ROOT)
}

// checked 2026-11-17: TF1+ live only in France, Belgium, Luxembourg, Switzerland and some African countries;
// M6+ only France, Monaco, Andorra; france.tv excludes its main channels' live abroad
private val RIGHTS_BY_ID = mapOf(
    "tf1.fr" to "TF1+", "tmc.fr" to "TF1+", "tfx.fr" to "TF1+", "tf1seriesfilms.fr" to "TF1+", "lci.fr" to "TF1+",
    "m6.fr" to "M6+", "w9.fr" to "M6+", "6ter.fr" to "M6+",
    "france2.fr" to "france.tv", "france3.fr" to "france.tv", "france4.fr" to "france.tv", "france5.fr" to "france.tv", "franceinfo.fr" to "france.tv",
)
private val RIGHTS_BY_NAME = mapOf(
    "tf1" to "TF1+", "tmc" to "TF1+", "tfx" to "TF1+", "lci" to "TF1+", "m6" to "M6+", "w9" to "M6+", "6ter" to "M6+",
    "france 2" to "france.tv", "france 3" to "france.tv", "france 4" to "france.tv", "france 5" to "france.tv", "franceinfo" to "france.tv",
)
private val BRACKETS = Regex("""\(.*?\)|\[.*?]""")
private val SPACES = Regex("\\s+")
private val QUALITY = Regex("""\s+(hd|fhd|uhd|sd|4k)$""", RegexOption.IGNORE_CASE)

private fun plainTitle(title: String) = title.replace(BRACKETS, " ").replace(SPACES, " ").trim().replace(QUALITY, "").lowercase(Locale.ROOT)

/** The official service of a French channel that has no free legal live from Portugal, or null. */
fun rightsService(id: String, title: String): String? = RIGHTS_BY_ID[channelKey(id)] ?: RIGHTS_BY_NAME[plainTitle(title)]

// checked 2026-11-23: RTP 1 and RTP 2 left plain HLS for DASH with a DRM key (old URLs 404); RTP's live stays free in RTP Play
private val RTP = setOf("rtp1.pt", "rtp2.pt", "rtp3.pt", "rtpnoticias.pt", "rtpmemoria.pt", "rtpacores.pt", "rtpmadeira.pt", "rtpafrica.pt", "rtpmundo.pt")
private val RTP_NAMES = Regex("rtp ?(1|2|3|notícias|noticias|memória|memoria|açores|acores|madeira|áfrica|africa|mundo)")

/** The free official app that carries this channel's live here, or null. */
fun officialApp(id: String, title: String): String? =
    if (channelKey(id) in RTP || RTP_NAMES.matches(plainTitle(title))) "RTP Play" else null
