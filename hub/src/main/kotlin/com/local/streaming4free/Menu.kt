package com.local.streaming4free

import android.content.Context
import android.net.Uri
import java.util.Calendar
import java.util.Locale

/** Titles of the catalog rows written by tools/probe_streams.py, keyed "cat/row". */
val ROW_TITLES = mapOf(
    "live/pt" to R.string.row_live_pt, "live/fra" to R.string.row_live_fra, "live/por" to R.string.row_live_por,
    "live/kids" to R.string.row_live_kids, "live/music" to R.string.row_live_music, "live/news" to R.string.row_live_news,
    "live/other" to R.string.row_live_other,
    "sport/por" to R.string.row_sport_por, "sport/fra" to R.string.row_sport_fra, "sport/int" to R.string.row_sport_int,
    "docs/por" to R.string.row_docs_por, "docs/fra" to R.string.row_docs_fra, "docs/int" to R.string.row_docs_int,
    "movies/por" to R.string.row_movies_por, "movies/fra" to R.string.row_movies_fra, "movies/int" to R.string.row_movies_int,
    "series/por" to R.string.row_series_por, "series/fra" to R.string.row_series_fra, "series/int" to R.string.row_series_int,
)

private val CUT_TITLES = mapOf(
    "football" to R.string.cut_football, "combat" to R.string.cut_combat, "basket" to R.string.cut_basket, "motor" to R.string.cut_motor,
    "tennis" to R.string.cut_tennis, "golf" to R.string.cut_golf, "cycling" to R.string.cut_cycling, "rugby" to R.string.cut_rugby,
    "horse" to R.string.cut_horse, "extreme" to R.string.cut_extreme, "history" to R.string.cut_history, "science" to R.string.cut_science,
    "nature" to R.string.cut_nature, "travel" to R.string.cut_travel, "economy" to R.string.cut_economy, "society" to R.string.cut_society,
    "action" to R.string.cut_action, "comedy" to R.string.cut_comedy, "horror" to R.string.cut_horror, "crime" to R.string.cut_crime,
    "scifi" to R.string.cut_scifi, "romance" to R.string.cut_romance, "western" to R.string.cut_western, "classics" to R.string.cut_classics,
    "family" to R.string.cut_family, "asia" to R.string.cut_asia, "drama" to R.string.cut_drama, "anime" to R.string.cut_anime,
    "reality" to R.string.cut_reality,
)

private val GENRE_TITLES = mapOf(
    Genre.MOVIES to R.string.g_movies, Genre.SERIES to R.string.g_series, Genre.KIDS to R.string.g_kids, Genre.SPORT to R.string.g_sport,
    Genre.DOCS to R.string.g_docs, Genre.MUSIC to R.string.g_music, Genre.NEWS to R.string.g_news, Genre.GENERAL to R.string.g_general,
    Genre.RELIGION to R.string.g_religion, Genre.OTHER to R.string.g_other,
)

private val SPORT_PROG = Regex("(?i)sport|desport|futebol|football|soccer|rugby|tennis|t[eé]nis|basket|cyclisme|ciclismo|boxe|boxing|golf|moto|formul|automobil|handball|athl|natation|ski|surf|combat|mma|ufc|catch|equita|hipism")

/**
 * The IPTV4FREE menu: the rail's sections and everything under them, built once from the library (the channel rows are
 * fixed), with the rows that change (apps installed, favorites, history, guide, search) computed when shown.
 * Empty rows are left out. A channel always stays reachable: in its list's full row, its country and "All channels".
 */
class Menu(private val c: Context, private val lib: List<Info>) {
    private val pm = c.packageManager
    private fun t(id: Int) = c.getString(id)

    private fun chans(p: (Info) -> Boolean): List<Item> = lib.filter(p).map { it.ch }

    @Suppress("DEPRECATION") // configuration.locales needs API 24
    private fun uiLocale(): Locale = c.resources.configuration.locale

