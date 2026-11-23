package com.local.streaming4free

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuRulesTest {
    @Test
    fun countryAndLanguageComeFromTheIdAndItsFeed() {
        assertEquals("fr", countryOf("TF1.fr"))
        assertEquals("gb", countryOf("BBCNews.uk@SD"))
        assertEquals("", countryOf("NoCountry"))
        assertEquals("", countryOf("Weird.abc"))
        assertEquals("br", channelCountry("PlutoTVRecordNews.us@BR")) // a US id, made for Brazil
        assertEquals("fr", channelCountry("PlutoTVToons.de@FR")) // the feed made for France of a German id
        assertEquals("gb", channelCountry("FIFAPlus.uk@French")) // a language feed keeps the id's country
        assertEquals("fr", feedLanguage("PlutoTVToons.de@FR"))
        assertEquals("pt", feedLanguage("AngelTV.in@Portuguese"))
        assertEquals("", feedLanguage("TF1.fr@SD")) // SD is a quality, not Sudan
        assertTrue("fr" in countryLanguages("be") && "nl" in countryLanguages("be"))
        assertEquals(listOf("pt"), countryLanguages("ao"))
        assertTrue(isAnglophone("us") && !isAnglophone("fr"))
        assertTrue(isEuropean("de") && !isEuropean("fr") && !isEuropean("br"))
    }

    @Test
    fun freePlatformsAreReadFromTheAddressesAndNames() {
        assertEquals("Pluto TV", fastProvider("Cine Action by Pluto TV", "https://jmp2.uk/plu-5f.m3u8"))
        assertEquals("Pluto TV", fastProvider("Pluto TV Record News", "https://d2.cloudfront.net/WNNationalSamsung/x.m3u8"))
        assertEquals("Samsung TV Plus", fastProvider("Les Schtroumpfs", "https://jmp2.uk/stvp-FR123"))
        assertEquals("Rakuten TV", fastProvider("Comedy Movies", "https://rakuten.example.com/live.m3u8"))
        assertEquals(OTHER_FAST, fastProvider("Some FAST", "https://xyz.amagi.tv/playlist.m3u8"))
        assertNull(fastProvider("TF1", "https://r.eu.cdn-redge.media/live/tf1.m3u8"))
        assertNull(fastProvider("Complex TV", "https://example.com/complex.m3u8")) // "plex" inside a word is not Plex
    }

    @Test
    fun listGroupsGiveTheKindOfProgrammes() {
        assertEquals(setOf(Genre.NEWS), groupGenres("Monde · Info"))
        assertEquals(setOf(Genre.MOVIES, Genre.SERIES), groupGenres("Monde · Films et séries"))
        assertEquals(setOf(Genre.MOVIES), groupGenres("Films · Chaînes de films en français"))
        assertEquals(setOf(Genre.KIDS), groupGenres("TV en direct · Enfants"))
        assertEquals(setOf(Genre.GENERAL), groupGenres("TV en direct · Chaînes portugaises"))
        assertEquals(setOf(Genre.DOCS), groupGenres("row:docs/int"))
        assertEquals(setOf(Genre.RELIGION), groupGenres("row:live/other"))
        assertEquals(emptySet<Genre>(), groupGenres("France · Favoris"))
        assertEquals("fr", groupLanguage("Séries · Chaînes de séries en français"))
        assertEquals("pt", groupLanguage("TV en direct · En portugais (Brésil, Afrique, Macao)"))
        assertEquals("pt", groupLanguage("row:movies/por"))
        assertEquals("", groupLanguage("Sport · Sport, autres langues"))
    }

    @Test
    fun namesGiveTheKindWhenNoListSaysIt() {
        assertTrue(Genre.NEWS in nameGenres("BFM TV"))
        assertTrue(Genre.KIDS in nameGenres("Bob l'eponge"))
        assertTrue(Genre.MUSIC in nameGenres("Trace Urban"))
        assertTrue(Genre.RELIGION in nameGenres("Mana Igreja Online"))
        assertFalse(Genre.RELIGION in nameGenres("Manager TV")) // "mana" is a whole word
        assertFalse(Genre.RELIGION in nameGenres("Godzilla Channel"))
        assertTrue(nameGenres("TF1").isEmpty())
    }

    @Test
    fun finerCutsForFranceAndPortugal() {
        assertEquals(0, tntRank("TF1.fr"))
        assertTrue(tntRank("France2.fr@HD") in 1..5)
        assertEquals(-1, tntRank("BFMLyon.fr@SD"))
        assertTrue(isFrenchRegional("France 3 Cote d'Azur"))
        assertTrue(isFrenchRegional("BFM Lyon"))
        assertFalse(isFrenchRegional("France 3"))
        assertFalse(isFrenchRegional("BFM Business"))
        assertTrue(isPortugueseNational("RTP Notícias") && isPortugueseNews("RTP Notícias"))
        assertTrue(isPortugueseNational("V+ TVI"))
        assertTrue(isPortugueseRegional("RTP Açores"))
        assertFalse(isPortugueseNational("Kuriakos TV"))
    }

    @Test
    fun disciplinesThemesAndGenresFromNames() {
        assertEquals(listOf("combat"), cutsOf("DAZN Ringside", SPORT_CUTS))
        assertEquals(listOf("football"), cutsOf("Benfica TV", SPORT_CUTS))
        assertEquals(listOf("horse"), cutsOf("Equidia", SPORT_CUTS))
        assertEquals(emptyList<String>(), cutsOf("Xbox Live", SPORT_CUTS)) // "box" is a whole word
        assertEquals(listOf("history"), cutsOf("Pluto TV Histoire", DOC_CUTS))
        assertEquals(emptyList<String>(), cutsOf("Warner TV", DOC_CUTS))
        assertTrue("horror" in cutsOf("Cine Horreur by Pluto TV", MOVIE_CUTS))
        assertTrue("western" in cutsOf("Cine Western (France)", MOVIE_CUTS))
        assertFalse("classics" in cutsOf("Culture Club", MOVIE_CUTS)) // "cult" is a whole word
        assertTrue("crime" in cutsOf("Pluto TV Brigade Criminelle", SERIES_CUTS))
    }

    @Test
    fun infoFilesAChannelWithoutLosingIt() {
        val tf1 = Info(Channel("TF1", "https://a/tf1.m3u8", id = "TF1.fr"), 1, "france", setOf("France · Favoris", "France · Toutes"), "")
        assertEquals("fr", tf1.country)
        assertEquals(setOf("fr"), tf1.langs)
        assertTrue(tf1.genres.isEmpty()) // no group nor name rule: TF1 stays in TNT, favorites and the full lists
        val noId = Info(Channel("Pluto TV Junior BR", "https://jmp2.uk/plu-1.m3u8"), 2, "portugal", setOf("Portugal · Favoris"), "")
        assertEquals("pt", noId.country)
        assertEquals("Pluto TV", noId.fast)
        assertTrue(Genre.KIDS in noId.genres)
        val world = Info(Channel("Canal Exemplo", "https://a/x.m3u8", id = "CanalExemplo.ao@SD"), 3, "monde", setOf("Monde · Généraliste"), "")
        assertEquals("ao", world.country)
        assertTrue(world.speaks("pt") && world.has(Genre.GENERAL))
        val catalog = Info(Channel("TVI", "https://a/tvi.m3u8", id = ""), 4, "catalog", setOf("row:live/pt"), "pt")
        assertEquals("pt", catalog.country)
        assertTrue(catalog.speaks("pt"))
    }
}
