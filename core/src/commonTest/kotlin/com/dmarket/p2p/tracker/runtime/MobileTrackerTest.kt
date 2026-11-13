package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.MonotonicElapsedClock
import com.dmarket.p2p.tracker.adapter.host.InMemoryCredentialVault
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceAuthenticator
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.ElapsedClock
import com.dmarket.p2p.tracker.port.host.InstallMarker
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.fakeMarketplaceCredential
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MobileTrackerTest {
    private val clock = FakeClock()
    private val alice = SteamId("76561198000000001")
    private val bob = SteamId("76561198000000002")
    private val credential = SteamCredential(token = "steam-token", subjectSteamId = alice, expiresAt = clock.now() + 20.hours)

    /** A key-value store that can be emptied, as a platform's account store must be. */
    private class FakeStore : DeviceKeyValueStore {
        val entries = mutableMapOf<String, String>()
        var reads = 0

        override suspend fun get(key: String): String? = entries[key].also { reads++ }

        override suspend fun set(key: String, value: String) {
            entries[key] = value
        }

        override suspend fun remove(key: String) {
            entries.remove(key)
        }
    }

    /** Answers [same] until marked, as the iOS marks would; counts the marks. */
    private class FakeInstallMarker(var same: Boolean = false) : InstallMarker {
        var marks = 0

        override suspend fun isSameInstall(): Boolean = same

        override suspend fun markInstall() {
            marks++
            same = true
        }
    }

    /** Its storage outlives it: [restarted] is the same device after a process restart. */
    private class FakePlatform(
        override val clock: Clock,
        tokens: MarketplaceTokenSupplier = MarketplaceTokenSupplier { null },
        val account: FakeStore = FakeStore(),
        val marker: FakeInstallMarker = FakeInstallMarker(),
        val credentials: InMemoryCredentialVault = InMemoryCredentialVault(),
        val install: FakeStore = FakeStore(),
        val sealed: FakeStore = FakeStore(),
    ) : MobileTrackerPlatform(tokens) {
        fun restarted() =
            FakePlatform(clock, account = account, marker = marker, credentials = credentials, install = install, sealed = sealed)

        // The Android actual reads android.os.SystemClock, which the host tests do not stub.
        override val elapsedClock: ElapsedClock = MonotonicElapsedClock()
        override val surface: RuntimeSurface = RuntimeSurface.AndroidNative
        override val network: NetworkConditions = NetworkConditions.Unmetered
        override val installStore: DeviceKeyValueStore = install
        override val sealedStore: DeviceKeyValueStore = sealed
        override val sealedVault: CredentialVault = credentials
        override val accountStore: DeviceKeyValueStore = account
        override val installMarker: InstallMarker = marker

        override fun dmarketEngine(): HttpClientEngine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        override fun steamEngine(): HttpClientEngine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        override suspend fun clearAccountStore() = account.entries.clear()
    }

    private val platform = FakePlatform(clock)
    private var linked: SteamId? = alice

    private fun TestScope.tracker(
        factory: MarketplaceClientFactory? = null,
        config: TrackerConfig = TrackerConfig.defaults(),
        scope: CoroutineScope = backgroundScope,
    ): P2pTracker = MobileTracker(platform, linkedAccount = { linked }, cellularProofs = { false }, hostMarketplaceClient = factory)
        .tracker(scope, "https://api.test", config, NoOpNetworkObserver, NoOpEventObserver)

    @Test
    fun aThrowFromTheHostSupplierIsATemporaryFailureAndNullIsALogout() = runTest {
        var answer: () -> MarketplaceCredential? = { error("keychain locked") }
        val credentials = FakePlatform(clock, tokens = { answer() }).marketplaceCredentials

        assertNull(credentials.current())
        assertFalse(credentials.lastRefreshFailedLoggedOut)

        answer = { fakeMarketplaceCredential("back") }
        assertNotNull(credentials.current())
        assertFalse(credentials.lastRefreshFailedLoggedOut)

        answer = { null }
        assertNull(credentials.current())
        assertTrue(credentials.lastRefreshFailedLoggedOut)
    }

    @Test
    fun theHostClientFactoryGetsTheConfiguredRetry() = runTest {
        val retry = MarketplaceRetryConfig(maxRetries = 7, retryBaseDelayMs = 11, retryMaxDelayMs = 99)
        var seen: MarketplaceRetryConfig? = null

        // The factory runs while the tracker is assembled; failing it there is enough to read what it got.
        assertFailsWith<IllegalStateException> {
            tracker(
                factory = MarketplaceClientFactory { _, _, _, r ->
                    seen = r
                    error("captured")
                },
                config = TrackerConfig.defaults().copy(marketplaceRetry = retry),
            )
        }

        assertEquals(retry, seen)
    }

    /** What a session leaves behind: the Steam credential, and the account's loop state and progress. */
    private suspend fun workAsTheLinkedAccount() {
        platform.vault.writeSteamCredential(credential)
        val stores = platform.stores(checkNotNull(linked))
        stores.loopState.setNextHeartbeatAt(clock.now() + 5.minutes)
        stores.progress.recordHandledDirectives(setOf(DirectiveId("directive-1")))
    }

    @Test
    fun anotherAccountErasesThePreviousAccountsState() = runTest {
        val tracker = tracker()
        assertTrue(tracker.start())
        workAsTheLinkedAccount()
        assertTrue(platform.account.entries.isNotEmpty())

        linked = bob
        assertTrue(tracker.start())

        assertEquals(bob, tracker.state.value.account)
        assertTrue(platform.account.entries.isEmpty())
        val stores = platform.stores(bob)
        assertNull(stores.loopState.nextHeartbeatAt())
        assertTrue(stores.progress.loadHandledDirectives().isEmpty())
    }

    @Test
    fun theSameAccountKeepsItsState() = runTest {
        val tracker = tracker()
        tracker.start()
        workAsTheLinkedAccount()

        tracker.stop()
        tracker.start()

        assertEquals(clock.now() + 5.minutes, platform.stores(alice).loopState.nextHeartbeatAt())
        assertEquals(credential, platform.vault.readSteamCredential())
    }

    @Test
    fun logoutErasesTheSteamCredentialAndTheAccountStateButKeepsTheDeviceId() = runTest {
        val tracker = tracker()
        tracker.start()
        workAsTheLinkedAccount()
        val deviceId = tracker.deviceId()

        tracker.onLogout()

        assertFalse(tracker.state.value.running)
        assertNull(platform.vault.readSteamCredential())
        assertTrue(platform.account.entries.isEmpty())
        assertEquals(deviceId, tracker.deviceId())
    }

    @Test
    fun anotherInstallGetsANewDeviceIdAndEmptyStores() = runTest {
        val tracker = tracker()
        tracker.start()
        workAsTheLinkedAccount()
        val deviceId = tracker.deviceId()
        assertEquals(1, platform.marker.marks)

        // A reinstall, or the app's data restored onto another device: the next process finds it.
        platform.marker.same = false
        assertEquals(deviceId, tracker.deviceId())
        val restarted = platform.restarted()

        assertNotEquals(deviceId, restarted.deviceIds.current())
        assertEquals(2, platform.marker.marks)
        assertNull(platform.credentials.readSteamCredential())
        assertTrue(platform.account.entries.isEmpty())
        // The account stores read empty even for the account they belonged to.
        val stores = restarted.stores(alice)
        assertNull(stores.loopState.nextHeartbeatAt())
        assertTrue(stores.progress.loadHandledDirectives().isEmpty())
    }

    @Test
    fun steamCookiesAreReadFromTheSealedStoreOncePerProcess() = runTest {
        val cookie = SteamCookie("steamLoginSecure", "secret", "steamcommunity.com")
        platform.steamCookies.writeSteamCookies(listOf(cookie))
        val restarted = platform.restarted()
        val readsBefore = platform.sealed.reads

        repeat(10) { assertEquals(listOf(cookie), restarted.steamCookies.readSteamCookies()) }
        // The install check (device id) and the cookies: one read each, then memory.
        assertEquals(readsBefore + 2, platform.sealed.reads)

        val other = SteamCookie("sessionid", "fresh", "steamcommunity.com")
        restarted.steamCookies.writeSteamCookies(listOf(other))
        assertEquals(listOf(other), restarted.steamCookies.readSteamCookies())
        restarted.importSteamSession(listOf(cookie))
        assertEquals(listOf(cookie), restarted.steamCookies.readSteamCookies())
        restarted.steamCookies.clearSteamCookies()
        assertEquals(emptyList(), restarted.steamCookies.readSteamCookies())
        restarted.importSteamSession(listOf(cookie))
        restarted.clearAccountState()
        assertEquals(emptyList(), restarted.steamCookies.readSteamCookies())
        assertEquals(readsBefore + 2, platform.sealed.reads)
        // Every change reached the disk: a new process reads it there.
        assertEquals(emptyList(), restarted.restarted().steamCookies.readSteamCookies())
    }

    @Test
    fun logoutAlsoEmptiesTheCookiesKeptInMemory() = runTest {
        val tracker = tracker()
        platform.importSteamSession(listOf(SteamCookie("steamLoginSecure", "secret", "steamcommunity.com")))
        assertTrue(platform.steamCookies.readSteamCookies().isNotEmpty())

        tracker.onLogout()

        assertEquals(emptyList(), platform.steamCookies.readSteamCookies())
    }

    @Test
    fun theHostsDmarketClientAuthenticatesThroughTheHostTokenLayer() = runTest {
        var given: MarketplaceAuthenticator? = null
        tracker(
            MarketplaceClientFactory { _, authenticator, _, _ ->
                given = authenticator
                FakeMarketplaceClient()
            },
        )

        assertSame(platform.marketplaceAuthenticator, given)
    }

    @Test
    fun aDeliveryLeavesNoTimerBehindOnceStopped() = runTest {
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job())
        workAsTheLinkedAccount()
        val tracker = tracker(MarketplaceClientFactory { _, _, _, _ -> FakeMarketplaceClient() }, scope = scope)
        assertTrue(tracker.start())

        // A fresh session heartbeats before its create-trade call, and that heartbeat reschedules the loop.
        tracker.startDelivery(DealId("deal-1"))
        tracker.stop()
        runCurrent()

        assertTrue(scope.coroutineContext.job.children.none(), "nothing may wait in the process scope after stop")
        scope.cancel()
    }
}
