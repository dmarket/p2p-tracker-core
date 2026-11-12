package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.InMemoryCredentialVault
import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceAuthenticator
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.InstallMarker
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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

        override suspend fun get(key: String): String? = entries[key]

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

    private class FakePlatform(override val clock: Clock) : MobileTrackerPlatform(MarketplaceTokenSupplier { null }) {
        val account = FakeStore()
        val marker = FakeInstallMarker()
        val credentials = InMemoryCredentialVault()

        override val surface: RuntimeSurface = RuntimeSurface.AndroidNative
        override val network: NetworkConditions = NetworkConditions.Unmetered
        override val installStore: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()
        override val sealedStore: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()
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
    ): P2pTracker = MobileTracker(platform, linkedAccount = { linked }, cellularProofs = { false }, hostMarketplaceClient = factory)
        .tracker(backgroundScope, "https://api.test", config, NoOpNetworkObserver, NoOpEventObserver)

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

        // A reinstall, or the app's data restored onto another device.
        platform.marker.same = false

        assertNotEquals(deviceId, tracker.deviceId())
        assertEquals(2, platform.marker.marks)
        assertNull(platform.credentials.readSteamCredential())
        assertTrue(platform.account.entries.isEmpty())
        // The account stores read empty even for the account they belonged to.
        val stores = platform.stores(alice)
        assertNull(stores.loopState.nextHeartbeatAt())
        assertTrue(stores.progress.loadHandledDirectives().isEmpty())
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
}
