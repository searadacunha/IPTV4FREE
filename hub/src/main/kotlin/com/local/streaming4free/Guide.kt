package com.local.streaming4free

import android.content.Context
import android.util.Log
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream

/**
 * The TV guide, from the same two XMLTV files the Kodi interface used (France TNT, Portugal). Downloaded by the TV
 * every 12 hours, read as a stream, and only the next 30 hours (and the last 4) of the channels kept.
 * A channel gets a guide the way IPTV Simple found it, without guessing between namesakes: its tvg-id, then a
 * display name equal to its name, then a loose name that only one guide channel has, in its own country only.
 */
object Guide {
    class Prog(val start: Long, val stop: Long, val title: String, val desc: String, val category: String) {
        fun on(now: Long) = now in start until stop
        fun progress(now: Long) = if (stop > start) ((now - start) * 100 / (stop - start)).toInt().coerceIn(0, 100) else 0
    }

    class Source(val name: String, val url: String, val country: String)

    val SOURCES = listOf(
        Source("fr", "https://xmltvfr.fr/xmltv/xmltv_tnt.xml.gz", "fr"),
        Source("pt", "https://epgshare01.online/epgshare01/epg_ripper_PT1.xml.gz", "pt"),
    )

    /** Guide channel ids by lowercased id, display name and loose name; a name two channels share maps to none. */
    class Index {
        val ids = HashMap<String, String>()
        val display = HashMap<String, String>()
        val names = HashMap<String, String>()
        private val shared = HashSet<String>()

        fun add(id: String, displayNames: List<String>) {
            ids.putIfAbsent(id.lowercase(), id)
            for (d in displayNames) {
                put(display, "d:" + d.trim().lowercase(), id)
                put(names, "n:" + matchKey(d), id)
            }
        }

        private fun put(map: HashMap<String, String>, key: String, id: String) {
            val k = key.substring(2)
            if (k.isEmpty() || key in shared) return
            val old = map[k]
            if (old == null) map[k] = id else if (!old.equals(id, true)) { map.remove(k); shared += key }
        }
    }

    class Parsed(val index: Index, val progs: Map<String, List<Prog>>)

    private const val MAX_AGE = 12 * 3600_000L
    private const val PAST = 4 * 3600_000L
    private const val AHEAD = 30 * 3600_000L
    // names the TNT guide spells otherwise (from the IPTV4FREE list builder)
    private val ALIASES = mapOf("tfx" to "NT1.fr", "rmcstory" to "Numero23.fr", "lequipe" to "LEquipe21.fr",
        "lcp" to "LaChaineParlementaire.fr", "m6" to "M6.fr", "tmc" to "TMC.fr")

    @Volatile private var parsed: Map<String, Parsed> = emptyMap()
    private val ids = ConcurrentHashMap<String, String>() // channel URL -> guide id, "" = none

    fun loaded() = parsed.isNotEmpty()
    fun channels() = parsed.values.sumOf { it.progs.size }

    private fun file(c: Context, s: Source) = File(c.filesDir, "guide-${s.name}.txt")

    /** True when a source's copy is missing or older than 12 hours. */
    fun stale(c: Context) = SOURCES.any { val f = file(c, it); !f.exists() || System.currentTimeMillis() - f.lastModified() !in 0..MAX_AGE }

    /** Reads the copies kept on the TV. Blocking. */
    fun load(c: Context) {
        val out = HashMap<String, Parsed>()
        for (s in SOURCES) try {
            val f = file(c, s)
            if (f.exists()) out[s.name] = f.bufferedReader().use { readCache(it.lineSequence()) }
        } catch (e: Exception) {
            Log.w(TAG, "guide ${s.name} cache: $e")
        }
        set(out)
    }

