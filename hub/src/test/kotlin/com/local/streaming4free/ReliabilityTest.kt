package com.local.streaming4free

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifyTest {
    @Test fun httpStatuses() {
        assertEquals(Kind.FORBIDDEN, classify(2004, 403))
        assertEquals(Kind.GEO, classify(2004, 403, mapOf("X-ErrorType" to listOf("GeoBlocked"))))
        assertEquals(Kind.GEO, classify(2004, 403, body = "<h1>Not available in your country</h1>".toByteArray()))
        assertEquals(Kind.GEO, classify(2004, 403, listGeo = true))
        assertEquals(Kind.GEO, classify(2004, 451))
        assertEquals(Kind.NOT_FOUND, classify(2004, 404))
        assertEquals(Kind.NOT_FOUND, classify(2004, 410))
        assertEquals(Kind.SERVER, classify(2004, 503))
        assertEquals(Kind.SERVER, classify(2004, 429))
    }

    @Test fun htmlWithStatus200IsNotMedia() {
        assertEquals(Kind.NOT_MEDIA, classify(2003)) // text/html content type
        assertEquals(Kind.NOT_MEDIA, classify(3002)) // an HTML page parsed as a playlist
    }

    @Test fun networkErrorsDependOnTheTvBeingOnline() {
        assertEquals(Kind.NETWORK, classify(2001, offline = false))
        assertEquals(Kind.LOCAL_NETWORK, classify(2001, offline = true))
        assertEquals(Kind.TIMEOUT, classify(2002))
        assertEquals(Kind.LOCAL_NETWORK, classify(1003, offline = true))
    }

    @Test fun decoderDrmLiveWindowAndStuck() {
        assertEquals(Kind.CODEC, classify(4003))
        assertEquals(Kind.CODEC, classify(5001))
        assertEquals(Kind.DRM, classify(6004))
        assertEquals(Kind.BEHIND_LIVE, classify(1002))
        assertEquals(Kind.TIMEOUT, classify(1004, stuck = true))
        assertEquals(Kind.OTHER, classify(1004))
    }

    @Test fun stuckOrNoPictureWhileOfflineIsTheTvsNetwork() {
        assertEquals(Kind.LOCAL_NETWORK, classify(1004, stuck = true, offline = true))
        assertEquals(Kind.LOCAL_NETWORK, classify(1003, stuck = true, offline = true))
        assertEquals(Kind.LOCAL_NETWORK, classify(2003, offline = true)) // captive portal page
        assertEquals(Kind.LOCAL_NETWORK, classify(2004, 403, offline = true))
        assertEquals(Kind.BEHIND_LIVE, classify(1002, offline = true))
    }

    @Test fun anHttpAnswerOtherThanAPortalIsTheServersEvenWhenAndroidSaysOffline() {
        assertEquals(Kind.NOT_FOUND, classify(2004, 404, offline = true))
        assertEquals(Kind.SERVER, classify(2004, 503, offline = true))
        assertEquals(Kind.LOCAL_NETWORK, classify(2004, 511, offline = true))
    }

    @Test fun geoOnlyWhenTheBodySaysSo() {
        assertEquals(Kind.FORBIDDEN, classify(2004, 403, body = """{"error":"token expired","region":"eu-west-1"}""".toByteArray()))
        assertEquals(Kind.FORBIDDEN, classify(2004, 403, body = "<div role=\"region\">Access denied</div>".toByteArray()))
        assertEquals(Kind.GEO, classify(2004, 403, body = "Sorry, this content is geo-blocked".toByteArray()))
        assertEquals(Kind.GEO, classify(2004, 403, body = "You are outside the country".toByteArray()))
    }

    @Test fun afterPlayingBadDataOrALostDecoderIsRetried() {
        assertEquals(Kind.NETWORK, classify(3001, played = true))
        assertEquals(Kind.NETWORK, classify(4003, played = true))
        assertEquals(Kind.NETWORK, classify(4006))
        assertTrue(classify(3001, played = true).transient)
        val r = Recovery(2)
        assertTrue(r.fail(classify(3001, played = true), live = true, now = 0) is Step.Retry)
    }
}

class FrenchProviderHandoffTest {
    @Test fun molotovIsAnOfficialApplicationAndNeverAnExtractedStream() {
        val molotov = APPS.single { it.id == "molotov" }
        assertEquals(listOf("tv.molotov.app"), molotov.packages)
        assertEquals(Terms.FREEMIUM, molotov.terms)
        assertTrue(Cat.LIVE in molotov.cats)
        assertFalse(molotov.packages.any { it.startsWith("http") })
    }

