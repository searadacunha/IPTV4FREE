package com.local.streaming4free

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

const val TAG = "S4F"

/** Downloads, parsing and search run here, never on the main thread. */
val IO = Executors.newFixedThreadPool(2)

fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

fun channel(o: JSONObject) = Channel(o.getString("n"), o.getString("u"), o.optString("l"), o.optString("g"), o.optString("s"), o.optString("id"), o.optString("q"),
    o.optJSONArray("a")?.let { a -> (0 until a.length()).map(a::getString) }.orEmpty(), o.optBoolean("geo"), o.optBoolean("h"))

/** Favorites, history, resume positions and settings: kept on the TV, nothing leaves it. */
object Store {
    private lateinit var prefs: SharedPreferences
    private lateinit var positions: SharedPreferences
    private lateinit var failures: SharedPreferences

    fun init(c: Context) {
        if (::prefs.isInitialized) return
        val app = c.applicationContext ?: c
        prefs = app.getSharedPreferences("hub", Context.MODE_PRIVATE)
        positions = app.getSharedPreferences("positions", Context.MODE_PRIVATE)
        failures = app.getSharedPreferences("failures", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis() // one entry per URL ever opened: forget the old ones
        val stale = failures.all.filter { (it.value as? String)?.let(Health::decode)?.stale(now) != false }.keys
        if (stale.isNotEmpty()) failures.edit().apply { stale.forEach(::remove) }.apply()
    }

    /** "" follows the TV's language. */
    var lang: String
        get() = prefs.getString("lang", "")!!
        set(v) = prefs.edit().putString("lang", v).apply()

    /** 0 lets the player pick the quality from the bandwidth. */
    var maxHeight: Int
        get() = prefs.getInt("maxHeight", 0)
        set(v) = prefs.edit().putInt("maxHeight", v).apply()

    fun localized(c: Context): Context {
        init(c)
        if (lang.isEmpty()) return c
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocale(Locale(lang))
        return c.createConfigurationContext(cfg)
    }

    /** Called on the main thread after favorites or history change, by the page on screen. */
    var onChange: (() -> Unit)? = null

    fun favorites() = list("favorites")
    fun history() = list("history")

    /** Returns true when [i] is now a favorite. */
    fun toggleFavorite(i: Item): Boolean {
        val l = favorites().toMutableList()
        val added = !l.removeAll { it.key == i.key }
        if (added) l.add(0, i)
        save("favorites", l)
        onChange?.invoke()
        return added
    }

    fun addHistory(i: Item) = save("history", (listOf(i) + history().filter { it.key != i.key }).take(40))

    fun clearHistory() {
        prefs.edit().remove("history").apply()
        positions.edit().clear().apply()
        onChange?.invoke()
    }

    /** Resume position of a film or replay; live streams have none. */
    fun position(url: String) = positions.getLong(url, 0)
    fun setPosition(url: String, ms: Long) = positions.edit().apply { if (ms > 0) putLong(url, ms) else remove(url) }.apply()

    /** What this TV saw of a URL. Entries of the old format (a bare failure time) read as unknown. */
    fun health(url: String): Health? = try { Health.decode(failures.getString(url, null)) } catch (e: ClassCastException) { null }
    fun recordOk(url: String) = setHealth(url, (health(url) ?: Health()).ok(System.currentTimeMillis()))
    fun recordFail(url: String, k: Kind) = setHealth(url, (health(url) ?: Health()).fail(k, System.currentTimeMillis()))
    private fun setHealth(url: String, h: Health) = failures.edit().putString(url, h.encode()).apply()

    /** A stream that failed on this TV for a reason of its own stays greyed out for a day, or until it plays again. */
    fun isDead(url: String) = health(url)?.dead(System.currentTimeMillis()) == true

    private fun list(name: String): List<Item> = try {
        JSONArray(prefs.getString(name, "[]")).objects().mapNotNull { o ->
            if (o.has("app")) APPS.find { it.id == o.getString("app") } else channel(o)
        }
    } catch (e: Exception) { // unreadable after a format change: start empty rather than crash at launch
        Log.w(TAG, "$name unreadable: $e")
        emptyList()
    }

    private fun save(name: String, items: List<Item>) {
        val a = JSONArray()
        for (i in items) when (i) {
            is App -> a.put(JSONObject().put("app", i.id))
            is Channel -> a.put(JSONObject().put("n", i.title).put("u", i.url).put("l", i.logo).put("g", i.group).put("s", i.source).put("id", i.id).put("q", i.quality)
                .put("a", JSONArray(i.alts)).put("geo", i.geo).put("h", i.headers))
            else -> {}
        }
        prefs.edit().putString(name, a.toString()).apply()
    }
}

/** The channels checked from the TV's own network by tools/probe_streams.py, shipped inside the app. */
object Catalog {
    class Row(val cat: String, val row: String, val items: List<Channel>)

