package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.support.FakeDisk
import com.dmarket.p2p.tracker.support.FakeKeystore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

class AndroidTrackerTest {
    @Test
    fun a_failure_in_the_tracker_scope_does_not_reach_the_uncaught_exception_handler() = runBlocking {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        var uncaught: Throwable? = null
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught = e }
        try {
            AndroidTracker.scope.launch { error("host linkedAccount() threw") }.join()
            assertNull(uncaught)
            assertTrue(AndroidTracker.scope.isActive, "the scope survives for the next start")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun the_import_after_install_writes_the_vault_before_the_tracker_is_built() = runBlocking {
        val platform = AndroidTrackerPlatform(
            prefs = FakeDisk()::prefs,
            cipher = FakeKeystore().cipher(),
            tokens = MarketplaceTokenSupplier { null },
            io = Dispatchers.Unconfined,
        )
        val alice = SteamId("76561198000000001")
        platform.vault.writeSteamCredential(SteamCredential("old-token", alice, Clock.System.now() + 20.hours))
        AndroidTracker.install(platform, linkedAccount = { alice }, cellularProofs = { false })

        val state = AndroidTracker.importSteamSession {
            listOf(
                SteamCookie("steamLoginSecure", "login-secure", "steamcommunity.com"),
                SteamCookie("steamRefresh_steam", "refresh", "login.steampowered.com"),
                SteamCookie("tracker", "other", "dmarket.com"),
            )
        }

        assertNull(state, "no tracker yet: nothing to read the session with")
        assertNull(AndroidTracker.tracker)
        assertEquals(
            listOf("steamLoginSecure@steamcommunity.com", "steamRefresh_steam@login.steampowered.com"),
            platform.steamCookies.readSteamCookies().map { "${it.name}@${it.domain}" },
        )
        assertNull(platform.vault.readSteamCredential(), "the previous session's token is dropped")
    }
}
