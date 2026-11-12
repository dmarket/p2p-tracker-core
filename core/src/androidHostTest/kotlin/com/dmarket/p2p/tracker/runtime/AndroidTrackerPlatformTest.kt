package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.SealedKeyValueStore
import com.dmarket.p2p.tracker.adapter.SharedPreferencesKeyValueStore
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceRequest
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.ClaimVerdict
import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import com.dmarket.p2p.tracker.support.FakeDisk
import com.dmarket.p2p.tracker.support.FakeKeystore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import java.security.KeyStoreException
import java.security.UnrecoverableKeyException
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AndroidTrackerPlatformTest {
    private val now = Instant.parse("2026-11-11T10:00:00Z")
    private val clock = object : Clock {
        override fun now(): Instant = this@AndroidTrackerPlatformTest.now
    }
    private val alice = SteamId("76561198000000001")
    private val bob = SteamId("76561198000000002")
    private val credential = SteamCredential(token = "steam-secret-token", subjectSteamId = alice, expiresAt = now + 20.hours)
    private val claim = DealWriteClaim(
        dealId = DealId("deal-1"),
        action = DirectiveAction.CREATE_OFFER,
        phase = ClaimPhase.IN_FLIGHT,
        claimedAt = now,
        directiveId = DirectiveId("directive-1"),
    )

    private fun platform(disk: FakeDisk, keystore: FakeKeystore, tokens: MarketplaceTokenSupplier = MarketplaceTokenSupplier { null }) =
        AndroidTrackerPlatform(prefs = disk::prefs, cipher = keystore.cipher(), tokens = tokens, clock = clock, io = Dispatchers.Unconfined)

    /** What a session leaves behind: a device id, a Steam credential, and the account's loop state, progress and claims. */
    private suspend fun AndroidTrackerPlatform.workAs(account: SteamId) {
        deviceIds.current()
        vault.writeSteamCredential(credential)
        val stores = stores(account)
        stores.loopState.setNextHeartbeatAt(now + 5.minutes)
        stores.progress.recordHandledDirectives(setOf(DirectiveId("directive-1")))
        assertIs<ClaimVerdict.Proceed>(stores.claims.claim(claim, now, 15.minutes))
    }

    private suspend fun AndroidTrackerPlatform.assertCleanState(account: SteamId) {
        val stores = stores(account)
        assertNull(stores.loopState.nextHeartbeatAt())
        assertTrue(stores.progress.loadHandledDirectives().isEmpty())
        assertTrue(stores.claims.all().isEmpty())
    }

    @Test
    fun aColdStartOverTheSameDiskRestoresStateProgressAndClaims() = runTest {
        val disk = FakeDisk()
        val keystore = FakeKeystore()
        val before = platform(disk, keystore)
        before.workAs(alice)
        val deviceId = before.deviceIds.current()

        val after = platform(disk, keystore)

        assertEquals(deviceId, after.deviceIds.current())
        assertEquals(credential, after.vault.readSteamCredential())
        val stores = after.stores(alice)
        assertEquals(now + 5.minutes, stores.loopState.nextHeartbeatAt())
        assertEquals(setOf(DirectiveId("directive-1")), stores.progress.loadHandledDirectives())
        assertEquals(listOf(claim.key), stores.claims.all().map { it.key })
        // The restored claim still blocks a second create for the deal.
        assertIs<ClaimVerdict.InFlight>(stores.claims.claim(claim.copy(directiveId = DirectiveId("directive-2")), now, 15.minutes))
    }

    @Test
    fun aDeviceTransferWithoutTheKeystoreKeyGetsANewDeviceIdAndRefreshesTheSteamSession() = runTest {
        val disk = FakeDisk()
        val old = platform(disk, FakeKeystore())
        old.workAs(alice)
        val oldDeviceId = old.deviceIds.current()

        // Everything on disk came along (as if the app's dataExtractionRules missed the core's files); the key did not.
        val transferred = platform(disk.copy(), FakeKeystore())

        assertNotEquals(oldDeviceId, transferred.deviceIds.current())
        assertNull(transferred.vault.readSteamCredential())
        transferred.assertCleanState(alice)
        // The empty vault sends the Steam credential down the refresh path.
        var scrapes = 0
        val scraper = object : SteamSessionScraper {
            override suspend fun scrape(): SteamCredential? {
                scrapes++
                return null
            }
        }
        SteamCredentialProvider(vault = transferred.vault, scraper = scraper, clock = clock).current()
        assertEquals(1, scrapes)
    }

    @Test
    fun anUnopenableCredentialIsErasedNotThrown() = runTest {
        val disk = FakeDisk()
        platform(disk, FakeKeystore()).workAs(alice)
        val install = disk.files.getValue(AndroidTrackerPlatform.INSTALL_FILE)
        val plain = SharedPreferencesKeyValueStore(disk.prefs(AndroidTrackerPlatform.INSTALL_FILE))
        // No key under the alias (a restore or a transfer).
        assertNull(SealedKeyValueStore(plain, FakeKeystore().cipher()).get("steam_credential"))
        assertFalse("steam_credential" in install)
        // A key, but not the one that sealed the value: the tag check fails.
        val otherKey = FakeKeystore().apply { replace() }.cipher()
        assertNull(SealedKeyValueStore(plain, otherKey).get("device_id"))
        assertFalse("device_id" in install)
    }

    @Test
    fun aMalformedSealedDeviceIdIsErasedAndANewOneIsMade() = runTest {
        val malformed = listOf<(ByteArray) -> ByteArray>(
            { ByteArray(0) },
            { it.copyOf(1 + it[0] + 5) },
            { byteArrayOf(-1) + it.copyOfRange(1, it.size) },
            { byteArrayOf(0) + it.copyOfRange(1, it.size) },
            { byteArrayOf(17) + it.copyOfRange(1, it.size) },
            { byteArrayOf(12, 1, 2, 3) },
        )
        for (corrupt in malformed) {
            val disk = FakeDisk()
            val keystore = FakeKeystore()
            platform(disk, keystore).workAs(alice)
            val install = disk.files.getValue(AndroidTrackerPlatform.INSTALL_FILE)
            val oldDeviceId = install.getValue(DeviceVaultKeys.DEVICE_ID)
            val oldId = platform(disk, keystore).deviceIds.current()
            install[DeviceVaultKeys.DEVICE_ID] = Base64.encode(corrupt(Base64.decode(oldDeviceId)))

            val restarted = platform(disk, keystore)

            assertNotEquals(oldId, restarted.deviceIds.current())
            assertNull(restarted.vault.readSteamCredential())
            restarted.assertCleanState(alice)
        }
    }

    @Test
    fun aKeystoreKeyThatNoLongerWorksIsReplacedAndANewDeviceIdIsMade() = runTest {
        val breakages = listOf<FakeKeystore.() -> Unit>(
            { entryFailure = UnrecoverableKeyException("entry does not open") },
            // Cipher.init refuses it with an InvalidKeyException, as for a KeyPermanentlyInvalidatedException.
            { key = SecretKeySpec(ByteArray(3), "AES") },
        )
        for (breakKey in breakages) {
            val disk = FakeDisk()
            val keystore = FakeKeystore()
            platform(disk, keystore).workAs(alice)
            val oldId = platform(disk, keystore).deviceIds.current()
            val oldKey = keystore.key
            keystore.breakKey()

            val restarted = platform(disk, keystore)
            val newId = restarted.deviceIds.current()

            assertNotEquals(oldId, newId)
            assertNull(restarted.vault.readSteamCredential())
            restarted.assertCleanState(alice)
            assertNotEquals(oldKey, keystore.key)
            // The new key under the same alias opens what it sealed on the next start.
            assertEquals(newId, platform(disk, keystore).deviceIds.current())
        }
    }

    @Test
    fun aKeystoreFaultFailsTheCallAndErasesNothing() = runTest {
        val disk = FakeDisk()
        val keystore = FakeKeystore()
        val platform = platform(disk, keystore)
        platform.workAs(alice)
        val deviceId = platform.deviceIds.current()
        val onDisk = disk.copy().files

        keystore.failure = KeyStoreException("keystore2 busy")
        assertFailsWith<KeyStoreException> { platform.deviceIds.current() }
        assertFailsWith<KeyStoreException> { platform.vault.readSteamCredential() }
        assertFailsWith<KeyStoreException> { platform.stores(alice) }
        assertEquals(onDisk, disk.files)

        keystore.failure = null
        assertEquals(deviceId, platform.deviceIds.current())
        assertEquals(credential, platform.vault.readSteamCredential())
        assertEquals(setOf(DirectiveId("directive-1")), platform.stores(alice).progress.loadHandledDirectives())
    }

    @Test
    fun anotherAccountStartsFromCleanStateAndThePreviousOneIsErased() = runTest {
        val platform = platform(FakeDisk(), FakeKeystore())
        platform.workAs(alice)

        platform.assertCleanState(bob)
        platform.assertCleanState(alice)
    }

    @Test
    fun logoutErasesTheAccountStateAndTheCredentialButKeepsTheDeviceId() = runTest {
        val disk = FakeDisk()
        val platform = platform(disk, FakeKeystore())
        platform.workAs(alice)
        val deviceId = platform.deviceIds.current()

        platform.clearAccountState()

        assertTrue(disk.files.getValue(AndroidTrackerPlatform.ACCOUNT_FILE).isEmpty())
        assertNull(platform.vault.readSteamCredential())
        assertEquals(deviceId, platform.deviceIds.current())
        platform.assertCleanState(alice)
    }

    @Test
    fun noSecretReachesDiskInTheClear() = runTest {
        val disk = FakeDisk()
        platform(disk, FakeKeystore()).workAs(alice)

        val values = disk.allValues()
        assertTrue(values.isNotEmpty())
        assertTrue(values.none { credential.token in it || credential.token in decodedOrEmpty(it) })
        assertEquals(setOf(AndroidTrackerPlatform.INSTALL_FILE, AndroidTrackerPlatform.ACCOUNT_FILE), disk.files.keys)
    }

    @Test
    fun aStorageLocationIsOnlyEverASteamId64() = runTest {
        val platform = platform(FakeDisk(), FakeKeystore())
        assertFailsWith<IllegalArgumentException> { platform.storageFor(SteamId("../evil")) }
    }

    @Test
    fun dmarketAuthGoesThroughTheHostTokenLayer() = runTest {
        val asked = mutableListOf<String?>()
        val platform = platform(FakeDisk(), FakeKeystore()) { rejected ->
            asked += rejected
            MarketplaceCredential(token = if (rejected != null) "fresh-jwt" else "host-jwt", expiresAt = now + 1.hours)
        }

        assertEquals(
            mapOf("Authorization" to "host-jwt"),
            platform.marketplaceAuthenticator.headersFor(MarketplaceRequest("GET", "/x", "")),
        )
        assertTrue(platform.marketplaceAuthenticator.refreshOnUnauthorized("host-jwt"))
        assertEquals(listOf(null, "host-jwt"), asked)
    }

    @Test
    fun aHostWithNoSessionReadsAsLoggedOut() = runTest {
        val platform = platform(FakeDisk(), FakeKeystore()) { error("TokenException") }

        assertTrue(platform.marketplaceAuthenticator.headersFor(MarketplaceRequest("GET", "/x", "")).isEmpty())
        assertTrue(platform.marketplaceCredentials.lastRefreshFailedLoggedOut)
    }

    private fun decodedOrEmpty(value: String): String = runCatching { Base64.decode(value).decodeToString() }.getOrDefault("")
}
