package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.steam.CancelOfferResult
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceAuthenticator
import com.dmarket.p2p.tracker.support.FakeNotaryProver
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * [LoopConfig.reportEachWrite] (mobile): every `create_offer` / `cancel_offer` gets its own `/trade-actions` call right
 * after Steam answers, before the next write and before any proof; web and headless keep one batch per cycle.
 *
 * The DMarket side is the real [KtorMarketplaceClient] over a [MockEngine], so the order asserted is the order of the
 * requests on the wire. Steam writes and the notary are fakes that write into the same log.
 */
class PerWriteReportingTest {

    private val partner = "76561198000000002"

    /** Every Steam write and every DMarket POST other than the heartbeat, in the order they happened. */
    private val log = mutableListOf<String>()

    /** Directive ids `/trade-actions` leaves out of its answer, as a partial answer would. */
    private var unacked = emptySet<String>()

    /** Directive ids `/trade-actions` answers `accepted: false` for. */
    private var rejected = emptySet<String>()

    private val observer = RecordingEventObserver()

    /** How many of the next `/trade-actions` calls throw, as a dropped connection does. */
    private var tradeActionsFailures = 0

    private var directives = ""

    private val creator = object : SteamOfferCreator {
        override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult {
            val asset = draft.assetsToGive.single().value
            log += "create $asset"
            return CreateOfferResult.NeedsConfirmation(OfferId("offer-$asset"))
        }
    }