    /** Downloads both guides; a failed one keeps its old copy. Blocking. Returns how many sources were refreshed. */
    fun refresh(c: Context): Int {
        val out = HashMap(parsed)
        var ok = 0
        val now = System.currentTimeMillis()
        for (s in SOURCES) try {
            val p = try { download(s, now) } catch (e: IOException) { Log.w(TAG, "guide ${s.name}: $e, second try"); download(s, now) } // a reset was seen once on the TV
            if (p.progs.isEmpty()) throw IOException("no programme")
            val tmp = File(c.filesDir, "guide-${s.name}.tmp")
            tmp.bufferedWriter().use { w -> writeCache(p, w) }
            if (!tmp.renameTo(file(c, s))) throw IOException("cannot store the guide")
            out[s.name] = p
            ok++
            Log.i(TAG, "guide ${s.name}: ${p.progs.size} channels, ${p.progs.values.sumOf { it.size }} programmes")
        } catch (e: Exception) {
            Log.w(TAG, "guide ${s.name}: $e")
        }
        set(out)
        return ok
    }

    private fun download(s: Source, now: Long): Parsed {
        val http = URL(s.url).openConnection() as HttpURLConnection
        try {
            http.connectTimeout = 10_000
            http.readTimeout = 30_000
            http.setRequestProperty("User-Agent", "IPTV4FREE (Android TV)")
            if (http.responseCode != 200) throw IOException("HTTP ${http.responseCode}")
            return http.inputStream.use { raw -> GZIPInputStream(raw.buffered()).use { parse(Xml.newPullParser(), it, now - PAST, now + AHEAD) } }
        } finally {
            http.disconnect()
        }
    }

    private fun set(p: Map<String, Parsed>) {
        parsed = p
        ids.clear()
    }

    /** The programmes of [ch], sorted, or an empty list. [country]: the channel's, "" if unknown. */
    fun schedule(ch: Channel, country: String): List<Prog> {
        val id = ids.getOrPut(ch.url) { match(ch, country).orEmpty() }
        if (id.isEmpty()) return emptyList()
        return parsed.values.firstNotNullOfOrNull { it.progs[id] }.orEmpty()
    }

    fun now(ch: Channel, country: String, now: Long) = schedule(ch, country).firstOrNull { it.on(now) }
    fun next(ch: Channel, country: String, now: Long) = schedule(ch, country).firstOrNull { it.start >= now }

    fun match(ch: Channel, country: String): String? = match(ch, country, parsed)

    internal fun match(ch: Channel, country: String, guides: Map<String, Parsed>): String? {
        for (s in SOURCES) {
            if (country.isNotEmpty() && country != s.country) continue
            val ix = guides[s.name]?.index ?: continue
            val key = matchKey(ch.title)
            for (cid in listOfNotNull(ALIASES[key], ch.id, ch.id.substringBefore('@'))) ix.ids[cid.trim().lowercase()]?.let { return it }
            ix.display[ch.title.trim().lowercase()]?.let { return it }
            ix.names[key]?.let { return it }
        }
        return null
    }

