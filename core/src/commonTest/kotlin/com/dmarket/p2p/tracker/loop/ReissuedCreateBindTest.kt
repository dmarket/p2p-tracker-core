package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.steam.KtorSteamReadClient
import com.dmarket.p2p.tracker.config.MarketplaceRetryConfig
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.host.ElapsedClock
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceAuthenticator
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Bind-or-create (DMA-662 Q14): a `create_offer` the backend serves again after an earlier lease first reads the
 * account's sent offers and reports the offer an earlier lease made, instead of creating a second one. Steam and the
 * backend are both a [MockEngine]; the creator is a fake that logs its POST.
 */
class ReissuedCreateBindTest {

    private val partner = "76561198000000002"

    /** [partner] as `accountid_other`: the steamid64 less 76561197960265728. */
    private val partnerAccountId = 39734274

    private val endpoints = SteamEndpointsConfig()

    /** Every `GetTradeOffers` read, Steam create and `/trade-actions` report, in order. */
    private val log = mutableListOf<String>()

    /** The query of each `GetTradeOffers` read. */
    private val reads = mutableListOf<Map<String, String>>()

    /** The `steamOfferId`/`status` of every report sent on `/trade-actions`. */
    private val reported = mutableListOf<String>()

    /** The sent offers `GetTradeOffers` lists; each one `"<state>:<partner account id>:<asset>,<asset>"`. */
    private var sentOffers = listOf<String>()

    /** How many `GetTradeOffers` reads still fail with a 500. */
    private var readFailures = 0

    private var directive = ""

    private val elapsed = object : ElapsedClock {
        var now = 10.hours
        override fun elapsed(): Duration = now
    }

    private val wall = FakeClock()

    private val creator = object : SteamOfferCreator {
        override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult {
            log += "create"
            return CreateOfferResult.NeedsConfirmation(OfferId("new-offer"))
        }
    }

    private val json = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    private val engine = MockEngine { request ->
        val path = request.url.encodedPath
        when {
            path == endpoints.getTradeOffersPath -> {
                log += "read"
                reads += request.url.parameters.entries().associate { it.key to it.value.first() }
                if (readFailures-- > 0) return@MockEngine respond("", HttpStatusCode.InternalServerError)
                val offers = sentOffers.mapIndexed { i, spec ->
                    val (state, account, assets) = spec.split(":")
                    val items = assets.split(",").joinToString(",") { """{"appid":730,"contextid":"2","assetid":"$it"}""" }
                    """{"tradeofferid":"bound-$i","accountid_other":$account,"trade_offer_state":$state,
                       "time_created":1792420000,"time_updated":1792420000,"items_to_give":[$items]}"""
                }
                respond("""{"response":{"trade_offers_sent":[${offers.joinToString(",")}]}}""", headers = json)
            }
            path.endsWith("/heartbeat") ->
                respond("""{"activeTracking":[],"directives":[$directive],"ttlSeconds":60}""", headers = json)
            path.endsWith("/trade-actions") -> {
                val body = (request.body as TextContent).text
                val reports = Json.parseToJsonElement(body).jsonObject.getValue("reports").jsonArray.map { it.jsonObject }
                reports.forEach {
                    reported += "${it["steamOfferId"]?.jsonPrimitive?.content}/${it.getValue("status").jsonPrimitive.content}"
                }
                log += "report"
                val results = reports.joinToString(",") { """{"directiveId":${it.getValue("directiveId")},"accepted":true}""" }
                respond("""{"results":[$results]}""", headers = json)
            }
            else -> respond("{}", headers = json)
        }
    }

    /** A `create_offer` for [partner] giving `a1` and `a2`, with [extra] JSON fields. */
    private fun create(extra: String = "") = """{"directiveId":"dir-1","action":"create_offer","dealId":"deal-1",
           "partnerSteamId":"$partner","assetIds":["a1","a2"],"tradeToken":"tok","contextId":2$extra}"""

    private val reissued = ""","leaseAttempt":2"""

