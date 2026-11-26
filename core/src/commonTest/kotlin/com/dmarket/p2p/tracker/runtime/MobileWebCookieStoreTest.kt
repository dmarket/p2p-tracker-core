package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.MonotonicElapsedClock
import com.dmarket.p2p.tracker.adapter.host.InMemoryCredentialVault
import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.adapter.host.SealedSteamCookieVault
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.ElapsedClock
import com.dmarket.p2p.tracker.port.host.InstallMarker
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.host.SteamCookieStore
import com.dmarket.p2p.tracker.port.host.SteamCookieStoreException
import com.dmarket.p2p.tracker.support.FakeBrowserCookieStore
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/** The mobile platform with the Steam session in the WebView's store ([MobileTrackerPlatform.webCookies]). */
class MobileWebCookieStoreTest {
    private val clock = FakeClock()
    private val alice = SteamId("76561198000000001")
    private val credential = SteamCredential(token = "steam-token", subjectSteamId = alice, expiresAt = clock.now() + 20.hours)

    private class Marker(var same: Boolean) : InstallMarker {
        override suspend fun isSameInstall(): Boolean = same

        override suspend fun markInstall() {
            same = true
        }
    }

    private class Platform(
        override val clock: Clock,
        override val webCookies: SteamCookieStore?,
        val marker: Marker = Marker(same = true),
        val sealed: InMemoryDeviceKeyValueStore = InMemoryDeviceKeyValueStore(),
        val account: InMemoryDeviceKeyValueStore = InMemoryDeviceKeyValueStore(),
        val credentials: InMemoryCredentialVault = InMemoryCredentialVault(),
    ) : MobileTrackerPlatform(MarketplaceTokenSupplier { null }) {
        override val elapsedClock: ElapsedClock = MonotonicElapsedClock()
        override val surface: RuntimeSurface = RuntimeSurface.AndroidNative
        override val network: NetworkConditions = NetworkConditions.Unmetered
        override val installStore: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()
        override val sealedStore: DeviceKeyValueStore = sealed
        override val sealedVault: CredentialVault = credentials
        override val accountStore: DeviceKeyValueStore = account
        override val installMarker: InstallMarker = marker

        override fun dmarketEngine(): HttpClientEngine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        override fun steamEngine(): HttpClientEngine = MockEngine { respondError(HttpStatusCode.InternalServerError) }

        override suspend fun clearAccountStore() {
            accountKeys.forEach { account.remove(it) }
        }

        /** The keys the tests put in [account]. */
        val accountKeys = listOf("kept")
    }

    private val web = FakeBrowserCookieStore(clock)

    private fun signedIn() {
        web.signIn("steamcommunity.com", "steamLoginSecure", "community-session")
        web.signIn("login.steampowered.com", "steamRefresh_steam", "refresh")
        web.signIn("example.com", "other-site", "kept")
    }

    private suspend fun sealedKeyOf(platform: Platform) = platform.sealed.get(SealedSteamCookieVault.KEY)

    @Test
    fun theCoreReadsAndWritesTheWebViewStoreItself() = runTest {
        signedIn()
        val platform = Platform(clock, web)

        assertEquals("steamLoginSecure=community-session", platform.steamCookieStore.cookieHeader("https://steamcommunity.com/"))
        platform.steamCookieStore.set("https://steamcommunity.com/", "sessionid", "sid", null, httpOnly = false)
        assertEquals("sid", web.slots.single { it.name == "sessionid" }.value)
        assertNull(sealedKeyOf(platform), "nothing goes to the sealed vault")
    }

    @Test
    fun aNewInstallLeavesTheWebViewSessionAndRemovesTheSealedKey() = runTest {
        signedIn()
        val platform = Platform(clock, web, marker = Marker(same = false))
        platform.sealed.set(SealedSteamCookieVault.KEY, "[]")
        val before = web.slots.toList()

        platform.deviceIds.current()

        assertEquals(before, web.slots, "a sign-in made before the core's first call stays the session")
        assertNull(sealedKeyOf(platform))
        assertEquals(0, web.clears)
    }

    @Test
    fun theSealedCookieKeyIsRemovedOnTheSameInstallWithoutMigration() = runTest {
        val platform = Platform(clock, web)
        platform.sealed.set(
            SealedSteamCookieVault.KEY,
            """[{"name":"steamLoginSecure","value":"old","domain":"steamcommunity.com"}]""",
        )

        assertNull(platform.steamCookieStore.cookieHeader("https://steamcommunity.com/"), "the vault's cookies are not copied")
        assertNull(sealedKeyOf(platform))
        assertTrue(web.slots.isEmpty())
    }

    @Test
    fun theImportCopiesNothingAndDropsTheScrapedToken() = runTest {
        signedIn()
        val platform = Platform(clock, web)
        platform.vault.writeSteamCredential(credential)
        val before = web.slots.toList()

        platform.importSteamSession(listOf(SteamCookie("steamLoginSecure", "from-the-host", "steamcommunity.com")))

        assertEquals(before, web.slots)
        assertNull(platform.vault.readSteamCredential())
        assertNull(sealedKeyOf(platform))
    }

    @Test
    fun clearingTheSessionDeletesEverySteamHostAndTheTokenButNotOtherSites() = runTest {
        signedIn()
        val platform = Platform(clock, web)
        platform.vault.writeSteamCredential(credential)
        platform.storageFor(alice).set("kept", "state")

        platform.clearSteamSession()

        assertEquals(listOf("other-site"), web.slots.map { it.name })
        assertNull(platform.vault.readSteamCredential())
        assertEquals("state", platform.account.get("kept"), "the account's state stays")
    }

    @Test
    fun logoutClearsTheWebViewSessionLastSoItsFailureLeavesNothingElse() = runTest {
        signedIn()
        val platform = Platform(clock, web)
        platform.vault.writeSteamCredential(credential)
        platform.storageFor(alice).set("kept", "state")
        web.failWrites = true

        assertFailsWith<SteamCookieStoreException> { platform.clearAccountState() }
        assertNull(platform.vault.readSteamCredential())
        assertNull(platform.account.get("kept"))

        web.failWrites = false
        platform.clearAccountState()
        assertEquals(listOf("other-site"), web.slots.map { it.name })
    }

    @Test
    fun theFacadesClearGoesThroughTheTrackerOnceBuilt() = runTest {
        signedIn()
        val platform = Platform(clock, web)
        val mobile =
            MobileTracker(platform, linkedAccount = {
                alice
            }, cellularProofs = { false }, hostMarketplaceClient = { _, _, _, _ -> FakeMarketplaceClient() })

        assertNull(mobile.clearSteamSession(), "before the build the platform clears")
        assertEquals(1, web.clears)

        signedIn()
        mobile.tracker(backgroundScope, "https://api.test", TrackerConfig.defaults(), NoOpNetworkObserver, NoOpEventObserver)
        assertNull(mobile.clearSteamSession(), "no loop runs")
        assertEquals(2, web.clears)
    }

    @Test
    fun withoutAWebViewStoreTheSealedVaultStaysTheSession() = runTest {
        val platform = Platform(clock, webCookies = null)
        platform.importSteamSession(listOf(SteamCookie("steamLoginSecure", "secret", "steamcommunity.com")))

        assertNotNull(sealedKeyOf(platform))
        assertEquals("steamLoginSecure=secret", platform.steamCookieStore.cookieHeader("https://steamcommunity.com/"))
        platform.clearSteamSession()
        assertNull(sealedKeyOf(platform))
        assertSame(null, platform.webCookies)
    }
}
