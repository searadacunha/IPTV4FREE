package com.local.streaming4free

import java.text.Normalizer
import java.util.Locale

/**
 * How IPTV4FREE files a channel: country, language, kind of programmes, free (FAST) platform, and the finer cuts
 * (TNT, regional, discipline, theme, genre). Pure Kotlin, unit-tested. The lists only state an id, a name and their
 * own groups, so the finer cuts read names: a channel no rule recognises stays in its country, language and full lists.
 */
enum class Genre { NEWS, GENERAL, SPORT, MOVIES, SERIES, KIDS, DOCS, MUSIC, RELIGION, OTHER }

private val MARKS = Regex("\\p{Mn}+")

/** Lowercase without accents, for keyword rules and search. */
fun fold(s: String): String = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(MARKS, "")

/** ISO country of an iptv-org style id "Name.cc@feed" ("uk" read as "gb"), or "". */
fun countryOf(tvgId: String): String {
    val cc = tvgId.substringBefore('@').trim().substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        cc.length != 2 || !cc.all { it in 'a'..'z' } -> ""
        cc == "uk" -> "gb"
        else -> cc
    }
}

private val FEED_LANG = mapOf(
    "french" to "fr", "fr" to "fr", "france" to "fr", "portuguese" to "pt", "pt" to "pt", "portugal" to "pt", "br" to "pt",
    "brazil" to "pt", "spanish" to "es", "es" to "es", "english" to "en", "us" to "en", "uk" to "en", "german" to "de",
    "de" to "de", "italian" to "it", "it" to "it", "arabic" to "ar",
)
private val FEED_COUNTRY = mapOf("br" to "br", "brazil" to "br", "fr" to "fr", "france" to "fr", "pt" to "pt", "portugal" to "pt")

private fun feed(tvgId: String) = tvgId.substringAfter('@', "").lowercase(Locale.ROOT)

/** Language a feed name states: "France24.fr@French", "PlutoTVToons.de@FR", "AngelTV.in@Portuguese". */
fun feedLanguage(tvgId: String) = FEED_LANG[feed(tvgId)].orEmpty()

/** The country a feed is made for ("PlutoTVRecordNews.us@BR" is Brazilian), else the id's country. */
fun channelCountry(tvgId: String) = FEED_COUNTRY[feed(tvgId)] ?: countryOf(tvgId)

private val LANGS: Map<String, List<String>> = HashMap<String, List<String>>().apply {
    fun put(lang: String, cc: String) = cc.split(' ').forEach { merge(it, listOf(lang)) { a, b -> a + b } }
    put("fr", "fr be ch lu mc ca sn ci cm cd cg ga bj bf ml ne tg gn mg ht mq gp re gf pf nc bi td cf dj km rw sc yt pm wf")
    put("pt", "pt br ao mz cv gw st tl mo")
    put("en", "us gb ie ca au nz za jm tt bs bb gy ng gh ke ug zw zm bz mt lr sl gm bw na")
    put("es", "es mx ar co cl pe ve ec gt cu bo do hn py sv ni cr pa uy pr gq")
    put("de", "de at ch li lu")
    put("it", "it sm va ch")
    put("ar", "sa ae eg iq jo kw lb ly ma om ps qa sy tn ye dz bh sd mr")
    put("ru", "ru by kz kg")
    put("nl", "nl be sr aw cw")
    put("tr", "tr")
    put("zh", "cn tw hk sg mo")
    put("hi", "in")
}

/** Languages spoken on a country's channels, by country (several for Belgium, Switzerland, Canada...). */
fun countryLanguages(country: String) = LANGS[country].orEmpty()

private val ANGLO = "us gb ie ca au nz za jm tt bs bb gy ng gh ke ug zw zm bz".split(' ').toSet()
private val EUROPE = ("al ad at by be ba bg hr cy cz dk ee fi de gr hu is ie it xk lv li lt lu mt md mc me nl mk no pl ro ru sm rs sk " +
    "si es se ch ua gb va fo gi").split(' ').toSet()
fun isAnglophone(country: String) = country in ANGLO
/** Europe outside France and Portugal, which have their own sections. */
fun isEuropean(country: String) = country in EUROPE

val LUSO_AFRICA = setOf("ao", "mz", "cv", "gw", "st")

/** Free ad-supported platforms, read from the stream or logo address and the name. [OTHER_FAST]: smaller ones. */
const val OTHER_FAST = "*"