    /** A row of fixed content, or null when there is nothing in it. */
    private fun leaf(key: String, title: String, label2: String, items: List<Item>) =
        if (items.isEmpty()) null else Section(key, title, label2) { items }

    private fun node(key: String, title: String, label2: String, kids: List<Section?>): Section? {
        val k = kids.filterNotNull()
        return if (k.isEmpty()) null else Section(key, title, label2) { k }
    }

    private fun live(key: String, title: String, label2: String, slow: Boolean = false, load: () -> List<Item>) = Section(key, title, label2, slow, load)

    private fun apps(vararg cats: Cat): List<Item> = APPS.filter { a -> a.cats.any { it in cats } && a.visible(pm) }
        .sortedBy { !it.ready(pm) } // built in and installed first, then those to install

    private fun genres(prefix: String, label2: String, list: List<Genre>, p: (Info) -> Boolean) =
        list.map { g -> leaf("$prefix/$g", t(GENRE_TITLES.getValue(g)), label2, chans { p(it) && it.has(g) }) }

    private fun cuts(prefix: String, label2: String, cuts: List<Cut>, p: (Info) -> Boolean): List<Section?> {
        val pool = lib.filter(p)
        val by = pool.associateWith { cutsOf(it.ch.title, cuts) }
        return cuts.map { cut -> leaf("$prefix/${cut.key}", t(CUT_TITLES.getValue(cut.key)), label2, pool.filter { cut.key in by.getValue(it) }.map { it.ch }) } +
            leaf("$prefix/other", t(R.string.cut_other), label2, pool.filter { by.getValue(it).isEmpty() }.map { it.ch })
    }

    private val fr = { i: Info -> i.list == "france" || i.country == "fr" }
    private val pt = { i: Info -> i.list == "portugal" || i.country == "pt" }
    private val intl = { i: Info -> i.list != "france" && i.list != "portugal" && i.country != "fr" && i.country != "pt" }
    private val themes = listOf(Genre.MOVIES, Genre.SERIES, Genre.KIDS, Genre.SPORT, Genre.DOCS, Genre.MUSIC, Genre.RELIGION, Genre.OTHER)

    val rail: List<Section> = listOf(tv(), streaming(), movies(), series(), sport(), docs(),
        favorites(), resume(), Section("search", t(R.string.rail_search)) { emptyList() }, guide(), all(), settings())

