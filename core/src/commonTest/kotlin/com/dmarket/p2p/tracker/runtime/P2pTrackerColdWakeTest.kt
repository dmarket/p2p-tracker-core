package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.MonotonicElapsedClock
import com.dmarket.p2p.tracker.adapter.host.InMemoryCredentialVault
import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.MarketplaceTokenSupplier
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.WakeCause
import com.dmarket.p2p.tracker.model.WakeOutcome
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.model.steam.SteamCookie
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.ElapsedClock
import com.dmarket.p2p.tracker.port.host.InstallMarker
import com.dmarket.p2p.tracker.port.host.NetworkConditions
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.WakeAcknowledger
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.delivered
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The seller's background duties on a wake (DMA-664 1/3), each run by a process that starts cold: a new
 * [MobileTracker] and [P2pTracker] over the same device storage, as after the OS killed the app. Built only through
 * [MobileTracker.tracker] with Steam writes on, so the lease fence, the bind read and the per-write report are the
 * mobile ones; Steam is a [MockEngine], DMarket a fake that also takes the wake acks.
 */
class P2pTrackerColdWakeTest {
    private val clock = FakeClock()
    private val alice = SteamId("76561198000000001")
    private val bob = SteamId("76561198000000002")
    private val deal = DealId("d1")
    private val events = RecordingEventObserver()

    /** What survives a process: the platform's stores and its Steam requests log. */
    private val install = InMemoryDeviceKeyValueStore()
    private val sealed = InMemoryDeviceKeyValueStore()
    private val credentials = InMemoryCredentialVault()
    private val accountEntries = mutableMapOf<String, String>()
    private var installMarked = false

    /** Every request the Steam client sent, across processes. */
    private val steamRequests = mutableListOf<HttpRequestData>()

    /** The `status` GetTradeHistory/GetTradeStatus answer for [trade]; `null` lists no row. */
    private var historyStatus: Int? = null
    private val trade = TradeId("744935517744884653")

    /** Offers this account has sent and Steam lists as active: what a bind read finds. */
    private val sentOffers = mutableListOf<String>()

    /** Where a create POST stops answering: before Steam makes the offer, after it, or not at all. */
    private enum class Hang { NONE, BEFORE_THE_OFFER, AFTER_THE_OFFER }

    private var createHang = Hang.NONE

    /** DMarket: the fake, plus the wake acks the core sends through it. */
    private class Backend(val fake: FakeMarketplaceClient) :
        MarketplaceClient by fake,
        WakeAcknowledger {
        val acks = mutableListOf<Pair<String, WakeOutcome>>()

        override suspend fun acknowledge(deviceId: DeviceId, wakeId: String, outcome: WakeOutcome) {
            acks += wakeId to outcome
        }
    }

    private val backend = Backend(FakeMarketplaceClient(HeartbeatResponse(ttlSeconds = 86_400, linkedSteamId = alice)))
    private val marketplace get() = backend.fake

    private inner class Platform(val testDispatcher: kotlinx.coroutines.CoroutineDispatcher) :
        MobileTrackerPlatform(MarketplaceTokenSupplier { fakeMarketplaceCredential("dm-token") }) {
        override val clock: Clock = this@P2pTrackerColdWakeTest.clock

        // The Android actual reads android.os.SystemClock, which the host tests do not stub. Virtual time does not move
        // it, so every lease window here is still whole when its write starts.
        override val elapsedClock: ElapsedClock = MonotonicElapsedClock()
        override val surface: RuntimeSurface = RuntimeSurface.AndroidNative
        override val network: NetworkConditions = NetworkConditions.Unmetered
        override val installStore: DeviceKeyValueStore = install
        override val sealedStore: DeviceKeyValueStore = sealed
        override val sealedVault: CredentialVault = credentials
        override val accountStore: DeviceKeyValueStore = object : DeviceKeyValueStore {
            override suspend fun get(key: String): String? = accountEntries[key]

            override suspend fun set(key: String, value: String) {
                accountEntries[key] = value
            }

            override suspend fun remove(key: String) {
                accountEntries.remove(key)
            }
        }

        // Same install across processes: a marker that answered false would erase the account store on the restart.
        override val installMarker: InstallMarker = object : InstallMarker {
            override suspend fun isSameInstall() = installMarked

            override suspend fun markInstall() {
                installMarked = true
            }
        }

        override fun dmarketEngine(): HttpClientEngine = MockEngine { respond("", HttpStatusCode.InternalServerError) }

        // On the test's scheduler: a real-thread engine would let the cycle deadline's virtual clock run out under it.
        override fun steamEngine(): HttpClientEngine = MockEngine(
            MockEngineConfig().apply {
                dispatcher = testDispatcher
                addHandler { request -> steam(request) }
            },
        )

        override suspend fun clearAccountStore() = accountEntries.clear()
    }

