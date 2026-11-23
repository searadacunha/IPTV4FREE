package com.local.streaming4free

import android.content.Context
import android.util.Log
import java.io.FileNotFoundException

/** One channel and how the menu files it; computed once, when the lists are read. */
class Info(val ch: Channel, val n: Int, val list: String, val groups: Set<String>, catalogCountry: String) {
    val country = channelCountry(ch.id).ifEmpty { catalogCountry }.ifEmpty { if (list == "france") "fr" else if (list == "portugal") "pt" else "" }
    /** Stated by the list, the feed or the catalog row; else the country's languages. */
    val langs: Set<String> = (setOf(listLang(list), feedLanguage(ch.id)) + groups.map(::groupLanguage)).filter { it.isNotEmpty() }.toSet()
        .ifEmpty { countryLanguages(country).toSet() }
    val genres: Set<Genre> = groups.flatMap(::groupGenres).toSet().ifEmpty { nameGenres(ch.title) }
    val fast = fastProvider(ch.title, ch.url, ch.logo, ch.id)

    fun has(g: Genre) = g in genres
    fun speaks(lang: String) = lang in langs

    private companion object {
        fun listLang(list: String) = when (list) { "france" -> "fr"; "portugal" -> "pt"; else -> "" }
    }
}

/**
 * Every channel of IPTV4FREE: the three lists the Kodi interface used (France, Portugal, Monde, shipped in the APK from
 * the TV's own copy, see docs/IPTV4FREE-APP.md) merged with the checked catalog. One entry per URL: a catalog channel
 * keeps its checked alternates, and gets the list groups of the same URL.
 */
object Library {
    val LISTS = listOf("france", "portugal", "monde")

    @Volatile private var all: List<Info>? = null
    @Volatile private var byUrl: Map<String, Info> = emptyMap()

    /** Blocking (about a second on the TV): call it off the main thread. */
    @Synchronized
    fun get(c: Context): List<Info> = all ?: build(c).also { l -> byUrl = l.associateBy { it.ch.url }; all = l }

    /** The channel's entry, for its number, country and groups; null for a channel of a public playlist. */
    fun info(url: String) = byUrl[url]

    private class Entry(var ch: Channel, val list: String, val groups: LinkedHashSet<String>)

    private fun build(c: Context): List<Info> {
        val byUrl = LinkedHashMap<String, Entry>()
        for (list in LISTS) {
            val parsed = try {
                c.assets.open("tv/$list.m3u").bufferedReader().useLines { parseM3u(it, "IPTV4FREE") }
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "list $list not in this build") // test builds: catalog only
                emptyList()
            }
            for (ch in parsed) {
                val groups = ch.group.split(';').map { it.trim() }.filter { it.isNotEmpty() }
                val e = byUrl.getOrPut(ch.url) { Entry(ch.copy(group = groups.firstOrNull().orEmpty()), list, LinkedHashSet()) }
                e.groups += groups
            }
        }
        val codes = HashMap<String, String>()
        for (row in Catalog.rows(c)) for (ch in row.items) {
            codes[ch.url] = Catalog.countries[ch.url].orEmpty()
            val e = byUrl[ch.url]
            if (e == null) byUrl[ch.url] = Entry(ch, "catalog", linkedSetOf("row:${row.cat}/${row.row}"))
            else {
                if (e.list != "catalog") e.ch = ch.copy(logo = ch.logo.ifEmpty { e.ch.logo }, group = e.ch.group)
                e.groups += "row:${row.cat}/${row.row}"
            }
        }
        return byUrl.values.mapIndexed { i, e -> Info(e.ch, i + 1, e.list, e.groups, codes[e.ch.url].orEmpty()) }
            .also { Log.i(TAG, "library: ${it.size} channels") }
    }
}