    private fun tv(): Section {
        val tvLabel = t(R.string.rail_tv)
        val frLabel = t(R.string.tv_france)
        val ptLabel = t(R.string.tv_portugal)
        val france = node("tv/fr", frLabel, tvLabel, listOf(
            leaf("tv/fr/tnt", t(R.string.tv_tnt), frLabel, lib.filter { fr(it) && tntRank(it.ch.id) >= 0 }.sortedBy { tntRank(it.ch.id) }.map { it.ch }),
            leaf("tv/fr/fav", t(R.string.tv_fav_list), frLabel, chans { "France · Favoris" in it.groups }),
            leaf("tv/fr/news", t(R.string.g_news), frLabel, chans { fr(it) && it.has(Genre.NEWS) && !isFrenchRegional(it.ch.title) }),
            leaf("tv/fr/regional", t(R.string.tv_regional), frLabel, chans { fr(it) && it.country in setOf("fr", "") && isFrenchRegional(it.ch.title) }),
            node("tv/fr/themes", t(R.string.tv_themes), frLabel, genres("tv/fr/g", frLabel, themes, fr)),
            leaf("tv/fr/fast", t(R.string.tv_free_fast), frLabel, chans { fr(it) && it.fast != null }),
            leaf("tv/fr/francophonie", t(R.string.tv_francophonie), frLabel, chans { it.speaks("fr") && it.country !in setOf("fr", "") }),
            leaf("tv/fr/all", t(R.string.tv_full_fr), frLabel, chans(fr)),
        ))
        val portugal = node("tv/pt", ptLabel, tvLabel, listOf(
            leaf("tv/pt/fav", t(R.string.tv_fav_list), ptLabel, chans { "Portugal · Favoris" in it.groups }),
            leaf("tv/pt/national", t(R.string.tv_national), ptLabel, chans { pt(it) && it.country in setOf("pt", "") && isPortugueseNational(it.ch.title) }),
            leaf("tv/pt/news", t(R.string.g_news), ptLabel, chans { pt(it) && (isPortugueseNews(it.ch.title) || it.has(Genre.NEWS)) }),
            leaf("tv/pt/regional", t(R.string.tv_pt_regional), ptLabel, chans { pt(it) && (isPortugueseRegional(it.ch.title) || !isPortugueseNational(it.ch.title)) }),
            node("tv/pt/themes", t(R.string.tv_themes), ptLabel, genres("tv/pt/g", ptLabel, themes, pt)),
            leaf("tv/pt/all", t(R.string.tv_full_pt), ptLabel, chans(pt)),
        ))
        val lusoLabel = t(R.string.tv_luso)
        val luso = { i: Info -> i.country in LUSO_AFRICA || i.country in setOf("br", "mo", "tl") || (i.speaks("pt") && i.list != "portugal" && i.country != "pt") }
        val lusophone = node("tv/luso", lusoLabel, tvLabel, listOf(
            leaf("tv/luso/br", t(R.string.tv_brazil), lusoLabel, chans { luso(it) && it.country == "br" }),
            leaf("tv/luso/africa", t(R.string.tv_luso_africa), lusoLabel, chans { luso(it) && it.country in LUSO_AFRICA }),
            leaf("tv/luso/mo", t(R.string.tv_macao), lusoLabel, chans { luso(it) && it.country == "mo" }),
            leaf("tv/luso/other", t(R.string.tv_luso_other), lusoLabel, chans { luso(it) && it.country !in LUSO_AFRICA + setOf("br", "mo") }),
        ))
        val fastLabel = t(R.string.tv_fast)
        val providers = lib.mapNotNull { it.fast }.groupingBy { it }.eachCount().entries
            .sortedWith(compareBy({ it.key == OTHER_FAST }, { -it.value })).map { it.key }
        val fast = node("tv/fast", fastLabel, tvLabel, providers.map { p ->
            val name = if (p == OTHER_FAST) t(R.string.fast_other) else p
            node("tv/fast/$p", name, fastLabel, listOf(leaf("tv/fast/$p/all", t(R.string.all_channels), name, chans { it.fast == p })) +
                genres("tv/fast/$p/g", name, Genre.entries, { it.fast == p }))
        })
        val intLabel = t(R.string.tv_international)
        val international = node("tv/int", intLabel, tvLabel, listOf(
            leaf("tv/int/news", t(R.string.tv_int_news), intLabel, chans { intl(it) && it.has(Genre.NEWS) }),
            leaf("tv/int/en", t(R.string.tv_anglo), intLabel, chans { intl(it) && (isAnglophone(it.country) || it.speaks("en")) }),
            leaf("tv/int/eu", t(R.string.tv_europe), intLabel, chans { intl(it) && isEuropean(it.country) }),
            node("tv/int/country", t(R.string.tv_by_country), intLabel, byCountry(intl)),
            node("tv/int/lang", t(R.string.tv_by_language), intLabel, byLanguage(intl)),
            leaf("tv/int/music", t(R.string.g_music), intLabel, chans { intl(it) && it.has(Genre.MUSIC) }),
            leaf("tv/int/kids", t(R.string.g_kids), intLabel, chans { intl(it) && it.has(Genre.KIDS) }),
            leaf("tv/int/culture", t(R.string.tv_culture), intLabel, chans { intl(it) && it.has(Genre.DOCS) }),
            leaf("tv/int/religion", t(R.string.g_religion), intLabel, chans { intl(it) && it.has(Genre.RELIGION) }),
            leaf("tv/int/general", t(R.string.g_general), intLabel, chans { intl(it) && it.has(Genre.GENERAL) }),
            leaf("tv/int/movies", t(R.string.tv_int_movies), intLabel, chans { intl(it) && (it.has(Genre.MOVIES) || it.has(Genre.SERIES)) }),
            leaf("tv/int/sport", t(R.string.g_sport), intLabel, chans { intl(it) && it.has(Genre.SPORT) }),
            leaf("tv/int/all", t(R.string.tv_full_int), intLabel, chans(intl)),
        ))
        // what no rule files: no country and no kind of programmes stated anywhere
        val others = leaf("tv/other", t(R.string.tv_other_channels), tvLabel, chans { (it.genres.isEmpty() || it.has(Genre.OTHER)) && it.country.isEmpty() })
        val kids = listOfNotNull(france, portugal, lusophone, fast, international, others)
        return Section("tv", tvLabel) { kids }
    }