    private suspend fun MockRequestHandleScope.steam(request: HttpRequestData): HttpResponseData {
        steamRequests += request
        val owner = request.headers["Cookie"].orEmpty().substringAfter("steamLoginSecure=", "").substringBefore("%7C%7C")
        val community = request.url.host == "steamcommunity.com" && owner.isNotEmpty()
        val path = request.url.encodedPath
        val endpoints = SteamEndpointsConfig()
        return when {
            community && path.trimEnd('/').isEmpty() -> {
                val token = steamAccessToken(owner, clock.now() + 20.hours)
                respond("""<div data-loyalty_webapi_token="&quot;$token&quot;"></div><script>var g_steamID = "$owner";</script>""")
            }
            path == endpoints.getTradeOffersPath -> {
                // bob as accountid_other: his steamid64 less 76561197960265728.
                val offers = sentOffers.joinToString(",") {
                    """{"tradeofferid":"$it","accountid_other":39734274,"trade_offer_state":9,"time_created":1792420000,
                       "time_updated":1792420000,"items_to_give":[{"appid":730,"contextid":"2","assetid":"a-1"}]}"""
                }
                respond("""{"response":{"trade_offers_sent":[$offers]}}""")
            }
            path == endpoints.getTradeHistoryPath || path == endpoints.getTradeStatusPath -> {
                val row = historyStatus?.let {
                    """{"tradeid":"${trade.value}","steamid_other":"${bob.value}","time_init":1785929600,"status":$it,
                       "assets_given":[{"appid":730,"contextid":"2","assetid":"a-1"}]}"""
                }
                respond("""{"response":{"trades":[${row.orEmpty()}]}}""")
            }
            community && path == "/tradeoffer/new/send" -> {
                if (createHang == Hang.BEFORE_THE_OFFER) awaitCancellation()
                sentOffers += "8001"
                if (createHang == Hang.AFTER_THE_OFFER) awaitCancellation()
                respond("""{"tradeofferid":"8001","needs_mobile_confirmation":true}""")
            }
            community && path.endsWith("/cancel") -> respond("""{"success":1}""")
            else -> respond("", HttpStatusCode.InternalServerError)
        }
    }

    /** One process: a cold [MobileTracker] over the device's storage, its tracker started by the first call. */
    private class Process(val mobile: MobileTracker, val tracker: P2pTracker)

    private fun TestScope.process(reportUnsentCreates: Boolean = true): Process {
        val mobile = MobileTracker(
            Platform(StandardTestDispatcher(testScheduler)),
            linkedAccount = { alice },
            cellularProofs = { false },
            hostMarketplaceClient = MarketplaceClientFactory { _, _, _, _ -> backend },
            steamWrites = true,
            reportUnsentCreates = reportUnsentCreates,
        )
        return Process(mobile, mobile.tracker(backgroundScope, "https://api.test", TrackerConfig.defaults(), NoOpNetworkObserver, events))
    }

    private fun loginSecure(owner: SteamId) = "${owner.value}%7C%7C${steamAccessToken(owner.value, clock.now() + 20.hours)}"

    private fun session(owner: SteamId = alice) = listOf(
        SteamCookie("steamLoginSecure", loginSecure(owner), "steamcommunity.com"),
        SteamCookie("steamLoginSecure", loginSecure(owner), "store.steampowered.com"),
        SteamCookie("sessionid", "sid", "steamcommunity.com"),
        SteamCookie("steamRefresh_steam", "refresh", "login.steampowered.com"),
    )

    /** The app's Steam sign-in in an earlier process; only the device's storage keeps it. */
    private suspend fun TestScope.signedInEarlier() {
        process().mobile.importSteamSession(session())
    }