fun fastProvider(title: String, url: String, logo: String = "", id: String = ""): String? {
    val t = fold(title)
    val u = url.lowercase(Locale.ROOT)
    val l = logo.lowercase(Locale.ROOT)
    val i = id.lowercase(Locale.ROOT)
    return when {
        "jmp2.uk/plu-" in u || "pluto.tv" in u || "pluto tv" in t || "plutotv" in i || "pluto.tv" in l -> "Pluto TV"
        "jmp2.uk/stvp-" in u || "samsung" in u || "samsung" in t || "samsungcloud" in l -> "Samsung TV Plus"
        "rakuten" in u || "rakuten" in t -> "Rakuten TV"
        "plex.tv" in u || PLEX.containsMatchIn(t) -> "Plex"
        "xumo" in u -> "Xumo"
        "roku" in u -> "The Roku Channel"
        "tubi" in u -> "Tubi"
        "stirr" in u -> "STIRR"
        "distro" in u -> "DistroTV"
        "lgchannels" in u -> "LG Channels"
        "amagi.tv" in u || "wurl" in u -> OTHER_FAST
        else -> null
    }
}

private val PLEX = Regex("\\bplex\\b")

/** Kinds of programmes a list group states: Kodi lists ("Monde · Info"), hub rubrics ("Films · ..."), catalog rows ("row:movies/fra"). */
fun groupGenres(group: String): Set<Genre> {
    if (group.startsWith("row:")) {
        val (cat, row) = group.removePrefix("row:").split('/').let { it[0] to it.getOrElse(1) { "" } }
        return setOf(when (cat) {
            "movies" -> Genre.MOVIES
            "series" -> Genre.SERIES
            "docs" -> Genre.DOCS
            "sport" -> Genre.SPORT
            else -> when (row) { "kids" -> Genre.KIDS; "music" -> Genre.MUSIC; "news" -> Genre.NEWS; "other" -> Genre.RELIGION; else -> Genre.GENERAL }
        })
    }
    val parts = group.split(" · ")
    if (parts.size < 2) return emptySet()
    val (head, tail) = fold(parts[0]) to fold(parts[1])
    return when (head) {
        "films" -> setOf(Genre.MOVIES)
        "series" -> setOf(Genre.SERIES)
        "documentaires" -> setOf(Genre.DOCS)
        "sport" -> setOf(Genre.SPORT)
        "tv en direct" -> setOf(when {
            "enfants" in tail -> Genre.KIDS
            "musique" in tail -> Genre.MUSIC
            "infos" in tail -> Genre.NEWS
            "religion" in tail -> Genre.RELIGION
            else -> Genre.GENERAL
        })
        else -> when (tail) { // "Monde · Info" ... of the Kodi lists
            "info" -> setOf(Genre.NEWS)
            "generaliste" -> setOf(Genre.GENERAL)
            "sport" -> setOf(Genre.SPORT)
            "films et series" -> setOf(Genre.MOVIES, Genre.SERIES)
            "enfants" -> setOf(Genre.KIDS)
            "decouverte" -> setOf(Genre.DOCS)
            "musique" -> setOf(Genre.MUSIC)
            "religion" -> setOf(Genre.RELIGION)
            "autres" -> setOf(Genre.OTHER)
            else -> emptySet()
        }
    }
}

/** Language a list group states ("... en français", "Chaînes portugaises", catalog "row:docs/por"), or "". */
fun groupLanguage(group: String): String {
    if (group.startsWith("row:")) return when (group.substringAfter('/')) { "fra" -> "fr"; "por", "pt" -> "pt"; else -> "" }
    val g = fold(group)
    return when {
        "en francais" in g -> "fr"
        "portugais" in g || "portugaises" in g -> "pt"
        else -> ""
    }
}

/** Keywords match from the start of a word ("cine" finds "cinema"); one ending in \b must be the whole word. */
private fun words(vararg w: String) = Regex("\\b(" + w.joinToString("|") + ")")