    var checked = ""
        private set
    private var rows: List<Row> = emptyList()
    /** URL -> country code the probe gave, for the menu's country and language sections. */
    var countries: Map<String, String> = emptyMap()
        private set

    @Synchronized
    fun rows(c: Context): List<Row> {
        if (rows.isEmpty()) {
            val o = JSONObject(c.assets.open("catalog.json").bufferedReader().use { it.readText() })
            checked = o.getString("checked")
            val codes = HashMap<String, String>()
            rows = o.getJSONArray("rows").objects().map { r ->
                Row(r.getString("cat"), r.getString("row"), r.getJSONArray("items").objects().map {
                    codes[it.getString("u")] = it.optString("c")
                    channel(it).copy(group = countryName(it.optString("c")))
                })
            }
            countries = codes
        }
        return rows
    }

    private var flat: List<Channel> = emptyList()

    /** Every checked channel, for alternates and search; built once since cards ask for it on every bind. */
    @Synchronized
    fun all(c: Context): List<Channel> {
        if (flat.isEmpty()) flat = rows(c).flatMap { it.items }
        return flat
    }
}

fun countryName(code: String) = if (code.length == 2) Locale("", code.uppercase(Locale.ROOT)).displayCountry else ""

/** The three public playlists, downloaded by the TV itself and kept 12 hours. */
internal fun playlistCacheExpired(modified: Long, now: Long, maxAge: Long = 12 * 3600_000L): Boolean =
    modified <= 0L || modified > now || now - modified > maxAge

/** An empty download is refused; so is a truncated one (less than half the old count) unless [force]d, as a list may really shrink. */
internal fun keepOldPlaylist(fresh: Int, old: Int, force: Boolean = false) = fresh == 0 || !force && fresh * 2 < old

object Playlists {
    class Source(val name: String, val url: String)

    val SOURCES = listOf(
        Source("M3UPT", "https://m3upt.com/iptv"),
        Source("Free-TV", "https://raw.githubusercontent.com/Free-TV/IPTV/master/playlist.m3u8"),
        Source("iptv-org", "https://iptv-org.github.io/iptv/index.m3u"),
    )
    private const val MAX_AGE = 12 * 3600_000L
    private val memory = ConcurrentHashMap<String, List<Channel>>()
    private val folders = ConcurrentHashMap<String, List<Folder>>()
    private val failedAt = ConcurrentHashMap<String, Long>() // no new download for 10 minutes after a failed one
    private val FIRST = listOf("TV", "Portugal", "France", "Brazil", "Belgium", "Switzerland")

    fun file(c: Context, s: Source) = File(c.filesDir, s.name + ".m3u")

    /** Whatever is already in memory, for the search screen. */
    fun loaded(s: Source) = memory[s.name].orEmpty()

    /** The list's groups (country, category), Portuguese and French first, then the biggest. Blocking. */
    fun folders(c: Context, s: Source, refresh: Boolean = false): List<Folder> {
        // Cached folders must not defeat the on-disk 12-hour refresh policy.
        if (!refresh && !playlistCacheExpired(file(c, s).lastModified(), System.currentTimeMillis()))
            folders[s.name]?.let { return it }
        val groups = LinkedHashMap<String, MutableList<Channel>>()
        for (ch in load(c, s, refresh))
            for (g in ch.group.split(';')) groups.getOrPut(g.trim().ifEmpty { "-" }) { ArrayList() } += ch
        return groups.map { (g, l) -> Folder(g, l, s.name) }
            .sortedWith(compareBy({ FIRST.indexOf(it.title).let { i -> if (i < 0) FIRST.size else i } }, { -it.channels.size }))
            .also { folders[s.name] = it }
    }