    private fun byCountry(p: (Info) -> Boolean): List<Section?> {
        val ui = uiLocale()
        val label = t(R.string.tv_by_country)
        return lib.filter(p).groupBy { it.country }.entries
            .map { (cc, l) -> cc to l }
            .sortedWith(compareBy({ it.first.isEmpty() }, { Locale("", it.first).getDisplayCountry(ui) }))
            .map { (cc, l) ->
                val name = if (cc.isEmpty()) t(R.string.unknown_country) else Locale("", cc.uppercase(Locale.ROOT)).getDisplayCountry(ui)
                leaf("tv/int/c/$cc", name, label, l.map { it.ch })
            }
    }

    private fun byLanguage(p: (Info) -> Boolean): List<Section?> {
        val ui = uiLocale()
        val label = t(R.string.tv_by_language)
        val pool = lib.filter(p)
        return listOf("fr", "pt", "en", "es", "de", "it", "ar", "ru", "nl", "tr", "zh", "hi").map { lang ->
            leaf("tv/int/l/$lang", Locale(lang).getDisplayLanguage(ui).replaceFirstChar { it.titlecase(ui) }, label, pool.filter { it.speaks(lang) }.map { it.ch })
        } + leaf("tv/int/l/other", t(R.string.other_langs), label, pool.filter { it.langs.isEmpty() }.map { it.ch })
    }

    private fun streaming(): Section {
        val label = t(R.string.rail_streaming)
        val yt = t(R.string.cat_youtube)
        val youtubeChannels = chans { it.ch.isYouTube }
        val kids = listOfNotNull(
            leaf("st/sites", t(R.string.st_sites), label, APPS.filter { it.site != null }),
            Section("st/youtube", yt, label) {
                listOfNotNull(
                    Section("st/youtube/live", t(R.string.row_youtube_live), yt, slow = true) {
                        val freeTv = Playlists.SOURCES.first { it.name == "Free-TV" } // the only list with official YouTube live channels
                        Playlists.load(c, freeTv).filter { it.isYouTube && it.group in YOUTUBE_GROUPS }.distinctBy { it.url }
                    },
                    leaf("st/youtube/lists", t(R.string.yt_from_lists), yt, youtubeChannels),
                    live("st/youtube/apps", t(R.string.yt_apps), yt) { apps(Cat.YOUTUBE) },
                )
            },
            live("st/apps", t(R.string.st_apps), label) {
                val l = t(R.string.st_apps)
                listOf(
                    live("st/apps/movies", t(R.string.apps_movies), l) { apps(Cat.MOVIES, Cat.SERIES) },
                    live("st/apps/live", t(R.string.apps_live), l) { apps(Cat.LIVE) },
                    live("st/apps/sport", t(R.string.apps_sport), l) { apps(Cat.SPORT) },
                    live("st/apps/docs", t(R.string.apps_docs), l) { apps(Cat.DOCS) },
                    live("st/apps/youtube", yt, l) { apps(Cat.YOUTUBE) },
                    live("st/apps/iptv", t(R.string.apps_iptv), l) { apps(Cat.IPTV) },
                    live("st/apps/install", t(R.string.apps_install), l) { APPS.filter { it.visible(pm) && !it.ready(pm) } },
                )
            },
            Section("st/lists", t(R.string.st_public_lists), label) {
                Playlists.SOURCES.map { s -> Section("st/lists/${s.name}", s.name, t(R.string.st_public_lists), slow = true) { Playlists.folders(c, s) } }
            },
        )
        return Section("streaming", label) { kids }
    }

