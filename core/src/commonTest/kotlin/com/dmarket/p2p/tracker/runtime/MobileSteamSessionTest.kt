package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.MonotonicElapsedClock
import com.dmarket.p2p.tracker.adapter.host.InMemoryCredentialVault
import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.ElapsedClock
import com.dmarket.p2p.tracker.port.host.InstallMarker
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.fakeMarketplaceCredential
import com.dmarket.p2p.tracker.support.steamAccessToken
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockEngineConfig
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class MobileSteamSessionTest {
    private val clock = FakeClock()
    private val alice = SteamId("76561198000000001")
    private val bob = SteamId("76561198000000002")

    /** Every request the Steam client sent; Steam answers the community page as whoever the session cookie names. */
    private val steamRequests = mutableListOf<HttpRequestData>()

    private inner class Platform : MobileTrackerPlatform(MarketplaceTokenSupplier { fakeMarketplaceCredential("dm-token") }) {
        private val account = mutableMapOf<String, String>()
        override val clock: Clock = this@MobileSteamSessionTest.clock

        // The Android actual reads android.os.SystemClock, which the host tests do not stub.
        override val elapsedClock: ElapsedClock = MonotonicElapsedClock()
        override val surface: RuntimeSurface = RuntimeSurface.AndroidNative
        override val network: NetworkConditions = NetworkConditions.Unmetered
        override val installStore: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()
        override val sealedStore: DeviceKeyValueStore = InMemoryDeviceKeyValueStore()
        override val sealedVault: CredentialVault = InMemoryCredentialVault()
        override val accountStore: DeviceKeyValueStore = object : DeviceKeyValueStore {
            override suspend fun get(key: String): String? = account[key]

            override suspend fun set(key: String, value: String) {
                account[key] = value
            }

            override suspend fun remove(key: String) {
                account.remove(key)
            }
        }
        override val installMarker: InstallMarker = object : InstallMarker {
            override suspend fun isSameInstall() = true

            override suspend fun markInstall() = Unit
        }

        override fun dmarketEngine(): HttpClientEngine = MockEngine { respond("", HttpStatusCode.InternalServerError) }

        /** On the test's scheduler: a real-thread engine would let the cycle deadline's virtual clock run out under it. */
        var dispatcher: CoroutineDispatcher? = null

        override fun steamEngine(): HttpClientEngine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = checkNotNull(this@Platform.dispatcher)
                addHandler { request -> steam(request) }
            },
        )

        private fun MockRequestHandleScope.steam(request: HttpRequestData): HttpResponseData {
            steamRequests += request
            val owner = request.headers["Cookie"].orEmpty().substringAfter("steamLoginSecure=", "").substringBefore("%7C%7C")
            val community = request.url.host == "steamcommunity.com" && owner.isNotEmpty()
            return if (community && request.url.encodedPath.trimEnd('/').isEmpty()) {
                val token = steamAccessToken(owner, clock.now() + 20.hours)
                respond("""<div data-loyalty_webapi_token="&quot;$token&quot;"></div><script>var g_steamID = "$owner";</script>""")
            } else if (community && request.url.encodedPath == "/tradeoffer/new/send") {
                respond("""{"tradeofferid":"8001","needs_mobile_confirmation":true}""")
            } else if (community && request.url.encodedPath.endsWith("/cancel")) {
                respond("""{"success":1}""")
            } else {
                respond("", HttpStatusCode.InternalServerError)
            }
        }

        override suspend fun clearAccountStore() = account.clear()
    }

    private val platform = Platform()
    private val marketplace = FakeMarketplaceClient(HeartbeatResponse(ttlSeconds = 60, linkedSteamId = alice))

    /** The Steam account the app says its user links. */
    private var linked = alice

    private fun TestScope.mobile(steamWrites: Boolean = false): MobileTracker = MobileTracker(
        platform.also { it.dispatcher = StandardTestDispatcher(testScheduler) },
        linkedAccount = { linked },
        cellularProofs = { false },
        hostMarketplaceClient = MarketplaceClientFactory { _, _, _, _ -> marketplace },
        steamWrites = steamWrites,
    )

    private fun TestScope.tracker(steamWrites: Boolean = false, mobile: MobileTracker = mobile(steamWrites)): P2pTracker =
        mobile.tracker(backgroundScope, "https://api.test", TrackerConfig.defaults(), NoOpNetworkObserver, NoOpEventObserver)

    private fun loginSecure(owner: SteamId, left: Duration) = "${owner.value}%7C%7C${steamAccessToken(owner.value, clock.now() + left)}"

    private fun session(owner: SteamId, left: Duration = 20.hours) = listOf(
        SteamCookie("steamLoginSecure", loginSecure(owner, left), "steamcommunity.com"),
        SteamCookie("steamLoginSecure", loginSecure(owner, left), "store.steampowered.com"),
        SteamCookie("sessionid", "sid", "steamcommunity.com"),
        SteamCookie("steamRefresh_steam", "refresh", "login.steampowered.com"),
    )

    private val push = """{"type":"wake_all","reason":"new_deal"}"""

    @Test
    fun eachOfTheFourStatesComesFromTheVaultAndShowsInTrackerState() = runTest {
        val tracker = tracker()

        assertEquals(SteamWebSessionState.GONE, tracker.checkSteamSession())
        assertEquals(SteamWebSessionState.GONE, tracker.state.value.steamSession)

        platform.steamCookies.writeSteamCookies(session(alice, left = 20.hours))
        assertEquals(SteamWebSessionState.ALIVE, tracker.checkSteamSession())
        assertEquals(SteamWebSessionState.ALIVE, tracker.state.value.steamSession)
        assertTrue(steamRequests.isEmpty(), "a live session costs no request")

        // Due for renewal, and Steam refuses the renewal here, so it stays due.
        platform.steamCookies.writeSteamCookies(session(alice, left = 30.minutes))
        assertEquals(SteamWebSessionState.NEEDS_REFRESH, tracker.checkSteamSession())
        assertEquals(SteamWebSessionState.NEEDS_REFRESH, tracker.state.value.steamSession)
        assertTrue(steamRequests.all { it.url.host == "login.steampowered.com" && it.url.encodedPath == "/jwt/ajaxrefresh" })

        platform.steamCookies.writeSteamCookies(session(alice))
        tracker.onPush(push) // the heartbeat names alice as linked
        tracker.importSteamSession(session(bob))
        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, tracker.state.value.steamSession)
        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, tracker.checkSteamSession())
    }

    @Test
    fun anotherAccountsSessionSendsNoSteamRequestAtAll() = runTest {
        // A watched deal that needs a proof: every Steam read and the proof would run for it on alice's session.
        marketplace.heartbeatResponse = HeartbeatResponse(
            ttlSeconds = 60,
            linkedSteamId = alice,
            activeTracking = listOf(
                TrackedDeal(
                    dealId = DealId("d1"),
                    steamOfferId = OfferId("7001"),
                    watch = setOf(WatchTarget.GET_TRADE_OFFER, WatchTarget.GET_TRADE_HISTORY),
                    proofRequired = true,
                ),
            ),
        )
        val tracker = tracker()
        tracker.importSteamSession(session(alice))
        tracker.onPush(push)
        assertEquals(SteamWebSessionState.ALIVE, tracker.state.value.steamSession)
        assertTrue(steamRequests.any { it.url.host == "api.steampowered.com" }, "alice's session reads the deal on Steam")

        // The user signs in to Steam as bob; DMarket links alice.
        tracker.importSteamSession(session(bob, left = 30.minutes))
        steamRequests.clear()

        tracker.onPush(push)
        tracker.updateStatus(DealId("d1"))
        val delivery = tracker.startDelivery(DealId("d1"))
        tracker.checkSteamSession()
        tracker.onPush(push)

        assertEquals(emptyList(), steamRequests.map { "${it.url.host}${it.url.encodedPath}" })
        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, tracker.state.value.steamSession)
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, tracker.state.value.block)
        assertTrue(delivery.result is CreateOfferResult.AccountMismatch || delivery.result is CreateOfferResult.Failed)
        // The heartbeat still goes, for the session's own account, and says whose session it is.
        assertEquals(bob, marketplace.heartbeatsSent.last().steamId)
        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, marketplace.heartbeatsSent.last().steamSession)
    }

    @Test
    fun signingBackInAsTheLinkedAccountLiftsTheBlock() = runTest {
        val tracker = tracker()
        tracker.importSteamSession(session(alice))
        tracker.onPush(push)
        tracker.importSteamSession(session(bob))
        tracker.onPush(push)
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, tracker.state.value.block)

        assertEquals(SteamWebSessionState.ALIVE, tracker.importSteamSession(session(alice)))
        tracker.onPush(push)

        assertEquals(TrackerBlock.NONE, tracker.state.value.block)
        assertEquals(SteamWebSessionState.ALIVE, tracker.state.value.steamSession)
        assertEquals(alice, marketplace.heartbeatsSent.last().steamId)
    }

    @Test
    fun theImportKeepsOnlyTheSteamSessionDomains() = runTest {
        val tracker = tracker()

        tracker.importSteamSession(
            session(alice) + listOf(
                SteamCookie("steamLoginSecure", "x", "help.steampowered.com"),
                SteamCookie("browserid", "y", ".steamcommunity.com"),
                SteamCookie("tracker", "z", "dmarket.com"),
            ),
        )

        val stored = platform.steamCookies.readSteamCookies()
        assertEquals(setOf("steamcommunity.com", "store.steampowered.com", "login.steampowered.com"), stored.map { it.domain }.toSet())
        assertTrue(stored.any { it.name == "browserid" && it.domain == "steamcommunity.com" })
    }

    @Test
    fun theOnStartCheckImportsNothingAndLogoutErasesTheCookies() = runTest {
        val tracker = tracker()
        tracker.importSteamSession(session(alice))
        val before = platform.steamCookies.readSteamCookies()

        tracker.checkSteamSession()
        tracker.setForeground(true)
        tracker.checkSteamSession()

        assertEquals(before, platform.steamCookies.readSteamCookies())
        tracker.onLogout()
        assertEquals(emptyList(), platform.steamCookies.readSteamCookies())
    }

    /** A heartbeat for alice leasing one create (to bob) and one cancel of an offer already sent. */
    private fun leaseWrites() {
        marketplace.heartbeatResponse = HeartbeatResponse(
            ttlSeconds = 60,
            linkedSteamId = alice,
            directives = listOf(
                Directive(DirectiveId("create-1"), DirectiveAction.CREATE_OFFER, DealId("d2"), bob, listOf(AssetId("a-1")), "tok"),
                Directive(DirectiveId("cancel-1"), DirectiveAction.CANCEL_OFFER, DealId("d3"), steamOfferId = OfferId("7002")),
            ),
        )
    }

    private val steamWrites get() = steamRequests.filter { it.method == HttpMethod.Post && it.url.host == "steamcommunity.com" }

    @Test
    fun leasedWritesGoToSteamOnTheVaultSessionAndReportTheirOutcomes() = runTest {
        leaseWrites()
        val tracker = tracker(steamWrites = true)
        tracker.importSteamSession(session(alice))

        tracker.onPush(push)

        assertEquals(
            setOf("/tradeoffer/new/send", "/tradeoffer/7002/cancel"),
            steamWrites.map { it.url.encodedPath }.toSet(),
        )
        for (write in steamWrites) {
            val cookies = write.headers.getAll("Cookie").orEmpty().flatMap { it.split("; ") }.map { it.substringBefore('=') }
            assertEquals(listOf("steamLoginSecure", "sessionid"), cookies, "the vault's session, each cookie once")
        }
        val outcomes = marketplace.directiveOutcomes.associate { it.directiveId.value to it.status }
        assertEquals(DirectiveStatus.NEEDS_CONFIRMATION, outcomes["create-1"])
        assertEquals(DirectiveStatus.SUCCESS, outcomes["cancel-1"])
    }

    @Test
    fun offerWritesAreOffByDefault() = runTest {
        leaseWrites()
        val tracker = tracker()
        tracker.importSteamSession(session(alice))

        tracker.onPush(push)
        val delivery = tracker.startDelivery(DealId("d2"))

        assertTrue(steamRequests.isNotEmpty(), "the session is alive and Steam is read")
        assertEquals(emptyList(), steamWrites.map { it.url.encodedPath })
        assertTrue(marketplace.directiveOutcomes.none { it.directiveId.value in setOf("create-1", "cancel-1") })
        assertIs<CreateOfferResult.Failed>(delivery.result)
    }

    @Test
    fun noSessionSendsNoWrite() = runTest {
        leaseWrites()
        val tracker = tracker(steamWrites = true)

        tracker.onPush(push)
        tracker.startDelivery(DealId("d2"))

        assertEquals(SteamWebSessionState.GONE, tracker.state.value.steamSession)
        assertEquals(emptyList(), steamWrites.map { it.url.encodedPath })
    }

    @Test
    fun anotherAccountsSessionSendsNoWrite() = runTest {
        val tracker = tracker(steamWrites = true)
        tracker.importSteamSession(session(alice))
        tracker.onPush(push) // the heartbeat names alice as linked
        tracker.importSteamSession(session(bob))
        leaseWrites()
        steamRequests.clear()

        tracker.onPush(push)
        val delivery = tracker.startDelivery(DealId("d2"))

        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, tracker.state.value.steamSession)
        assertEquals(emptyList(), steamRequests.map { "${it.method.value} ${it.url.host}${it.url.encodedPath}" })
        assertEquals(CreateOfferResult.AccountMismatch(alice, bob), delivery.result)
    }

    @Test
    fun anImportBeforeTheTrackerIsBuiltIsReadByTheFirstCycleAfterTheStart() = runTest {
        val mobile = mobile()
        platform.vault.writeSteamCredential(SteamCredential("old-token", alice, clock.now() + 20.hours))

        assertNull(mobile.importSteamSession(session(alice)), "no tracker: the vault is written, no state to give")
        assertEquals(
            setOf("steamcommunity.com", "store.steampowered.com", "login.steampowered.com"),
            platform.steamCookies.readSteamCookies().map {
                it.domain
            }.toSet(),
        )
        assertNull(platform.vault.readSteamCredential(), "the previous session's token is dropped")

        val tracker = tracker(mobile = mobile)
        assertTrue(tracker.start())
        assertNull(tracker.state.value.steamSession, "running, not read yet, nothing blocks")
        tracker.onPush(push)

        assertEquals(SteamWebSessionState.ALIVE, tracker.state.value.steamSession)
        assertEquals(TrackerBlock.NONE, tracker.state.value.block)
        assertEquals(SteamWebSessionState.ALIVE, marketplace.heartbeatsSent.last().steamSession)
    }

    @Test
    fun anImportOfAnotherAccountBeforeTheStartSendsNoSteamRequestOnTheFirstCycle() = runTest {
        val mobile = mobile()
        mobile.importSteamSession(session(bob))
        val tracker = tracker(mobile = mobile)

        tracker.onPush(push)

        assertEquals(emptyList(), steamRequests.map { "${it.url.host}${it.url.encodedPath}" })
        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, tracker.state.value.steamSession)
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, tracker.state.value.block)
        assertEquals(bob, marketplace.heartbeatsSent.single().steamId)
        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, marketplace.heartbeatsSent.single().steamSession)
    }

    @Test
    fun logoutErasesASessionImportedBeforeTheStart() = runTest {
        val mobile = mobile()
        mobile.importSteamSession(session(alice))
        val tracker = tracker(mobile = mobile)

        tracker.onLogout()

        assertEquals(emptyList(), platform.steamCookies.readSteamCookies())
    }

    @Test
    fun anotherLinkedAccountKeepsTheVaultAndTheGuardReadsOtherAccount() = runTest {
        val tracker = tracker()
        tracker.importSteamSession(session(alice))
        tracker.onPush(push)
        assertEquals(SteamWebSessionState.ALIVE, tracker.state.value.steamSession)
        val vault = platform.steamCookies.readSteamCookies()

        // The DMarket user now links bob: his loop opens his own stores, the Steam session stays alice's.
        linked = bob
        marketplace.heartbeatResponse = HeartbeatResponse(ttlSeconds = 60, linkedSteamId = bob)
        steamRequests.clear()
        assertTrue(tracker.start())
        tracker.onPush(push)

        assertEquals(bob, tracker.state.value.account)
        assertEquals(vault, platform.steamCookies.readSteamCookies(), "storageFor another account leaves the vault")
        assertEquals(SteamWebSessionState.OTHER_ACCOUNT, tracker.state.value.steamSession)
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, tracker.state.value.block)
        assertEquals(emptyList(), steamRequests.map { "${it.url.host}${it.url.encodedPath}" })
    }
}