    private fun wake(reason: String, id: String = "w-1") =
        """{"v":"1","type":"wake_deal","wakeId":"$id","transactionId":"tx-1","reason":"$reason","issuedAt":"2026-11-24T10:00:00Z"}"""

    private val steamWrites get() = steamRequests.filter { it.method == HttpMethod.Post && it.url.host == "steamcommunity.com" }
    private val createPosts get() = steamWrites.count { it.url.encodedPath == "/tradeoffer/new/send" }
    private val historyReads get() = steamRequests.count { it.url.encodedPath == SteamEndpointsConfig().getTradeHistoryPath }

    private fun cancel(id: String = "cancel-1") =
        Directive(DirectiveId(id), DirectiveAction.CANCEL_OFFER, deal, steamOfferId = OfferId("7002"))

    private fun create(id: String) = Directive(DirectiveId(id), DirectiveAction.CREATE_OFFER, deal, bob, listOf(AssetId("a-1")), "tok")

    private fun lease(vararg directives: Directive, tracking: List<TrackedDeal> = listOf(sellerDeal())) {
        marketplace.heartbeatResponse = HeartbeatResponse(
            ttlSeconds = 86_400,
            linkedSteamId = alice,
            activeTracking = tracking,
            directives = directives.toList(),
        )
    }

    private fun sellerDeal(watch: Set<WatchTarget> = emptySet(), offer: OfferId? = null, tradeId: TradeId? = null) =
        TrackedDeal(dealId = deal, steamOfferId = offer, watch = watch, role = DealRole.SELLER, steamTradeId = tradeId)

    private fun resent(id: String) = events.events.filterIsInstance<LifecycleEvent.DirectiveOutcomeResent>().filter { it.directiveId == id }

    // ---- cancel_offer (W5, the 18h deadline) ------------------------------------------------------------------------

    @Test
    fun a_cold_cancel_ask_wake_cancels_the_leased_offer_on_the_stored_session_and_reports_it() = runTest {
        signedInEarlier()
        lease(cancel())

        val outcome = process().tracker.onPush(wake("cancel_ask"))

        assertEquals(WakeOutcome.DONE, outcome)
        assertEquals(listOf("/tradeoffer/7002/cancel"), steamWrites.map { it.url.encodedPath }, "on the session kept on disk")
        assertEquals(DirectiveStatus.SUCCESS, marketplace.directiveOutcomes.single { it.directiveId.value == "cancel-1" }.status)
        assertEquals(listOf("w-1" to WakeOutcome.DONE, "w-1" to WakeOutcome.DONE), backend.acks)
    }

    @Test
    fun a_cancel_whose_report_was_lost_is_resent_by_the_next_cold_wake_without_a_second_cancel() = runTest {
        signedInEarlier()
        lease(cancel())
        marketplace.reportDirectiveThrows = true
        process().tracker.onPush(wake("cancel_ask"))
        assertEquals(1, steamWrites.size)

        marketplace.reportDirectiveThrows = false
        process().tracker.onPush(wake("cancel_ask", id = "w-2"))

        assertEquals(1, steamWrites.size, "the cancel is not run again")
        assertTrue(resent("cancel-1").single().accepted, "the stored outcome went out and was accepted")
    }

    @Test
    fun a_cancel_waiting_for_a_steam_session_stays_unanswered_and_runs_on_the_sync_after_the_sign_in() = runTest {
        lease(cancel())
        val process = process()

        assertEquals(WakeOutcome.failed(WakeCause.STEAM_SESSION), process.tracker.onPush(wake("cancel_ask")))

        assertTrue(marketplace.heartbeatsSent.isEmpty(), "mobile sends no heartbeat without a Steam session")
        assertTrue(steamWrites.isEmpty())
        assertTrue(marketplace.directiveOutcomes.isEmpty(), "the directive is left unanswered, never failed")
        assertTrue(events.events.none { it is LifecycleEvent.DirectiveDropped }, "the cancel was never served to drop")
        assertEquals(WakeOutcome.failed(WakeCause.STEAM_SESSION), backend.acks.last().second)

        process.mobile.importSteamSession(session())
        assertEquals(WakeOutcome.DONE, process.tracker.onPush(wake("cancel_ask", id = "w-2")))

        assertEquals(listOf("/tradeoffer/7002/cancel"), steamWrites.map { it.url.encodedPath })
        assertEquals(DirectiveStatus.SUCCESS, marketplace.directiveOutcomes.single().status)
    }