    /** XMLTV to channels and programmes between [from] and [to]. [p] comes from Xml.newPullParser() (a plain KXmlParser in tests). */
    fun parse(p: XmlPullParser, input: InputStream, from: Long, to: Long): Parsed {
        p.setInput(input, null)
        val index = Index()
        val progs = HashMap<String, MutableList<Prog>>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) when (p.name) {
                "channel" -> {
                    val id = p.getAttributeValue(null, "id").orEmpty()
                    val names = ArrayList<String>()
                    readChildren(p, "channel") { tag, text -> if (tag == "display-name") names += text }
                    if (id.isNotEmpty()) index.add(id, names)
                }
                "programme" -> {
                    val id = p.getAttributeValue(null, "channel").orEmpty()
                    val start = xmltvTime(p.getAttributeValue(null, "start").orEmpty())
                    val stop = xmltvTime(p.getAttributeValue(null, "stop").orEmpty())
                    var title = ""
                    var sub = ""
                    var desc = ""
                    var cat = ""
                    readChildren(p, "programme") { tag, text ->
                        when (tag) {
                            "title" -> if (title.isEmpty()) title = text
                            "sub-title" -> if (sub.isEmpty()) sub = text
                            "desc" -> if (desc.isEmpty()) desc = text
                            "category" -> if (cat.isEmpty()) cat = text
                        }
                    }
                    if (id.isNotEmpty() && title.isNotEmpty() && start > 0 && stop > start && stop > from && start < to)
                        progs.getOrPut(id) { ArrayList() } += Prog(start, stop, clean(title), clean(listOf(sub, desc).filter { it.isNotEmpty() }.joinToString(" — ")).take(400), clean(cat))
                }
            }
            event = p.next()
        }
        return Parsed(index, progs.mapValues { (_, l) -> l.sortedBy { it.start } })
    }

    /** Calls [f] with each direct child's tag and text, up to the end of [parent]. */
    private fun readChildren(p: XmlPullParser, parent: String, f: (String, String) -> Unit) {
        var depth = 1
        var tag = ""
        val text = StringBuilder()
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.START_TAG -> { depth++; if (depth == 2) { tag = p.name; text.setLength(0) } }
                XmlPullParser.TEXT -> if (depth == 2) text.append(p.text)
                XmlPullParser.END_TAG -> { if (depth == 2) f(tag, text.toString().trim()); depth-- }
                XmlPullParser.END_DOCUMENT -> throw IOException("unexpected end in <$parent>")
            }
        }
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim()

    internal fun writeCache(p: Parsed, w: java.io.Writer) {
        val names = HashMap<String, MutableList<String>>() // id -> display names, rebuilt from the index
        for ((n, id) in p.index.display) names.getOrPut(id) { ArrayList() } += n
        for (id in p.index.ids.values) w.write("C\t$id\t${names[id].orEmpty().joinToString("\u001f")}\n")
        for ((id, l) in p.progs) for (g in l) w.write("P\t$id\t${g.start}\t${g.stop}\t${g.title}\t${g.category}\t${g.desc}\n")
    }

    internal fun readCache(lines: Sequence<String>): Parsed {
        val index = Index()
        val progs = HashMap<String, MutableList<Prog>>()
        for (line in lines) {
            val f = line.split('\t')
            when {
                f[0] == "C" && f.size >= 3 -> index.add(f[1], f[2].split('\u001f').filter { it.isNotEmpty() })
                f[0] == "P" && f.size >= 7 -> progs.getOrPut(f[1]) { ArrayList() } += Prog(f[2].toLong(), f[3].toLong(), f[4], f[6], f[5])
            }
        }
        return Parsed(index, progs)
    }
}

/** Loose key for comparing channel names: accents, case, punctuation and quality tags dropped (as the IPTV4FREE builder). */
fun matchKey(name: String): String = fold(name).replace(BRACKETS, "").replace(QUALITY_TAG, "").filter { it.isLetterOrDigit() || it == '+' }

private val BRACKETS = Regex("""\(.*?\)|\[.*?]""")
private val QUALITY_TAG = Regex("\\b(hd|sd|fhd|uhd|4k)\\b")

/** XMLTV time "20261123190000 +0100" in epoch ms; no offset = UTC; 0 when unreadable. */
fun xmltvTime(s: String): Long {
    if (s.length < 14 || !s.substring(0, 14).all { it.isDigit() }) return 0
    fun n(a: Int, b: Int) = s.substring(a, b).toInt()
    val tz = s.substring(14).trim()
    val offset = if (tz.length >= 5 && (tz[0] == '+' || tz[0] == '-') && tz.substring(1, 5).all { it.isDigit() })
        (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt()) * (if (tz[0] == '-') -1 else 1) else 0
    return (daysFromCivil(n(0, 4), n(4, 6), n(6, 8)) * 86_400L + n(8, 10) * 3600L + n(10, 12) * 60L + n(12, 14) - offset * 60L) * 1000L
}

/** Days since 1970-01-01 of a Gregorian date (H. Hinnant's algorithm). */
private fun daysFromCivil(year: Int, m: Int, d: Int): Long {
    val y = if (m <= 2) year - 1 else year
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (m + if (m > 2) -3 else 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146_097L + doe - 719_468
}