    @Test fun providerOnlyForRecognizedFrenchChannels() {
        assertEquals("TF1+", rightsService("TF1.fr", "TF1"))
        assertEquals("M6+", rightsService("M6.fr", "M6"))
        assertNull(rightsService("RTP1.pt", "RTP1"))
    }
}

class RecoveryTest {
    @Test fun deadLinkSwitchesToTheNextSource() {
        val r = Recovery(3)
        assertEquals(Step.Next(1), r.fail(Kind.NOT_FOUND, false, 0))
        assertEquals(1, r.source)
    }

    @Test fun geoSwitchesAtOnce() {
        assertEquals(Step.Next(1), Recovery(2).fail(Kind.GEO, true, 0))
    }

    @Test fun interruptedLiveStreamRetriesWithBackoffAtTheLiveEdgeThenLowersQuality() {
        val r = Recovery(2)
        r.ready(0)
        assertEquals(Step.Retry(1000, 0, true), r.fail(Kind.NETWORK, true, 120_000))
        assertEquals(Step.Retry(2000, 0, true), r.fail(Kind.NETWORK, true, 121_000))
        assertEquals(Step.Retry(4000, 1, true), r.fail(Kind.TIMEOUT, true, 123_000)) // 720p
        r.ready(127_000)
        assertEquals(0, r.source)
        // a minute later the stream has proved itself: a new outage gets its full retries again
        assertEquals(Step.Retry(1000, 1, true), r.fail(Kind.NETWORK, true, 200_000))
    }

    @Test fun everySourceFailingEndsWithGiveUpAndNoLoop() {
        val r = Recovery(2)
        val steps = (0 until 20).map { r.fail(Kind.TIMEOUT, true, it * 100_000L) }
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), steps.take(4).map { (it as Step.Retry).delayMs })
        assertEquals(listOf(0, 0, 1, 2), steps.take(4).map { (it as Step.Retry).quality })
        assertEquals(Step.Next(1), steps[4])
        assertEquals(Step.Retry(1000, 0, true), steps[5]) // the new source starts at full quality
        assertEquals(Step.GiveUp, steps[9])
        assertTrue(steps.drop(9).all { it == Step.GiveUp })
    }

    @Test fun streamThatDropsEverySecondsDoesNotRetryForever() {
        val r = Recovery(1)
        var t = 0L
        val steps = (0 until 6).map { r.ready(t); t += 5_000; r.fail(Kind.NETWORK, true, t) }
        assertEquals(Step.GiveUp, steps[4])
    }

    @Test fun deadHostBeforeTheFirstPictureGetsOneRetryWhenAnotherSourceIsLeft() {
        val r = Recovery(2)
        assertEquals(Step.Retry(1000, 0, false), r.fail(Kind.NETWORK, false, 0, played = false))
        assertEquals(1, r.limit)
        assertEquals(Step.Next(1), r.fail(Kind.TIMEOUT, false, 21_000, played = false))
        // the last source keeps its full retries
        val steps = (0 until 5).map { r.fail(Kind.TIMEOUT, false, 50_000L + it * 30_000, played = false) }
        assertEquals(4, steps.count { it is Step.Retry })
        assertEquals(Step.GiveUp, steps[4])
    }

    @Test fun liveStreamDroppingEveryThirtySecondsKeepsPlaying() {
        val r = Recovery(1)
        var t = 0L
        repeat(10) { r.ready(t); t += 30_000; assertEquals(Step.Retry(1000, 0, true), r.fail(Kind.NETWORK, true, t)) }
    }

    @Test fun offlineTvWaitsWithoutUsingTriesThenRecovers() {
        val r = Recovery(2)
        assertEquals(Step.WaitForNetwork, r.fail(Kind.LOCAL_NETWORK, true, 0))
        assertEquals(Step.WaitForNetwork, r.fail(Kind.LOCAL_NETWORK, true, 1_000))
        assertEquals(0, r.tries)
        assertEquals(0, r.source)
        r.ready(5_000)
        assertEquals(Step.Retry(1000, 0, true), r.fail(Kind.NETWORK, true, 100_000))
    }

    @Test fun behindLiveWindowRejoinsThreeTimesAMinuteThenCountsAsTimeout() {
        val r = Recovery(1)
        repeat(3) { assertEquals(Step.Retry(0, 0, true), r.fail(Kind.BEHIND_LIVE, true, it * 1_000L)) }
        assertEquals(Step.Retry(1000, 0, true), r.fail(Kind.BEHIND_LIVE, true, 4_000))
        assertEquals(Step.Retry(0, 0, true), r.fail(Kind.BEHIND_LIVE, true, 70_000))
    }

    @Test fun nonLiveStreamKeepsItsQuality() {
        val r = Recovery(1)
        val steps = (0 until 4).map { r.fail(Kind.SERVER, false, 0) as Step.Retry }
        assertTrue(steps.all { it.quality == 0 && !it.toLiveEdge })
    }
}

