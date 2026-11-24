package com.local.streaming4free

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParseM3uTest {
    @Test
    fun keepsPlayableEntriesWithTheirAttributes() {
        val list = parseM3u(sequenceOf(
            "﻿#EXTM3U url-tvg=\"https://example.com/epg.xml\"",
            "#EXTINF:-1 tvg-id=\"RTP1.pt\" tvg-logo=\"https://example.com/rtp1.png\" group-title=\"TV\",RTP 1",
            "#EXTVLCOPT:http-referrer=https://www.rtp.pt/",
            "https://example.com/rtp1.m3u8",
            "",
            "#EXTINF:-1 group-title=\"News;Public\",Name, with a comma",
            "  http://example.com/news.m3u8  ",
            "#EXTINF:-1,Clear-key DRM channel",
            "#KODIPROP:inputstream.adaptive.license_type=org.w3.clearkey",
            "#KODIPROP:inputstream.adaptive.license_key=0123:4567",
            "https://example.com/drm.mpd",
            "#EXTINF:-1,RTMP channel",
            "rtmp://example.com/live",
            "#EXTINF:-1 tvg-name=\"From tvg-name\",",
            "https://example.com/named.m3u8",
            "https://example.com/orphan-url-without-extinf.m3u8",
        ), "Test")

        assertEquals(listOf("RTP 1", "Name, with a comma", "From tvg-name"), list.map { it.title })
        val rtp = list[0]
        assertEquals("https://example.com/rtp1.m3u8", rtp.url)
        assertEquals("https://example.com/rtp1.png", rtp.logo)
        assertEquals("TV", rtp.group)
        assertEquals("RTP1.pt", rtp.id)
        assertEquals("Test", rtp.source)
        assertEquals("http://example.com/news.m3u8", list[1].url)
        assertEquals("News;Public", list[1].group)
    }

    @Test
    fun flagsGeoBlockedAndHeaderEntries() {
        val list = parseM3u(sequenceOf(
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"France2.fr\",France 2 Ⓖ",
            "https://example.com/f2.m3u8",
            "#EXTINF:-1 tvg-id=\"France3.fr@HD\",France 3 (720p) [Geo-blocked]",
            "https://example.com/f3.m3u8",
            "#EXTINF:-1,RTP 1",
            "#EXTVLCOPT:http-user-agent=Mozilla/5.0",
            "https://example.com/rtp1.m3u8",
            "#EXTINF:-1 http-referrer=\"https://www.example.pt/\",RTP 2",
            "https://example.com/rtp2.m3u8",
            "#EXTINF:-1,Plain",
            "https://example.com/plain.m3u8",
        ), "Test")
        assertEquals(listOf("France 2", "France 3 (720p)", "RTP 1", "RTP 2", "Plain"), list.map { it.title })
        assertEquals(listOf(true, true, false, false, false), list.map { it.geo })
        assertEquals(listOf(false, false, true, true, false), list.map { it.headers })
    }

    @Test
    fun quotesInTheNameAndLicenceBeforeExtinf() {
        val list = parseM3u(sequenceOf(
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"mag.fr\" tvg-logo=\"https://example.com/l.png\",Le \"Mag\", édition HD",
            "https://example.com/mag.m3u8",
            "#KODIPROP:inputstream.adaptive.license_type=clearkey",
            "#KODIPROP:inputstream.adaptive.license_key=0123:4567",
            "#EXTINF:-1,Licence above",
            "https://example.com/drm.mpd",
            // M3UPT's RTP 1 since November 2026: a ClearKey given with drm_legacy, no "license" word
            "#EXTINF:-1 tvg-id=\"RTP1.pt\",RTP 1",
            "#EXTVLCOPT:http-origin=https://www.rtp.pt",
            "#KODIPROP:inputstream.adaptive.drm_legacy=org.w3.clearkey|0123:4567",
            "https://example.com/rtp1.mpd",
            "#EXTINF:-1,New syntax",
            "#KODIPROP:inputstream.adaptive.drm={\"com.widevine.alpha\":{}}",
            "https://example.com/wv.mpd",
            "#EXTINF:-1 tvg-id=\"next.fr\",Next",
            "https://example.com/next.m3u8",
            "#EXTINF:-1 tvg-name=\"No comma\"",
            "https://example.com/nocomma.m3u8",
        ), "Test")
        assertEquals(listOf("Le \"Mag\", édition HD", "Next", "No comma"), list.map { it.title })
        assertEquals("mag.fr", list[0].id)
        assertEquals("https://example.com/l.png", list[0].logo)
    }

    @Test
    fun recognisesYouTubePages() {
        assertTrue(Channel("a", "https://www.youtube.com/c/franceinfo/live").isYouTube)
        assertTrue(Channel("a", "https://youtu.be/abc").isYouTube)
        assertFalse(Channel("a", "https://example.com/youtube.com/x.m3u8").isYouTube)
    }

    @Test
    fun kodiYouTubeLinksBecomeYouTubePages() {
        val list = parseM3u(sequenceOf(
            "#EXTM3U",
            "#EXTINF:-1 tvg-id=\"FranceInfo.fr\",Franceinfo",
            "plugin://plugin.video.youtube/play/?video_id=NG7ZX42nZKc",
            "#EXTINF:-1,Other Kodi add-on",
            "plugin://plugin.video.other/play/?id=1",
            "#EXTINF:-1,Not an id",
            "plugin://plugin.video.youtube/play/?video_id=../../etc",
        ), "IPTV4FREE")
        assertEquals(listOf("Franceinfo"), list.map { it.title })
        assertEquals("https://www.youtube.com/watch?v=NG7ZX42nZKc", list[0].url)
        assertTrue(list[0].isYouTube)
        assertEquals(null, playable("rtmp://example.com/live"))
    }
}