    private val canceller = object : SteamOfferCanceller {
        override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): CancelOfferResult {
            log += "cancel ${offerId.value}"
            return CancelOfferResult.Cancelled
        }
    }

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath.substringAfterLast('/')
        val body = (request.body as? TextContent)?.text.orEmpty()
        when (path) {
            "heartbeat" -> respond(
                """{"activeTracking":[{"dealId":"deal-p","steamOfferId":"offer-p","proofRequired":true,"watch":["GetTradeOffer"]}],
                   "directives":[$directives],"ttlSeconds":60}""",
                headers = json,
            )
            "trade-actions" -> {
                val ids = Json.parseToJsonElement(body).jsonObject.getValue("reports").jsonArray
                    .map { it.jsonObject.getValue("directiveId").jsonPrimitive.content }
                log += "trade-actions ${ids.joinToString(",")}"
                if (tradeActionsFailures-- > 0) error("connection reset")
                val results = ids.filterNot { it in unacked }
                    .joinToString(",") { """{"directiveId":"$it","accepted":${it !in rejected},"reason":"deal closed"}""" }
                respond("""{"results":[$results]}""", headers = json)
            }
            "notary" -> {
                log += "notary"
                respond("""{"dealId":"deal-p","verified":true}""", headers = json)
            }
            "trade-events" -> respond("""{"results":[{"dealId":"deal-p","accepted":true}]}""", headers = json)
            else -> respond("{}", headers = json)
        }
    }

    private fun create(tag: String) = """{"directiveId":"$tag","action":"create_offer","dealId":"deal-$tag","partnerSteamId":"$partner",
           "assetIds":["asset-$tag"],"tradeToken":"tok","contextId":2}"""

    private fun cancel(tag: String) = """{"directiveId":"$tag","action":"cancel_offer","dealId":"deal-$tag","steamOfferId":"offer-$tag"}"""

    private val progress = InMemoryTrackerProgressStore()
    private val clock = FakeClock()

    private fun loop(reportEachWrite: Boolean) = TradeTrackerLoop(
        config = LoopConfig("1.0.0", RuntimeSurface.WebChrome, TrackerMode.Background, reportEachWrite = reportEachWrite),
        marketplace = KtorMarketplaceClient(
            createHttpClient(engine),
            "https://gateway.dmarket.com",
            FakeMarketplaceAuthenticator(token = "t"),
            MarketplaceRetryConfig(retryBaseDelayMs = 1, retryMaxDelayMs = 1),
        ),
        steamReader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-p") to 3)),
        credentials = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = fakeSteamCredential()),
            scraper = FakeSteamSessionScraper(result = fakeSteamCredential()),
            clock = clock,
        ),
        scheduler = FakeScheduler(),
        clock = clock,
        deviceId = FakeDeviceIdStore(),
        offerCreator = creator,
        offerCanceller = canceller,
        notary = FakeNotaryProver(),
        progress = progress,
        directivesEnabled = true,
        eventObserver = observer,
    )

    @Test
    fun each_write_is_reported_on_its_own_right_after_steam_answers_and_before_any_proof() = runTest {
        directives = listOf(create("a-1"), create("a-2"), cancel("c-1")).joinToString(",")

        val outcome = loop(reportEachWrite = true).runOnce()

        assertEquals(
            listOf(
                "create asset-a-1",
                "trade-actions a-1",
                "create asset-a-2",
                "trade-actions a-2",
                "cancel offer-c-1",
                "trade-actions c-1",
            ),
            log.takeWhile { it != "notary" },
        )
        assertEquals("notary", log.last(), "the proof comes after every write's report: $log")
        assertEquals(3, outcome.directivesExecuted, "an accepted write counts once, though it left the batch")
        assertTrue(progress.loadDirectiveOutcomes().isEmpty(), "each accepted outcome is pruned")
    }

    @Test
    fun without_the_flag_a_cycle_still_sends_one_batch() = runTest {
        directives = listOf(create("a-1"), create("a-2"), cancel("c-1")).joinToString(",")

        val outcome = loop(reportEachWrite = false).runOnce()

        assertEquals(
            listOf("create asset-a-1", "create asset-a-2", "cancel offer-c-1", "trade-actions a-1,a-2,c-1", "notary"),
            log,
        )
        assertEquals(3, outcome.directivesExecuted)
    }

    @Test
    fun a_report_lost_to_the_network_stays_stored_and_goes_with_the_next_cycle() = runTest {
        directives = create("a-1")
        tradeActionsFailures = 2
        val loop = loop(reportEachWrite = true)

        loop.runOnce()

        assertEquals(
            listOf("create asset-a-1", "trade-actions a-1", "trade-actions a-1"),
            log.filter { it != "notary" },
            "its own call, then the cycle's batch once more",
        )
        assertEquals(setOf(DirectiveId("a-1")), progress.loadDirectiveOutcomes().keys, "the outcome is kept")

        // The backend re-leases it: the stored outcome is sent, the offer is not created again.
        log.clear()
        clock.advance(1.minutes)
        loop.forceHeartbeatNow()
        loop.runOnce()

        assertEquals(listOf("trade-actions a-1"), log.filter { it != "notary" })
        assertTrue(progress.loadDirectiveOutcomes().isEmpty(), "pruned once accepted")
    }

    @Test
    fun an_outcome_left_out_of_its_own_answer_is_retried_in_the_batch_and_then_on_the_re_lease() = runTest {
        directives = listOf(create("a-1"), create("a-2")).joinToString(",")
        unacked = setOf("a-2")
        val loop = loop(reportEachWrite = true)

        val outcome = loop.runOnce()

        assertEquals(
            listOf("create asset-a-1", "trade-actions a-1", "create asset-a-2", "trade-actions a-2", "trade-actions a-2"),
            log.filter { it != "notary" },
            "the accepted a-1 is not sent again; the unanswered a-2 goes in the batch",
        )
        assertEquals(2, outcome.directivesExecuted, "both writes reached Steam")
        assertEquals(setOf(DirectiveId("a-2")), progress.loadDirectiveOutcomes().keys)

        // The backend re-leases only what it never accepted.
        log.clear()
        unacked = emptySet()
        directives = create("a-2")
        clock.advance(1.minutes)
        loop.forceHeartbeatNow()
        loop.runOnce()

        assertEquals(listOf("trade-actions a-2"), log.filter { it != "notary" }, "re-leased: resent, nothing re-created")
        assertTrue(progress.loadDirectiveOutcomes().isEmpty())
    }

    @Test
    fun a_report_that_fails_alone_but_lands_in_the_batch_is_pruned_in_the_same_cycle() = runTest {
        directives = create("a-1")
        tradeActionsFailures = 1

        val outcome = loop(reportEachWrite = true).runOnce()

        assertEquals(listOf("create asset-a-1", "trade-actions a-1", "trade-actions a-1"), log.filter { it != "notary" })
        assertEquals(1, outcome.directivesExecuted)
        assertTrue(progress.loadDirectiveOutcomes().isEmpty(), "accepted in the batch, so pruned")
    }

    @Test
    fun a_rejected_report_goes_once_per_cycle_and_waits_for_the_re_lease() = runTest {
        directives = create("a-1")
        rejected = setOf("a-1")

        val outcome = loop(reportEachWrite = true).runOnce()

        assertEquals(listOf("create asset-a-1", "trade-actions a-1"), log.filter { it != "notary" })
        assertEquals(1, observer.events.filterIsInstance<LifecycleEvent.DirectiveReportFailed>().size)
        assertEquals(1, outcome.directivesExecuted, "the write reached Steam")
        assertEquals(setOf(DirectiveId("a-1")), progress.loadDirectiveOutcomes().keys, "kept for the re-lease")
    }
}
