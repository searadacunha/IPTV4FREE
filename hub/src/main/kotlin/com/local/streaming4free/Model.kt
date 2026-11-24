package com.local.streaming4free

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Where an app is offered in the menu. */
enum class Cat { MOVIES, SERIES, LIVE, SPORT, DOCS, YOUTUBE, IPTV }

/** Everything a row can show. */
sealed class Item {
    abstract val key: String
    abstract val title: String
}

/** One stream, from the checked catalog, the IPTV4FREE lists or a public playlist. */
data class Channel(
    override val title: String,
    val url: String,
    val logo: String = "",
    val group: String = "",
    val source: String = "",
    val id: String = "", // tvg-id
    val quality: String = "", // what the probe read in the playlist, catalog only
    val alts: List<String> = emptyList(), // other URLs of the same channel, checked by the probe
    val geo: Boolean = false, // the list says it is geo-blocked
    val headers: Boolean = false, // the list asks for a Referer or user agent, which this player never sends
) : Item() {
    override val key get() = url
    /** A YouTube page, not a stream: opened by SmartTube or the YouTube app. */
    val isYouTube get() = YOUTUBE.containsMatchIn(url)

    private companion object {
        val YOUTUBE = Regex("^https?://([a-z]+\\.)?(youtube\\.com|youtu\\.be)/")
    }
}

/** What using a service costs: shown on its card, so nothing paid or blocked passes for free. */
enum class Terms(val label: Int) {
    FREE(R.string.terms_free), ACCOUNT(R.string.terms_account), FREEMIUM(R.string.terms_freemium),
    FRANCE(R.string.terms_france), PAID(R.string.terms_paid), SITE(R.string.terms_site),
}

/**
 * An app IPTV4FREE opens. It never reads that app's data: Android gives no access to it.
 * [site] = a web service built into IPTV4FREE (WATCHED, CINEFUSE): no other app needed.
 */
data class App(
    val id: String,
    val packages: List<String>, // first installed one wins
    override val title: String,
    val desc: Int,
    val terms: Terms,
    val cats: Set<Cat>,
    val onPlayStore: Boolean = true,
    val site: Class<*>? = null,
) : Item() {
    override val key get() = "app:$id"

    fun launchIntent(c: Context): Intent? = site?.let { Intent(c, it) }
        ?: packages.firstNotNullOfOrNull { c.packageManager.getLeanbackLaunchIntentForPackage(it) ?: c.packageManager.getLaunchIntentForPackage(it) }

    fun installedPackage(pm: PackageManager) = packages.firstOrNull { p ->
        try { pm.getApplicationInfo(p, 0).enabled } catch (e: PackageManager.NameNotFoundException) { false }
    }

    fun ready(pm: PackageManager) = site != null || installedPackage(pm) != null

    /** Free apps are offered for installation; paid or geo-blocked ones only show once installed. */
    fun visible(pm: PackageManager) = ready(pm) || (onPlayStore && terms in setOf(Terms.FREE, Terms.ACCOUNT, Terms.FREEMIUM))
}

/** A group of a playlist (country, category). */
class Folder(override val title: String, val channels: List<Channel>, val source: String) : Item() {
    override val key get() = "folder:$source:$title"
}

/** A menu level: its rows are computed when shown, since favorites, history and downloads change. [slow] ones load off the main thread. */
class Section(
    override val key: String,
    override val title: String,
    val label2: String = "",
    val slow: Boolean = false,
    val load: () -> List<Item>,
) : Item()

/** A programme of the TV guide; OK plays its channel. */
data class Programme(val channel: Channel, val prog: Guide.Prog) : Item() {
    override val key get() = "prog:${channel.url}:${prog.start}"
    override val title get() = prog.title
}

/** A settings row, or a plain hint when [id] is empty. */
data class Setting(val id: String, override val title: String, val value: String = "") : Item() {
    override val key get() = "setting:$id:$title"
}

private val MOVIES_SERIES = setOf(Cat.MOVIES, Cat.SERIES)