class CandidatesTest {
    private val chosen = Channel("RTP 1", "https://a.example/rtp1.m3u8", id = "RTP1.pt", source = "Free-TV",
        alts = listOf("https://b.example/rtp1.m3u8", "http://203.0.113.7:8080/rtp1.m3u8", "http://[2001:db8::1]/rtp1.m3u8"))

    @Test fun identityIsTheTvgIdWithoutFeed() {
        assertEquals("rtp1.pt", channelKey("RTP1.pt@HD"))
        assertEquals("rtp1.pt", channelKey("rtp1.pt"))
        assertEquals("", channelKey(""))
    }

    @Test fun bareIpAlternatesAreExcluded() {
        assertTrue(isBareIp("http://203.0.113.7:8080/x"))
        assertTrue(isBareIp("http://user@[2001:db8::1]/x"))
        assertFalse(isBareIp("https://cdn1.example.pt/x?ip=1.2.3.4"))
        assertEquals(listOf(chosen.url, "https://b.example/rtp1.m3u8"), candidates(chosen, emptyList(), emptyList()))
    }

    @Test fun catalogAndM3uptAreUsedButNotFreeTvOrIptvOrgRuntimeEntries() {
        val catalog = listOf(Channel("RTP 1", "https://c.example/rtp1.m3u8", id = "rtp1.pt@SD", source = "iptv-org", alts = listOf("https://d.example/rtp1.m3u8")))
        val runtime = listOf(
            Channel("RTP1", "https://m3upt.example/rtp1.m3u8", id = "RTP1.pt", source = "M3UPT"),
            Channel("RTP1", "https://relay.example/rtp1.m3u8", id = "RTP1.pt", source = "Free-TV"),
            Channel("RTP1", "https://relay2.example/rtp1.m3u8", id = "RTP1.pt", source = "iptv-org"),
            Channel("RTP1", chosen.url, id = "RTP1.pt", source = "M3UPT"), // same URL: kept once
        )
        assertEquals(listOf(chosen.url, "https://b.example/rtp1.m3u8", "https://c.example/rtp1.m3u8", "https://d.example/rtp1.m3u8",
            "https://m3upt.example/rtp1.m3u8"), candidates(chosen, catalog, runtime))
    }

    @Test fun namesAreNeverMatched() {
        val sic = Channel("SIC", "https://sic.example/live.m3u8", id = "SIC.pt")
        val catalog = listOf(Channel("SIC Alta Definição", "https://sicad.example/live.m3u8", id = "SICAltaDefinicao.pt"),
            Channel("SIC", "https://other.example/sic.m3u8")) // no tvg-id: no identity
        assertEquals(listOf(sic.url), candidates(sic, catalog, emptyList()))
        assertEquals(listOf("https://x.example/sic.m3u8"), candidates(Channel("SIC", "https://x.example/sic.m3u8"), catalog, emptyList()))
    }

    @Test fun chosenUrlStaysFirstUnlessItFailedAndAnotherWorked() {
        val now = 100 * Health.DAY
        val ch = Channel("X", "https://a.example/x", alts = listOf("https://b.example/x", "https://c.example/x"))
        val failed = Health().fail(Kind.FORBIDDEN, now - 1000)
        val worked = Health().ok(now - 2000)
        // failed, but nothing else is known to work: the user's choice is tried first
        assertEquals(listOf(ch.url, "https://b.example/x", "https://c.example/x"),
            candidates(ch, emptyList(), emptyList(), { if (it == ch.url) failed else null }, now))
        val h = mapOf(ch.url to failed, "https://c.example/x" to worked)
        assertEquals(listOf("https://c.example/x", "https://b.example/x", ch.url), candidates(ch, emptyList(), emptyList(), h::get, now))
        // the chosen URL has not failed: it stays first, the others sorted by health
        assertEquals(listOf(ch.url, "https://c.example/x", "https://b.example/x"),
            candidates(ch, emptyList(), emptyList(), mapOf("https://c.example/x" to worked)::get, now))
    }
}