    // ---- trade protection: a reversal during the hold ---------------------------------------------------------------

    @Test
    fun a_wake_on_a_deal_in_hold_reads_history_once_the_revert_watch_hour_is_up_and_reports_12() = runTest {
        signedInEarlier()
        lease(tracking = listOf(sellerDeal(watch = setOf(WatchTarget.GET_TRADE_HISTORY), tradeId = trade)))
        historyStatus = 3
        // The first wake sees the completed trade: the baseline and the revert-watch time go to disk.
        process().tracker.onPush(wake("watch"))
        assertEquals(1, historyReads)
        assertEquals(listOf(3), marketplace.tradeStatusReports.filter { it.source == TradeStatusSource.HISTORY }.map { it.steamStatusCode })

        // Inside the hour a cold wake reads no history: only the stored baseline and time say so.
        historyStatus = 12
        clock.advance(59.minutes)
        process().tracker.onPush(wake("watch", id = "w-2"))
        assertEquals(1, historyReads, "the revert watch is hourly")
        assertEquals(listOf(3), marketplace.tradeStatusReports.filter { it.source == TradeStatusSource.HISTORY }.map { it.steamStatusCode })

        clock.advance(2.minutes)
        assertEquals(WakeOutcome.DONE, process().tracker.onPush(wake("watch", id = "w-3")))
        assertEquals(2, historyReads)
        assertEquals(
            listOf(3, 12),
            marketplace.tradeStatusReports.filter { it.source == TradeStatusSource.HISTORY }.map { it.steamStatusCode },
        )
    }

    // ---- create_offer: the outcome kept until /trade-actions takes it -----------------------------------------------

    /** A first process creates the offer and loses its report. */
    private suspend fun TestScope.createdAndReportLost() {
        signedInEarlier()
        lease(create("dir-1"))
        marketplace.reportDirectiveThrows = true
        process().tracker.onPush(wake("directive"))
        assertEquals(1, createPosts)
        marketplace.reportDirectiveThrows = false
    }

    @Test
    fun an_unreported_create_is_resent_under_the_same_id_by_the_next_cold_wake() = runTest {
        createdAndReportLost()

        process().tracker.onPush(wake("directive", id = "w-2"))

        assertEquals(1, createPosts, "no second offer")
        assertTrue(resent("dir-1").single().accepted)
        assertEquals(OfferId("8001"), marketplace.directiveOutcomes.last { it.directiveId.value == "dir-1" }.steamOfferId)
    }

    @Test
    fun a_fresh_id_for_the_created_deal_is_answered_from_the_stored_claim_while_it_holds() = runTest {
        createdAndReportLost()
        sentOffers.clear() // Steam's list shows nothing: only the claim on disk can stop a second offer

        clock.advance(5.minutes)
        lease(create("dir-2"))
        process().tracker.onPush(wake("directive", id = "w-2"))

        assertEquals(1, createPosts, "no second offer")
        val restated = marketplace.directiveOutcomes.single { it.directiveId.value == "dir-2" }
        assertEquals(OfferId("8001"), restated.steamOfferId, "the offer the first process made")
    }

    @Test
    fun a_fresh_id_after_the_claim_expired_binds_the_offer_steam_still_lists() = runTest {
        createdAndReportLost()

        // W8 past the claim's 15 min: the bind read finds the first process's offer on Steam.
        clock.advance(16.minutes)
        lease(create("dir-2"))
        process().tracker.onPush(wake("directive", id = "w-2"))

        assertEquals(1, createPosts, "no second offer")
        val bound = marketplace.directiveOutcomes.single { it.directiveId.value == "dir-2" }
        assertEquals(OfferId("8001"), bound.steamOfferId)
        assertEquals(DirectiveStatus.NEEDS_CONFIRMATION, bound.status)
    }

    // ---- exactly once: Create trade racing a wake ------------------------------------------------------------------