private val NAME_GENRES = listOf(
    Genre.NEWS to words("info", "infos", "news", "noticias", "bfm", "cnews", "lci", "franceinfo", "euronews", "africanews", "cgtn", "press tv",
        "le figaro", "20 ?minutes", "europe 1", "bloomberg", "cnbc", "sky news", "al jazeera", "nhk world", "cnn", "24h", "lcp"),
    Genre.KIDS to words("kids", "kidz", "junior", "toons", "cartoon", "enfant", "jeunesse", "gulli", "baby", "bob l.eponge", "schtroumpf",
        "lapins cretins", "masha", "pocoyo", "caillou", "teletubbies", "maya l", "pingu", "charlotte aux fraises", "barbie", "mattel",
        "inazuma", "olive & tom", "beyblade", "yu-gi-oh", "digimon", "doraemon", "one piece", "anime", "turma da monica", "reino infantil",
        "cocorico", "moranguinho", "oggy", "nickelodeon", "disney", "boomerang", "tortues ninja", "avatar"),
    Genre.MUSIC to words("trace", "mcm", "mtv", "music", "musique", "musica", "jazz", "qwest", "stingray", "mezzo", "vevo", "radio", "fm\\b"),
    Genre.SPORT to words("sport", "sports", "equipe", "dazn", "fifa", "equidia", "kozoom", "ringside", "futebol", "football", "golf", "tennis",
        "nba", "ufc", "fight", "boxing", "boxe", "racing", "motor", "esportes", "deportes", "eurosport", "bein\\b", "espn", "red bull", "fuel tv"),
    Genre.MOVIES to words("cine", "cinema", "film", "films", "filmes", "movie", "movies", "nanar", "kino", "hollywood", "western"),
    Genre.SERIES to words("series", "serie", "novelas?", "sitcom", "telenovelas?", "detective", "dossiers fbi", "walker", "malibu",
        "helene et les garcons", "annees fac", "louis la brocante", "miracle de l.amour", "filles d.a cote", "blacklist", "teen mom",
        "ridiculousness", "deteste chris", "nouveaux detectives", "verite qui compte", "saga"),
    Genre.DOCS to words("decouverte", "documentaire", "docu", "histoire", "history", "historia", "animaux", "animal", "nature", "wild", "voyage",
        "travel", "saveurs", "science", "investigation", "crime", "cuisine", "food", "chef", "planete", "geographic", "discovery", "curiosity"),
    Genre.RELIGION to words("gospel", "church", "igreja", "eglise", "iglesia", "tserkov", "cirkev", "kto", "mana\\b", "kuriakos", "cancao nova",
        "angel tv", "god\\b", "bible", "catholic", "catolica", "evangel", "islam", "quran", "ewtn", "daystar", "shalom", "aparecida", "rede vida"),
)

/** Kinds of programmes guessed from the name, for channels whose lists state none. */
fun nameGenres(title: String): Set<Genre> {
    val t = fold(title)
    return NAME_GENRES.filter { (_, re) -> re.containsMatchIn(t) }.map { it.first }.toSet()
}

/** France's TNT, in channel-number order (ids of the TNT guide and of iptv-org). */
val TNT = listOf("tf1.fr", "france2.fr", "france3.fr", "canalplus.fr", "france5.fr", "m6.fr", "arte.fr", "w9.fr", "tmc.fr", "nt1.fr",
    "tfx.fr", "nrj12.fr", "lachaineparlementaire.fr", "lcp.fr", "publicsenat.fr", "france4.fr", "bfmtv.fr", "cnews.fr", "cstar.fr",
    "gulli.fr", "t18.fr", "novo19.fr", "tf1seriesfilms.fr", "lequipe21.fr", "lequipe.fr", "6ter.fr", "numero23.fr", "rmcstory.fr",
    "rmcdecouverte.fr", "cherie25.fr", "rmclife.fr", "lci.fr", "franceinfo.fr")

/** Position on the TNT, or -1. */
fun tntRank(tvgId: String) = TNT.indexOf(channelKey(tvgId))

private val FR_REGIONAL = words("france 3 \\w", "bfm (alsace|cote|dici|grand|lyon|marseille|normandie|var|paris|nice|toulon)", "tv7 ",
    "tv alsace", "mulhouse", "tebeo", "tebesud", "vosges", "alpe d.huez", "brionnais", "cannes", "viamatele", "viatelepaese", "via occitanie",
    "antenne reunion", "reunion", "tv3v", "ile-de-france", "tl7", "mont-blanc", "grenoble", "vendee", "canal 32", "bip tv", "alsace 20",
    "martinique", "guadeloupe", "guyane", "corse", "bretagne", "normande", "occitanie", "provence", "savoie", "bordeaux", "toulouse",
    "nantes", "lille", "tahiti", "polynesie", "caledonie", "mayotte")

/** A French local or regional channel. */
fun isFrenchRegional(title: String) = FR_REGIONAL.containsMatchIn(fold(title))

private val PT_NATIONAL = words("rtp", "sic", "tvi", "v\\+ ?tvi", "cnn portugal", "cmtv", "porto canal", "artv", "canal 11", "euronews port")
private val PT_NEWS = words("noticias", "cnn portugal", "cmtv", "euronews port", "rtp 3", "rtp3", "news")
private val PT_REGIONAL = words("acores", "madeira", "algarve", "porto canal", "nmt", "tds", "sines", "assim e portugal", "conectv", "regional")

fun isPortugueseNational(title: String) = PT_NATIONAL.containsMatchIn(fold(title))
fun isPortugueseNews(title: String) = PT_NEWS.containsMatchIn(fold(title))
fun isPortugueseRegional(title: String) = PT_REGIONAL.containsMatchIn(fold(title))