    private fun loop(fence: Boolean = true) = TradeTrackerLoop(
        config = LoopConfig("1.0.0", RuntimeSurface.AndroidNative, TrackerMode.Background, reportEachWrite = true),
        marketplace = KtorMarketplaceClient(
            createHttpClient(engine),
            "https://gateway.dmarket.com",
            FakeMarketplaceAuthenticator(token = "t"),
            MarketplaceRetryConfig(retryBaseDelayMs = 1, retryMaxDelayMs = 1),
        ),
        steamReader = KtorSteamReadClient(createHttpClient(engine), endpoints),
        credentials = SteamCredentialProvider(
            vault = FakeCredentialVault(steamCredential = fakeSteamCredential()),
            scraper = FakeSteamSessionScraper(result = fakeSteamCredential()),
            clock = wall,
        ),
        scheduler = FakeScheduler(),
        clock = wall,
        deviceId = FakeDeviceIdStore(),
        offerCreator = creator,
        directivesEnabled = true,
        leaseClock = elapsed.takeIf { fence },
    )

    @Test
    fun a_reissued_create_binds_to_an_offer_in_each_live_or_accepted_state_without_creating() = runTest {
        mapOf(2 to "success", 3 to "success", 9 to "needs_confirmation", 11 to "success").forEach { (state, status) ->
            log.clear()
            reported.clear()
            directive = create(reissued)
            sentOffers = listOf("$state:$partnerAccountId:a1,a2")

            loop().runOnce()

            assertEquals(listOf("read", "report"), log, "state $state")
            assertEquals(listOf("bound-0/$status"), reported, "state $state")
        }
    }

    @Test
    fun a_declined_or_expired_offer_does_not_bind_and_the_create_goes() = runTest {
        listOf(7, 8).forEach { state ->
            log.clear()
            directive = create(reissued)
            sentOffers = listOf("$state:$partnerAccountId:a1,a2")

            loop().runOnce()

            assertEquals(listOf("read", "create", "report"), log, "state $state")
        }
    }

    @Test
    fun an_offer_to_another_partner_or_for_other_assets_does_not_bind() = runTest {
        listOf("9:39734275:a1,a2", "9:$partnerAccountId:a1", "9:$partnerAccountId:a1,a2,a3").forEach { offer ->
            log.clear()
            reported.clear()
            directive = create(reissued)
            sentOffers = listOf(offer)

            loop().runOnce()

            assertEquals(listOf("read", "create", "report"), log, offer)
            assertEquals(listOf("new-offer/needs_confirmation"), reported, offer)
        }
    }

    @Test
    fun a_first_lease_creates_without_a_read() = runTest {
        directive = create(""","leaseAttempt":1""")
        sentOffers = listOf("9:$partnerAccountId:a1,a2")

        loop().runOnce()

        assertEquals(listOf("create", "report"), log)
    }

    @Test
    fun without_the_backend_fields_the_create_goes_as_before() = runTest {
        directive = create()
        sentOffers = listOf("9:$partnerAccountId:a1,a2")

        loop().runOnce()

        assertEquals(listOf("create", "report"), log)
    }

    @Test
    fun without_a_lease_clock_a_reissued_create_goes_as_before() = runTest {
        directive = create(reissued)
        sentOffers = listOf("9:$partnerAccountId:a1,a2")

        loop(fence = false).runOnce()

        assertEquals(listOf("create", "report"), log)
    }

    @Test
    fun a_failed_read_creates_nothing_this_cycle_and_the_next_cycle_reads_again() = runTest {
        directive = create(reissued)
        readFailures = 1
        val loop = loop()

        loop.runOnce()
        assertEquals(listOf("read"), log)

        wall.advance(1.minutes)
        loop.forceHeartbeatNow()
        loop.runOnce()
        assertEquals(listOf("read", "read", "create", "report"), log)
    }

    @Test
    fun the_read_asks_for_active_offers_and_those_changed_since_the_previous_lease_started() = runTest {
        directive = create(""","previousLeaseAgeSeconds":150""")

        loop().runOnce()

        val query = reads.single()
        assertEquals("1", query[endpoints.paramActiveOnly])
        assertEquals("0", query[endpoints.paramGetReceivedOffers])
        assertEquals("0", query[endpoints.paramGetDescriptions])
        val cutoff = wall.now() - 150.seconds - 5.minutes
        assertEquals(cutoff.epochSeconds.toString(), query[endpoints.paramTimeHistoricalCutoff])
    }
}