class HealthTest {
    private val now = 100 * Health.DAY

    @Test fun deadOnlyForStreamFailuresOfTheLastDayWithoutSuccessSince() {
        assertTrue(Health().fail(Kind.GEO, now - 1000).dead(now))
        assertTrue(Health().fail(Kind.TIMEOUT, now - 1000).dead(now))
        assertFalse(Health().fail(Kind.NETWORK, now - 1000).dead(now))
        assertFalse(Health().fail(Kind.LOCAL_NETWORK, now - 1000).dead(now))
        assertFalse(Health().fail(Kind.GEO, now - Health.DAY - 1).dead(now))
        assertFalse(Health().fail(Kind.GEO, now - 2000).ok(now - 1000).dead(now))
    }

    @Test fun offlineFailuresDoNotCount() {
        val h = Health().fail(Kind.LOCAL_NETWORK, now - 1000)
        assertEquals(0, h.fails)
        assertFalse(h.failedRecently(now))
        assertEquals(2, Health().fail(Kind.GEO, now - 2).fail(Kind.GEO, now - 1).fails)
        assertTrue(Health().fail(Kind.GEO, now - 2).fail(Kind.LOCAL_NETWORK, now - 1).dead(now))
        assertEquals(2, Health().ok(now - 2).fail(Kind.LOCAL_NETWORK, now - 1).score(now))
    }

    @Test fun entriesUnseenForAMonthAreStale() {
        assertFalse(Health().ok(now - 29 * Health.DAY).stale(now))
        assertTrue(Health().fail(Kind.GEO, now - 31 * Health.DAY).stale(now))
        assertFalse(Health().ok(now - 40 * Health.DAY).fail(Kind.GEO, now - 1).stale(now))
        assertTrue(Health().ok(now + 1000).stale(now))
    }

    @Test fun timesInTheFutureAreIgnored() {
        val ahead = Health().fail(Kind.GEO, now + 3600_000)
        assertFalse(ahead.dead(now))
        assertEquals(1, ahead.score(now))
        assertFalse(Health().ok(now + 3600_000).succeeded(now))
    }

    @Test fun scoreOrdersWorkedUnknownFailed() {
        assertEquals(2, Health().ok(now - 1).score(now))
        assertEquals(-3, Health().fail(Kind.GEO, now - 3).fail(Kind.GEO, now - 2).fail(Kind.GEO, now - 1).score(now))
    }

    @Test fun storedFormatRoundTrips() {
        val h = Health(1, 2, Kind.FORBIDDEN, 3)
        assertEquals(h, Health.decode(h.encode()))
        assertEquals(Health(5, 0, null, 0), Health.decode(Health(5).encode()))
        assertNull(Health.decode("1788000000000"))
        assertNull(Health.decode(null))
    }
}

class RightsTest {
    @Test fun frenchChannelsWithoutLegalFreeLiveFromPortugal() {
        assertEquals("TF1+", rightsService("TF1.fr@HD", "TF1 HD"))
        assertEquals("TF1+", rightsService("", "TMC"))
        assertEquals("M6+", rightsService("", "M6 HD"))
        assertEquals("france.tv", rightsService("", "France 2 (1080p)"))
        assertNull(rightsService("franceinfo.fr", "franceinfo:")) // free on its own YouTube channel
        assertEquals("YouTube", officialApp("Franceinfo.fr@SD", "Franceinfo (720p)"))
        assertEquals("YouTube", officialApp("", "franceinfo:"))
        assertNull(rightsService("France24.fr", "France 24"))
        assertNull(rightsService("", "TV5MONDE Europe"))
    }

    @Test fun rtpChannelsPointToTheFreeOfficialApp() {
        assertEquals("RTP Play", officialApp("RTP1.pt", "RTP 1"))
        assertEquals("RTP Play", officialApp("", "RTP 2 HD"))
        assertEquals("RTP Play", officialApp("", "RTP Notícias (720p)"))
        assertNull(officialApp("TVI.pt", "TVI"))
        assertNull(officialApp("", "RTP"))
        assertNull(rightsService("RTP1.pt", "RTP 1"))
    }

    @Test fun codecNames() {
        assertEquals("E-AC-3", mimeLabel("audio/eac3"))
        assertEquals("HEVC", mimeLabel("video/hevc"))
        assertEquals("OPUS", mimeLabel("audio/opus"))
        assertEquals("?", mimeLabel(null))
    }
}