    private suspend fun TestScope.deliveryRacingAWake(wakeFirst: Boolean) {
        signedInEarlier()
        marketplace.deliveries = listOf(delivered(create("dir-1")))
        marketplace.startDeliveryDelay = 1.seconds
        lease(create("dir-2"))
        val tracker = process().tracker

        val first = async { if (wakeFirst) tracker.onPush(wake("directive")) else tracker.startDelivery(deal) }
        runCurrent()
        val second = async { if (wakeFirst) tracker.startDelivery(deal) else tracker.onPush(wake("directive")) }
        advanceTimeBy(10.seconds)
        runCurrent()
        val delivery = (if (wakeFirst) second.await() else first.await()) as DeliveryOutcome

        assertEquals(1, createPosts, "one Steam offer for the deal")
        val offer = when (val result = delivery.result) {
            is CreateOfferResult.NeedsConfirmation -> result.offerId
            is CreateOfferResult.AlreadyCreated -> result.offerId
            else -> null
        }
        assertEquals(OfferId("8001"), offer, "the delivery answers the one offer: ${delivery.result}")
        assertEquals("w-1", marketplace.heartbeatsSent.first { it.wakeId != null }.wakeId, "the wake's sync ran")
        assertTrue(marketplace.directiveOutcomes.isNotEmpty())
        assertTrue(marketplace.directiveOutcomes.all { it.steamOfferId == OfferId("8001") }, "${marketplace.directiveOutcomes}")
    }

    @Test
    fun a_delivery_started_before_a_cold_wake_leasing_the_same_create_posts_one_offer() = runTest {
        deliveryRacingAWake(wakeFirst = false)
    }

    @Test
    fun a_cold_wake_leasing_a_create_before_the_delivery_posts_one_offer() = runTest {
        deliveryRacingAWake(wakeFirst = true)
    }

    // ---- a create the wake cut short is not the seller's fault -------------------------------------------------------

    /** A wake whose create Steam does not answer before the wake's budget runs out. */
    private suspend fun TestScope.createCutShort(hang: Hang) {
        signedInEarlier()
        lease(create("dir-1"))
        createHang = hang

        val outcome = process(reportUnsentCreates = false).tracker.onPush(wake("directive"), deadline = 20.seconds)

        assertEquals(WakeOutcome.failed(WakeCause.BUDGET), outcome)
        assertEquals(1, createPosts, "the create reached Steam")
        assertTrue(marketplace.directiveOutcomes.isEmpty(), "nothing is known, so nothing is reported: ${marketplace.directiveOutcomes}")
        createHang = Hang.NONE
    }

    @Test
    fun a_create_cut_short_after_steam_made_the_offer_is_reported_with_it_by_the_next_cold_wake() = runTest {
        createCutShort(Hang.AFTER_THE_OFFER)

        // Steam's list does not show the offer yet: only the claim kept on disk holds the deal.
        sentOffers.clear()
        process(reportUnsentCreates = false).tracker.onPush(wake("directive", id = "w-2"))
        assertEquals(1, createPosts, "held within the grace: no second offer")
        assertTrue(marketplace.directiveOutcomes.isEmpty())

        sentOffers += "8001"
        clock.advance(1.minutes)
        process(reportUnsentCreates = false).tracker.onPush(wake("directive", id = "w-3"))

        assertEquals(1, createPosts)
        val reported = marketplace.directiveOutcomes.single { it.directiveId.value == "dir-1" }
        assertEquals(OfferId("8001"), reported.steamOfferId)
        assertEquals(DirectiveStatus.NEEDS_CONFIRMATION, reported.status)
    }

    @Test
    fun a_create_cut_short_before_steam_made_the_offer_is_made_again_after_the_grace_and_never_reported_failed() = runTest {
        createCutShort(Hang.BEFORE_THE_OFFER)

        clock.advance(3.minutes) // past the 2 min grace, inside the claim's 15 min
        process(reportUnsentCreates = false).tracker.onPush(wake("directive", id = "w-2"))

        assertEquals(2, createPosts, "no offer on Steam: the re-served create runs again")
        assertTrue(marketplace.directiveOutcomes.none { it.status == DirectiveStatus.FAILED }, "${marketplace.directiveOutcomes}")
        assertEquals(OfferId("8001"), marketplace.directiveOutcomes.single { it.directiveId.value == "dir-1" }.steamOfferId)
    }
}