val APPS = listOf(
    // WATCHED and CINEFUSE TV used to be apps of their own: their code now runs inside IPTV4FREE
    App("cinefuse", emptyList(), "CINEFUSE", R.string.app_cinefuse, Terms.SITE, MOVIES_SERIES, false, com.local.cinefusetv.MainActivity::class.java),
    App("watched", emptyList(), "WATCHED", R.string.app_watched, Terms.SITE, MOVIES_SERIES, false, com.local.watchedtv.MainActivity::class.java),
    // checked on the TV in Portugal on 2026-11-11; Pluto TV ("not available in your location") and Tubi (not offered) are left out
    App("plex", listOf("com.plexapp.android"), "Plex", R.string.app_plex, Terms.FREE, MOVIES_SERIES + Cat.DOCS),
    App("rakuten", listOf("tv.wuaki.apptv", "tv.wuaki"), "Rakuten TV", R.string.app_rakuten, Terms.FREEMIUM, MOVIES_SERIES),
    App("arte", listOf("tv.arte.plus7"), "ARTE", R.string.app_arte, Terms.FREE, setOf(Cat.DOCS, Cat.MOVIES, Cat.SERIES, Cat.LIVE)),
    App("viki", listOf("com.viki.android"), "Viki", R.string.app_viki, Terms.ACCOUNT, setOf(Cat.SERIES)),
    App("crunchyroll", listOf("com.crunchyroll.crunchyroid"), "Crunchyroll", R.string.app_crunchyroll, Terms.PAID, setOf(Cat.SERIES)),
    App("rtp", listOf("pt.rtp.play"), "RTP Play", R.string.app_rtp, Terms.FREE, setOf(Cat.LIVE, Cat.SERIES, Cat.DOCS, Cat.SPORT)),
    App("tvi", listOf("pt.iol.tviplayer.androidtv"), "TVI Player", R.string.app_tvi, Terms.FREE, setOf(Cat.LIVE, Cat.SERIES)),
    App("opto", listOf("com.impresa.opta"), "OPTO SIC", R.string.app_opto, Terms.FREEMIUM, setOf(Cat.LIVE, Cat.SERIES)),
    App("france24", listOf("com.france24.androidapp.gtv", "com.france24.androidapp"), "France 24", R.string.app_france24, Terms.FREE, setOf(Cat.LIVE)),
    App("tv5monde", listOf("com.dotscreen.tv5mondeplus.mobile"), "TV5MONDE+", R.string.app_tv5monde, Terms.FREE, setOf(Cat.LIVE, Cat.DOCS)),
    App("rtve", listOf("com.rtve.androidtv"), "RTVE Play", R.string.app_rtve, Terms.FREE, setOf(Cat.LIVE, Cat.SERIES, Cat.DOCS)),
    App("redbull", listOf("com.nousguide.android.rbtv"), "Red Bull TV", R.string.app_redbull, Terms.FREE, setOf(Cat.SPORT, Cat.DOCS)),
    App("smarttube", listOf("org.smarttube.stable", "org.smarttube.beta"), "SmartTube", R.string.app_smarttube, Terms.FREE, setOf(Cat.YOUTUBE), false),
    App("youtube", listOf("com.google.android.youtube.tv"), "YouTube", R.string.app_youtube, Terms.FREE, setOf(Cat.YOUTUBE)),
    App("ytmusic", listOf("com.google.android.youtube.tvmusic"), "YouTube Music", R.string.app_ytmusic, Terms.FREE, setOf(Cat.YOUTUBE)),
    // the former IPTV4FREE tile (Kodi interface): shown only while it is still installed
    App("iptv4free", listOf("com.iptv4free.launcher"), "IPTV4FREE Kodi", R.string.app_iptv4free, Terms.FREE, setOf(Cat.IPTV), false),
    App("kodi", listOf("org.xbmc.kodi"), "Kodi", R.string.app_kodi, Terms.FREE, setOf(Cat.IPTV)),
    App("tivimate", listOf("ar.tvplayer.tv"), "TiviMate", R.string.app_tivimate, Terms.FREEMIUM, setOf(Cat.IPTV)),
    // Molotov Extra: paid French residency portability on temporary EU travel.
    App("molotov", listOf("tv.molotov.app"), "Molotov", R.string.app_molotov, Terms.FREEMIUM, setOf(Cat.LIVE, Cat.SPORT)),
    App("francetv", listOf("fr.francetv.pluzz"), "france.tv", R.string.app_france, Terms.FRANCE, setOf(Cat.LIVE)),
    App("tf1", listOf("fr.tf1.mytf1"), "TF1+", R.string.app_france, Terms.FRANCE, setOf(Cat.LIVE)),
    App("m6", listOf("fr.m6.m6replay"), "M6+", R.string.app_france, Terms.FRANCE, setOf(Cat.LIVE)),
    App("sporttv", listOf("pt.sporttv.app.androidtv"), "Sport TV", R.string.app_paid, Terms.PAID, setOf(Cat.SPORT)),
    App("dazn", listOf("com.dazn"), "DAZN", R.string.app_paid, Terms.PAID, setOf(Cat.SPORT)),
)