    /** Films, series, sport and documentaries: by language, finer cuts, apps and sites, everything. */
    private fun kind(key: String, rail: Int, g: Genre, appCats: Array<Cat>, cutsTitle: Int, cutList: List<Cut>, allTitle: Int, intTitle: Int, extra: List<Section?> = emptyList()): Section {
        val label = t(rail)
        val sites = live("$key/sites", t(R.string.sites_apps), label) { apps(*appCats) }
        val kids = listOfNotNull(
            if (g == Genre.MOVIES || g == Genre.SERIES) sites else null,
            leaf("$key/fr", t(R.string.lang_in_fr), label, chans { it.has(g) && it.speaks("fr") }),
            leaf("$key/pt", t(R.string.lang_in_pt), label, chans { it.has(g) && it.speaks("pt") }),
            leaf("$key/int", t(intTitle), label, chans { it.has(g) && !it.speaks("fr") && !it.speaks("pt") }),
            node("$key/cuts", t(cutsTitle), label, cuts("$key/cuts", label, cutList) { it.has(g) }),
        ) + extra.filterNotNull() + listOfNotNull(
            if (g == Genre.MOVIES || g == Genre.SERIES) null else sites,
            leaf("$key/all", t(allTitle), label, chans { it.has(g) }),
        )
        return Section(key, label) { kids }
    }

    private fun movies() = kind("movies", R.string.rail_movies, Genre.MOVIES, arrayOf(Cat.MOVIES), R.string.by_genre, MOVIE_CUTS, R.string.all_movie_channels, R.string.lang_other)
    private fun series() = kind("series", R.string.rail_series, Genre.SERIES, arrayOf(Cat.SERIES), R.string.by_genre, SERIES_CUTS, R.string.all_series_channels, R.string.lang_other)
    private fun sport() = kind("sport", R.string.rail_sport, Genre.SPORT, arrayOf(Cat.SPORT), R.string.by_discipline, SPORT_CUTS, R.string.all_sport_channels, R.string.sp_int,
        listOf(live("sport/guide", t(R.string.sp_guide), t(R.string.rail_sport), true) { sportProgrammes() }))
    private fun docs() = kind("docs", R.string.rail_docs, Genre.DOCS, arrayOf(Cat.DOCS), R.string.by_theme, DOC_CUTS, R.string.all_doc_channels, R.string.doc_int)

    private fun favorites(): Section {
        val label = t(R.string.rail_favorites)
        val kids = listOf(
            live("fav/all", t(R.string.fav_all), label) { Store.favorites() },
            live("fav/tv", t(R.string.fav_channels), label) { Store.favorites().filterIsInstance<Channel>() },
            live("fav/apps", t(R.string.fav_apps), label) { Store.favorites().filterIsInstance<App>() },
        )
        return Section("fav", label) { kids }
    }

    private fun resume(): Section {
        val label = t(R.string.rail_resume)
        val kids = listOf(
            live("resume/recent", t(R.string.resume_recent), label) { Store.history() },
            live("resume/tv", t(R.string.resume_channels), label) { Store.history().filterIsInstance<Channel>() },
            live("resume/apps", t(R.string.resume_apps), label) { Store.history().filterIsInstance<App>() },
        )
        return Section("resume", label) { kids }
    }