/** A finer cut of a section: its key, for the title, and the names it matches. */
class Cut(val key: String, val re: Regex)

private fun cut(key: String, vararg w: String) = Cut(key, words(*w))

val SPORT_CUTS = listOf(
    cut("football", "football", "futebol", "futbol", "soccer", "foot\\b", "goal", "liga", "premier league", "bundesliga", "serie a", "calcio",
        "fifa", "uefa", "benfica", "sporting", "fc porto", "porto tv", "fc\\b", "real madrid", "barca", "psg", "golazo", "copa"),
    cut("combat", "box(e|ing)?\\b", "fight", "combat", "mma", "ufc", "kickbox", "glory", "ringside", "wrestling", "lutte", "catch",
        "wwe", "judo", "karate", "bellator", "pfl"),
    cut("basket", "basket", "nba", "euroleague", "wnba"),
    cut("motor", "moto", "motor", "racing", "rally", "f1", "formula", "nascar", "turbo", "speed", "motogp", "drift", "auto"),
    cut("tennis", "tennis", "atp", "wta", "roland"),
    cut("golf", "golf"),
    cut("cycling", "cycl", "bike", "velo\\b", "ciclismo", "tour de"),
    cut("rugby", "rugby"),
    cut("horse", "equidia", "horse", "cheval", "equestr", "hipic", "turf", "trot"),
    cut("extreme", "extreme", "surf", "ski\\b", "snow", "outdoor", "adventure", "red bull", "fuel tv", "skate", "climb"),
)

val DOC_CUTS = listOf(
    cut("history", "histoire", "history", "historia", "guerre", "war\\b", "military", "militar", "ancient"),
    cut("science", "science", "ciencia", "space", "espace", "nasa", "cosmos", "curiosity", "tech", "innovation"),
    cut("nature", "nature", "natur", "animal", "animaux", "wild", "wildlife", "planet", "ocean", "earth", "pets", "zoo"),
    cut("travel", "voyage", "travel", "viagem", "viaje", "saveurs", "cuisine", "food", "cook", "chef", "gourmet", "culinar"),
    cut("economy", "business", "econom", "bloomberg", "finance", "money", "market", "bourse", "invest", "cnbc"),
    cut("society", "crime", "investigation", "enquete", "detective", "justice", "police", "societe", "society", "mystery", "paranormal"),
)

val MOVIE_CUTS = listOf(
    cut("action", "action", "aventure", "adventure", "martial", "kung fu"),
    cut("comedy", "comedie", "comedy", "comedia", "funny", "rire", "humour", "humor", "laugh"),
    cut("horror", "horreur", "horror", "terror", "thriller", "666", "scream", "fear", "frissons", "zombie", "suspense"),
    cut("crime", "polar", "crime", "criminel", "policier", "detective", "gangster", "mafia", "investigation", "fbi"),
    cut("scifi", "sci-fi", "scifi", "sci fi", "science.fiction", "fantas", "catastrophe", "disaster"),
    cut("romance", "romance", "romantic", "love", "amour", "romant"),
    cut("western", "western"),
    cut("classics", "retro", "classic", "classique", "nanar", "noir", "90.s", "80.s", "70.s", "vintage", "golden", "cult\\b"),
    cut("family", "famil", "kids", "anime", "animation", "cartoon", "toons", "disney"),
    cut("asia", "asie", "asia", "bollywood", "korea", "japan", "chin", "hindi", "indian", "tamil", "telugu"),
)

val SERIES_CUTS = listOf(
    cut("crime", "polar", "crime", "criminel", "policier", "detective", "brigade", "fbi", "enquete", "investigation", "blacklist", "csi", "walker"),
    cut("comedy", "comedie", "comedy", "comedia", "sitcom", "funny", "rire", "humour", "deteste chris"),
    cut("scifi", "fantas", "sci-fi", "scifi", "science.fiction", "supernatural", "mystery", "star trek"),
    cut("drama", "novela", "drama", "soap", "telenovela", "saga", "feuilleton", "miracle de l.amour", "helene"),
    cut("anime", "anime", "animation", "cartoon", "manga", "toons"),
    cut("reality", "reality", "realite", "dating", "teen mom", "ridiculousness", "verite qui compte"),
    cut("classics", "retro", "classic", "classique", "90.s", "80.s", "culte", "cult\\b", "vintage", "malibu", "annees fac", "filles d.a cote"),
)

/** The cuts a name falls in; none = the section's "other" row. */
fun cutsOf(title: String, cuts: List<Cut>): List<String> {
    val t = fold(title)
    return cuts.filter { it.re.containsMatchIn(t) }.map { it.key }
}