    /** Blocking: call it off the main thread. Offline, the last downloaded copy is used, however old. */
    @Throws(IOException::class)
    fun load(c: Context, s: Source, refresh: Boolean = false): List<Channel> = synchronized(s) { // one download per list at a time
        val file = file(c, s)
        val now = System.currentTimeMillis()
        val expired = playlistCacheExpired(file.lastModified(), now, MAX_AGE)
        if (!refresh && !expired) memory[s.name]?.let { return it }
        var fresh: List<Channel>? = null
        if (refresh || expired && now - (failedAt[s.name] ?: 0L) !in 0 until 10 * 60_000L) try {
            val tmp = File(c.filesDir, s.name + ".tmp")
            val http = URL(s.url).openConnection() as HttpURLConnection
            try {
                http.connectTimeout = 10_000
                http.readTimeout = 20_000
                if (http.responseCode != 200) throw IOException("HTTP ${http.responseCode}")
                http.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            } finally {
                http.disconnect()
            }
            // a captive portal, an error page or a cut download must not replace a good list
            if (tmp.useLines { it.firstOrNull()?.trimStart('\uFEFF')?.startsWith("#EXTM3U") } != true) throw IOException("not a playlist")
            val parsed = tmp.useLines { parseM3u(it, s.name) }
            val old = memory[s.name] ?: if (file.exists()) file.useLines { parseM3u(it, s.name) }.also { memory[s.name] = it } else emptyList()
            if (keepOldPlaylist(parsed.size, old.size, refresh || now - file.lastModified() > 3 * 24 * 3600_000L)) throw IOException("${parsed.size} channels instead of ${old.size}")
            if (!tmp.renameTo(file)) throw IOException("cannot store the playlist")
            fresh = parsed
            failedAt.remove(s.name)
        } catch (e: IOException) {
            failedAt[s.name] = now
            Log.w(TAG, "playlist ${s.name}: $e")
            // Manual refresh must report a failure even if the last good copy is preserved.
            if (refresh || !file.exists()) throw e
        } else if (!file.exists()) throw IOException("${s.name}: download failed less than 10 minutes ago")
        if (fresh != null) folders.remove(s.name)
        return (fresh ?: memory[s.name] ?: file.useLines { parseM3u(it, s.name) }).also { memory[s.name] = it }
    }
}

private val ATTR = Regex("""([\w-]+)="([^"]*)"""")
private val MARKERS = Regex("\\s*[ⓈⒼⓎⒹ]") // Free-TV's flags (geo-blocked, YouTube...), not part of the name

private val GEO_LABEL = Regex("""\s*\[Geo-blocked]\s*$""", RegexOption.IGNORE_CASE) // iptv-org's flag
private val HEADER_OPT = Regex("http-(referrer|user-agent)", RegexOption.IGNORE_CASE)
// license_type/license_key, drm_legacy=org.w3.clearkey|..., drm={...}: inputstream.adaptive's ways to declare a DRM key
private val DRM_PROP = Regex("license|drm|clearkey|widevine|playready", RegexOption.IGNORE_CASE)

/**
 * Reads an extended M3U playlist. Left out: entries that are not http(s) (Kodi's YouTube links become YouTube pages), and entries that only play with a DRM key
 * written in the list. Request headers a list declares (browser user agent, Referer) are never sent by the player:
 * such entries are only flagged, as are the geo-blocked ones.
 */
fun parseM3u(lines: Sequence<String>, source: String): List<Channel> {
    val out = ArrayList<Channel>()
    var info: String? = null
    var drm = false // option lines may come before or after #EXTINF: they hold until the entry's URL
    var headers = false
    for (raw in lines) {
        val line = raw.trim()
        when {
            line.startsWith("#EXTINF") -> info = line
            line.isEmpty() -> {}
            line.startsWith("#KODIPROP") && DRM_PROP.containsMatchIn(line) -> drm = true
            line.startsWith("#EXTVLCOPT") && HEADER_OPT.containsMatchIn(line) -> headers = true
            line.startsWith("#") -> {}
            else -> {
                val inf = info
                val url = playable(line)
                if (inf != null && !drm && url != null) {
                    val comma = nameComma(inf)
                    val a = ATTR.findAll(if (comma < 0) inf else inf.substring(0, comma)).associate { it.groupValues[1] to it.groupValues[2] }
                    val text = if (comma < 0) "" else inf.substring(comma + 1)
                    val name = text.replace(GEO_LABEL, "").replace(MARKERS, "").trim()
                    out += Channel(name.ifEmpty { a["tvg-name"].orEmpty() }, url, a["tvg-logo"].orEmpty(), a["group-title"].orEmpty(), source,
                        a["tvg-id"].orEmpty(), geo = 'Ⓖ' in text || GEO_LABEL.containsMatchIn(text),
                        headers = headers || "http-referrer" in a || "http-user-agent" in a)
                }
                info = null
                drm = false
                headers = false
            }
        }
    }
    return out
}

private val KODI_YOUTUBE = Regex("""^plugin://plugin\.video\.youtube/play/\?video_id=([\w-]{6,20})$""")

/** An http(s) URL as is; Kodi's YouTube add-on link (IPTV4FREE's franceinfo) as the YouTube page SmartTube opens; anything else null. */
internal fun playable(line: String): String? = when {
    line.startsWith("http://") || line.startsWith("https://") -> line
    else -> KODI_YOUTUBE.find(line)?.let { "https://www.youtube.com/watch?v=" + it.groupValues[1] }
}

/** The comma that ends the attributes: the first one outside double quotes, so quotes and commas in the name stay. */
private fun nameComma(info: String): Int {
    var quoted = false
    for ((i, ch) in info.withIndex()) {
        if (ch == '"') quoted = !quoted else if (ch == ',' && !quoted) return i
    }
    return -1
}