    // ---------- guide ----------

    /** Channels that have a guide, one per guide channel: TNT order, then the lists' order. */
    private fun guided(country: String?): List<Pair<Info, List<Guide.Prog>>> {
        val seen = HashSet<String>()
        return lib.asSequence()
            .filter { country == null || it.country == country || it.country.isEmpty() }
            .mapNotNull { i -> Guide.schedule(i.ch, i.country).takeIf { it.isNotEmpty() }?.let { i to it } }
            .filter { (i, _) -> seen.add(Guide.match(i.ch, i.country).orEmpty()) && (country == null || Guide.match(i.ch, country) != null) }
            .sortedWith(compareBy({ tntRank(it.first.ch.id).let { r -> if (r < 0) Int.MAX_VALUE else r } }, { it.first.n }))
            .toList()
    }

    private fun guide(): Section {
        val label = t(R.string.rail_guide)
        val kids = listOf(
            live("guide/now", t(R.string.guide_now), label, true) { guided(null).map { it.first.ch } },
            live("guide/fr", t(R.string.guide_fr), label, true) { guided("fr").map { it.first.ch } },
            live("guide/pt", t(R.string.guide_pt), label, true) { guided("pt").map { it.first.ch } },
            live("guide/tonight", t(R.string.guide_tonight), label, true) { tonight() },
            live("guide/sport", t(R.string.guide_sport), label, true) { sportProgrammes() },
        )
        return Section("guide", label) { kids }
    }

