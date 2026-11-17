package com.local.streaming4free

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistRefreshPolicyTest {
    private val hour = 3600_000L

    @Test fun freshCacheIsReused() {
        assertFalse(playlistCacheExpired(10 * hour, 11 * hour))
        assertFalse(playlistCacheExpired(10 * hour, 22 * hour))
    }

    @Test fun expiredMissingAndFutureDatedCacheIsRefreshed() {
        assertTrue(playlistCacheExpired(10 * hour, 22 * hour + 1))
        assertTrue(playlistCacheExpired(0L, 11 * hour))
        assertTrue(playlistCacheExpired(20 * hour, 11 * hour))
    }
}
