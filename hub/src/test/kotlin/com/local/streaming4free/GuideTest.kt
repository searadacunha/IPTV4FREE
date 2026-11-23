package com.local.streaming4free

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import java.io.StringWriter

class GuideTest {
    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE tv SYSTEM "xmltv.dtd">
        <tv generator-info-name="test">
          <channel id="TF1.fr"><display-name>TF1</display-name><icon src="x.png"/></channel>
          <channel id="NT1.fr"><display-name>TFX</display-name></channel>
          <channel id="TVI.HD.pt"><display-name>TVI HD</display-name></channel>
          <channel id="Canal.A.pt"><display-name>Canal 2</display-name></channel>
          <channel id="Canal.B.pt"><display-name>Canal 2</display-name></channel>
          <programme start="20261123190000 +0100" stop="20261123200000 +0100" channel="TF1.fr">
            <title lang="fr">Journal &amp; météo</title><sub-title>Édition du soir</sub-title><desc lang="fr">Les infos.</desc>
            <category lang="fr">Information</category><credits><director>X</director></credits>
          </programme>
          <programme start="20261123200000 +0100" stop="20261123210000 +0100" channel="TF1.fr"><title>Film</title></programme>
          <programme start="20261101000000 +0000" stop="20261101010000 +0000" channel="TF1.fr"><title>Too old</title></programme>
          <programme start="20261123180000 +0000" stop="20261123190000 +0000" channel="TVI.HD.pt"><title>Telejornal</title><category>Notícias</category></programme>
        </tv>"""

    private fun parsed(): Guide.Parsed {
        val from = xmltvTime("20261123000000 +0000")
        val to = xmltvTime("20261125000000 +0000")
        return Guide.parse(KXmlParser(), xml.byteInputStream(), from, to)
    }

    @Test
    fun readsXmltvTimes() {
        assertEquals(1795453200000L, xmltvTime("20261123170000 +0000"))
        assertEquals(xmltvTime("20261123170000 +0000"), xmltvTime("20261123180000 +0100"))
        assertEquals(xmltvTime("20261123170000 +0000"), xmltvTime("20261123170000")) // no offset: UTC
        assertEquals(xmltvTime("20261123220000 +0000"), xmltvTime("20261123170000 -0500"))
        assertEquals(0L, xmltvTime("garbage"))
        assertEquals(0L, xmltvTime(""))
    }

    @Test
    fun keepsTheWindowAndTheFirstTitleDescAndCategory() {
        val p = parsed()
        val tf1 = p.progs.getValue("TF1.fr")
        assertEquals(listOf("Journal & météo", "Film"), tf1.map { it.title }) // the 1 November one is out of the window
        assertEquals("Édition du soir — Les infos.", tf1[0].desc)
        assertEquals("Information", tf1[0].category)
        assertEquals(3600_000L, tf1[0].stop - tf1[0].start)
        val mid = tf1[0].start + 1800_000L
        assertTrue(tf1[0].on(mid))
        assertEquals(50, tf1[0].progress(mid))
    }

    @Test
    fun matchesByIdAliasDisplayNameThenUniqueLooseName() {
        val guides = mapOf("fr" to parsed(), "pt" to parsed())
        assertEquals("TF1.fr", Guide.match(Channel("TF1", "u1", id = "TF1.fr@SD"), "fr", guides))
        assertEquals("NT1.fr", Guide.match(Channel("TFX", "u2", id = "TFX.fr"), "fr", guides)) // the TNT guide's old id
        assertEquals("TF1.fr", Guide.match(Channel("tf1", "u3"), "", guides)) // display name, case ignored
        assertEquals("TVI.HD.pt", Guide.match(Channel("TVI", "u4", id = "TVI.pt"), "pt", guides)) // loose name: "TVI HD" = "TVI"
        assertNull(Guide.match(Channel("Canal 2", "u5"), "pt", guides)) // two guide channels share it: none
        assertNull(Guide.match(Channel("TVI", "u6", id = "TVI.br"), "br", guides)) // another country's TVI
    }

    @Test
    fun theCacheReadsBackTheSameGuide() {
        val p = parsed()
        val w = StringWriter()
        Guide.writeCache(p, w)
        val back = Guide.readCache(w.toString().lineSequence())
        assertEquals(p.progs.mapValues { (_, l) -> l.map { it.title } }, back.progs.mapValues { (_, l) -> l.map { it.title } })
        assertEquals("TVI.HD.pt", back.index.names["tvi"])
        assertNull(back.index.display["canal 2"])
        assertEquals(p.index.ids, back.index.ids)
    }

    @Test
    fun matchKeyDropsAccentsCaseAndQuality() {
        assertEquals("rtpnoticias", matchKey("RTP Notícias HD"))
        assertEquals("canal+", matchKey("Canal+ (720p)"))
    }
}