    /** Programmes starting between 20:30 and 22:30 today, by start time. */
    private fun tonight(): List<Item> {
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 20); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        val from = cal.timeInMillis
        val to = from + 2 * 3600_000L
        return guided(null).flatMap { (i, l) -> l.filter { it.start in from..to }.map { Programme(i.ch, it) } }.sortedBy { it.prog.start }
    }

    /** Sport on now or within 12 hours, by the guide's category or title. */
    fun sportProgrammes(): List<Item> {
        val now = System.currentTimeMillis()
        return guided(null).flatMap { (i, l) ->
            l.filter { it.stop > now && it.start < now + 12 * 3600_000L && (SPORT_PROG.containsMatchIn(it.category) || SPORT_PROG.containsMatchIn(it.title)) }
                .map { Programme(i.ch, it) }
        }.sortedBy { it.prog.start }
    }

    // ---------- every channel ----------

    private fun all(): Section {
        val label = t(R.string.rail_all)
        val lists = t(R.string.all_lists)
        // the Kodi interface's groups, in its order ("France · Favoris"...)
        val groups = lib.flatMap { i -> i.groups.filter { !it.startsWith("row:") } }.distinct().map { g ->
            val head = g.substringBefore(" · ", "")
            leaf("all/groups/$g", g.substringAfter(" · "), head.ifEmpty { t(R.string.all_groups) }, chans { g in it.groups })
        }
        val catalog = Catalog.rows(c).map { r -> leaf("all/catalog/${r.cat}/${r.row}", t(ROW_TITLES.getValue("${r.cat}/${r.row}")), t(R.string.all_catalog), r.items) }
        val kids = listOfNotNull(
            leaf("all/all", t(R.string.all_all), label, lib.map { it.ch }),
            leaf("all/fr", t(R.string.list_fr), lists, chans { it.list == "france" }),
            leaf("all/pt", t(R.string.list_pt), lists, chans { it.list == "portugal" }),
            leaf("all/monde", t(R.string.list_monde), lists, chans { it.list == "monde" }),
            node("all/groups", t(R.string.all_groups), label, groups),
            node("all/catalog", t(R.string.all_catalog), label, catalog),
        )
        return Section("all", label) { kids }
    }

    // ---------- settings ----------

    private fun settings(): Section {
        val label = t(R.string.rail_settings)
        fun mark(on: Boolean) = if (on) t(R.string.current) else ""
        val kids = listOf(
            live("set/lang", t(R.string.set_lang), label) {
                listOf("" to R.string.lang_auto, "fr" to R.string.lang_fr, "pt" to R.string.lang_pt)
                    .map { (code, name) -> Setting("lang:$code", t(name), mark(Store.lang == code)) }
            },
            live("set/quality", t(R.string.set_quality), label) {
                listOf(0 to R.string.q_auto, 1080 to R.string.q_1080, 720 to R.string.q_720, 480 to R.string.q_480)
                    .map { (h, name) -> Setting("q:$h", t(name), mark(Store.maxHeight == h)) }
            },
            live("set/data", t(R.string.set_data), label) {
                listOf(Setting("refresh", t(R.string.refresh), t(R.string.refresh_value)), Setting("guide", t(R.string.set_guide), t(R.string.refresh_value)),
                    Setting("clear", t(R.string.clear_history)))
            },
            live("set/help", t(R.string.set_help), label) {
                listOf(Setting("", t(R.string.help_fav)), Setting("", t(R.string.help_back)), Setting("", t(R.string.help_guide)),
                    Setting("", t(R.string.help_zap)), Setting("", t(R.string.help_preview)))
            },
            live("set/where", t(R.string.set_unavailable), label) {
                listOf(Setting("", t(R.string.un_sic), t(R.string.un_sic_v)), Setting("", t(R.string.un_rtp), t(R.string.un_rtp_v)),
                    Setting("", t(R.string.un_tvi), t(R.string.un_tvi_v)), Setting("", t(R.string.un_fr), t(R.string.un_fr_v)),
                    Setting("", t(R.string.un_paid), t(R.string.un_paid_v)))
            },
            live("set/about", t(R.string.set_about), label) {
                listOf(Setting("", t(R.string.version), pm.getPackageInfo(c.packageName, 0).versionName.orEmpty()),
                    Setting("", c.getString(R.string.about_lists, lib.size)),
                    Setting("", c.getString(R.string.catalog_info, Catalog.all(c).size), Catalog.checked),
                    Setting("", c.getString(R.string.about_guide, Guide.channels())))
            },
        )
        return Section("settings", label) { kids }
    }

    // ---------- search ----------

    /** One search over every channel, the guide, the apps and the public playlists already downloaded, and YouTube. */
    fun search(q: String): List<Section> {
        val n = fold(q.trim())
        val label = t(R.string.rail_search)
        val now = System.currentTimeMillis()
        val channels = lib.filter { fold(it.ch.title).contains(n) }.map { it.ch }.take(400)
        val progs = guided(null).flatMap { (i, l) -> l.filter { it.stop > now && fold(it.title).contains(n) }.map { Programme(i.ch, it) } }
            .sortedBy { it.prog.start }.take(200)
        val apps = APPS.filter { it.visible(pm) && fold(it.title).contains(n) }
        val known = channels.mapTo(HashSet()) { (it as Channel).url }
        val lists = Playlists.SOURCES.flatMap { Playlists.loaded(it) }.filter { it.url !in known && fold(it.title).contains(n) }.distinctBy { it.url }.take(300)
        val youtube = Channel(c.getString(R.string.search_youtube, q.trim()), "https://www.youtube.com/results?search_query=" + Uri.encode(q.trim()))
        return listOfNotNull(
            leaf("search/channels", t(R.string.search_channels), label, channels),
            leaf("search/progs", t(R.string.search_programmes), label, progs),
            leaf("search/apps", t(R.string.search_apps), label, apps),
            leaf("search/lists", t(R.string.search_lists), label, lists),
            leaf("search/youtube", t(R.string.cat_youtube), label, listOf(youtube)),
        )
    }

    private companion object {
        val YOUTUBE_GROUPS = setOf("France", "Portugal", "Brazil", "Belgium", "Switzerland", "Luxembourg", "Monaco", "News")
    }
}
