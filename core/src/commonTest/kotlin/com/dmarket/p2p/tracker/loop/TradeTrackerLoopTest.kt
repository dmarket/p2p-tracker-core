package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamInventoryReader
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamNotificationReader
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.config.CadenceConfig
import com.dmarket.p2p.tracker.config.NotaryBreakerConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.engine.FreshProofProgress
import com.dmarket.p2p.tracker.engine.ProofFreshness
import com.dmarket.p2p.tracker.engine.ProofIntent
import com.dmarket.p2p.tracker.engine.ProofSkipReason
import com.dmarket.p2p.tracker.engine.ReportedStatus
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.PushSignal
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.marketplace.WatchTarget
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.notary.ProvenReadKind
import com.dmarket.p2p.tracker.notary.ProvenReadRegistry
import com.dmarket.p2p.tracker.policy.SteamCreateFailureCause
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamInventoryReader
import com.dmarket.p2p.tracker.port.steam.SteamNotificationReader
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeDeviceIdStore
import com.dmarket.p2p.tracker.support.FakeMarketplaceClient
import com.dmarket.p2p.tracker.support.FakeMarketplaceCredentialProvider
import com.dmarket.p2p.tracker.support.FakeNotaryProver
import com.dmarket.p2p.tracker.support.FakeScheduler
import com.dmarket.p2p.tracker.support.FakeSteamInventoryReader
import com.dmarket.p2p.tracker.support.FakeSteamNotificationReader
import com.dmarket.p2p.tracker.support.FakeSteamOfferCanceller
import com.dmarket.p2p.tracker.support.FakeSteamOfferCreator
import com.dmarket.p2p.tracker.support.FakeSteamReadClient
import com.dmarket.p2p.tracker.support.FakeSteamSessionRefresher
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.RecordingEventObserver
import com.dmarket.p2p.tracker.support.fakeDeal
import com.dmarket.p2p.tracker.support.fakeMarketplaceCredential
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private const val PARTNER = "76561198000000002"

/** Steam `ETradeStatus` for a trade-protection rollback (the loop's own constant is private). */
private const val ROLLBACK_CODE = 12

class TradeTrackerLoopTest {

    private fun createDirective(id: String = "dir-1", dealId: String = "deal-1", partner: String = PARTNER, assetId: String = "asset-1") =
        Directive(
            directiveId = DirectiveId(id),
            action = DirectiveAction.CREATE_OFFER,
            dealId = DealId(dealId),
            partnerSteamId = SteamId(partner),
            assetIds = listOf(AssetId(assetId)),
            tradeToken = "trade-token",
            contextId = 2,
        )

    private fun tracked(
        dealId: String = "deal-1",
        offerId: String = "offer-1",
        proofRequired: Boolean = false,
        role: DealRole = DealRole.UNKNOWN,
    ) = TrackedDeal(
        dealId = DealId(dealId),
        steamOfferId = OfferId(offerId),
        watch = setOf(WatchTarget.GET_TRADE_OFFER),
        proofRequired = proofRequired,
        role = role,
    )

    private fun cancelDirective(id: String = "dir-c1", dealId: String = "deal-1", offerId: String = "offer-9") = Directive(
        directiveId = DirectiveId(id),
        action = DirectiveAction.CANCEL_OFFER,
        dealId = DealId(dealId),
        steamOfferId = OfferId(offerId),
    )

    private fun loop(
        marketplace: FakeMarketplaceClient = FakeMarketplaceClient(),
        creator: FakeSteamOfferCreator = FakeSteamOfferCreator(),
        canceller: FakeSteamOfferCanceller = FakeSteamOfferCanceller(),
        reader: FakeSteamReadClient = FakeSteamReadClient(),
        // The port, not the fake: one test needs the REAL NoOpNotaryProver, because what it asserts is the
        // identity that prover reports.
        notary: NotaryProver = FakeNotaryProver(),
        progress: TrackerProgressStore = InMemoryTrackerProgressStore(),
        claims: DealWriteClaimStore = PersistedDealWriteClaimStore(),
        inventoryReader: SteamInventoryReader = NoOpSteamInventoryReader,
        directivesEnabled: Boolean = false,
        credential: Boolean = true,
        // Seeds the vault with a credential for a specific Steam account. Defaults to [fakeSteamCredential]
        // (SteamId …001, the id every linkedSteamId test matches against); a test that needs the vault to
        // hold a DIFFERENT account — the wrong-account episode this loop is supposed to recover from —
        // passes its own. Ignored when [credential] is false.
        vaultCredential: SteamCredential = fakeSteamCredential(),
        clock: FakeClock = FakeClock(),
        scheduler: FakeScheduler = FakeScheduler(),
        eventObserver: EventObserver = NoOpEventObserver,
        loopState: LoopStateStore = InMemoryLoopStateStore(),
        marketplaceCredentials: MarketplaceCredentialProvider? = null,
        tunables: TrackerConfig = TrackerConfig.defaults(),
        // Declared AFTER [tunables] because its default reads it — a default expression can only see the
        // parameters before it, and a forward reference silently resolves to an uninitialised value.
        // Seeded Random so the jittered create cooldowns are reproducible; the limits are the shipped ones,
        // so a test that does not care about back-pressure behaves exactly as it did before.
        throttle: SteamWriteThrottleStore = PersistedSteamWriteThrottleStore(
            limits = tunables.steamWrites,
            random = Random(7),
        ),
        scraper: FakeSteamSessionScraper? = null,
        notifications: SteamNotificationReader = NoOpSteamNotificationReader,
        // Defaults to the no-op refresher, whose `sessionPresent()` fails open (`true`) — so unless a test
        // says otherwise, a failed scrape stays the signal-only re-login hint and raises no blocking state.
        sessionRefresher: SteamSessionRefresher = NoOpSteamSessionRefresher,
        // Seeded so the jittered parked-prover cooldowns are reproducible, exactly like [throttle] above.
        notaryThrottle: NotaryProofThrottleStore = PersistedNotaryProofThrottleStore(
            limits = tunables.notary.breaker,
            random = Random(7),
        ),
    ): TradeTrackerLoop {
        val vault = FakeCredentialVault(steamCredential = if (credential) vaultCredential else null)
        val provider = SteamCredentialProvider(
            vault = vault,
            scraper = scraper ?: FakeSteamSessionScraper(result = if (credential) fakeSteamCredential() else null),
            clock = clock,
            sessionRefresher = sessionRefresher,
        )
        return TradeTrackerLoop(
            config = LoopConfig("1.0.0", RuntimeSurface.WebChrome, TrackerMode.Background, tunables),
            marketplace = marketplace,
            steamReader = reader,
            credentials = provider,
            scheduler = scheduler,
            clock = clock,
            deviceId = FakeDeviceIdStore(),
            inventoryReader = inventoryReader,
            offerCreator = creator,
            offerCanceller = canceller,
            notary = notary,
            marketplaceCredentials = marketplaceCredentials,
            progress = progress,
            claims = claims,
            throttle = throttle,
            notifications = notifications,
            loopState = loopState,
            directivesEnabled = directivesEnabled,
            eventObserver = eventObserver,
            notaryThrottle = notaryThrottle,
        )
    }

    /** A DMarket credential provider whose answer (logged-in/out) can be flipped mid-test. */
    private fun marketplaceProvider(provider: FakeMarketplaceCredentialProvider): MarketplaceCredentialProvider = provider

    // ---- credential guard ------------------------------------------------------------------

    @Test
    fun idle_when_no_credential() = runTest {
        val mp = FakeMarketplaceClient()
        val result = loop(marketplace = mp, credential = false).runOnce()
        assertEquals(TickOutcome.EMPTY, result)
        assertTrue(mp.heartbeatsSent.isEmpty(), "no heartbeat when logged out")
    }

    // ---- missing DMarket connection --------------------------------------------------------

    @Test
    fun no_heartbeat_is_sent_when_dmarket_is_logged_out() = runTest {
        val mp = FakeMarketplaceClient()
        val events = RecordingEventObserver()
        // Provider that yields no token → logged out of DMarket.
        val provider = marketplaceProvider(FakeMarketplaceCredentialProvider(result = null))
        val l = loop(marketplace = mp, marketplaceCredentials = provider, eventObserver = events)
        val result = l.runOnce()
        assertEquals(TickOutcome.EMPTY, result)
        assertTrue(mp.heartbeatsSent.isEmpty(), "must NOT POST /heartbeat while logged out of DMarket")
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)
        assertTrue(events.events.any { it is LifecycleEvent.ReLoginNeeded && it.axis == "marketplace" })
    }

    @Test
    fun heartbeat_resumes_automatically_after_dmarket_login() = runTest {
        val mp = FakeMarketplaceClient()
        val events = RecordingEventObserver()
        val credentials = FakeMarketplaceCredentialProvider(result = null) // start logged out
        val provider = marketplaceProvider(credentials)
        val l = loop(marketplace = mp, marketplaceCredentials = provider, eventObserver = events)

        l.runOnce()
        assertTrue(mp.heartbeatsSent.isEmpty(), "no heartbeat while logged out")
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)
        assertTrue(events.events.any { it is LifecycleEvent.ReLoginNeeded && it.axis == "marketplace" })

        // User logs into DMarket → the provider now yields a token. The loop self-heals on its next cycle
        // (no forced nudge): the heartbeat is due again, the proactive guard now passes, and state clears.
        credentials.result = fakeMarketplaceCredential()
        l.runOnce()
        assertEquals(1, mp.heartbeatsSent.size, "heartbeat resumes once the DMarket session returns")
        assertEquals(TrackerBlock.NONE, l.blockingState)
        assertFalse(l.marketplaceConnectionMissing)
    }

    @Test
    fun unauthorized_heartbeat_sets_missing_connection_blocks_and_emits_marketplace_relogin() = runTest {
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceUnauthorizedException() }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)
        val result = l.runOnce()
        assertEquals(TickOutcome.EMPTY, result, "a missing DMarket connection blocks the whole cycle")
        assertTrue(l.marketplaceConnectionMissing)
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)
        assertTrue(
            events.events.any { it is LifecycleEvent.ReLoginNeeded && it.axis == "marketplace" },
            "should emit ReLoginNeeded(\"marketplace\") on entering the missing-connection state",
        )
    }

    @Test
    fun missing_connection_event_is_transition_only_then_clears_on_recovery() = runTest {
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceUnauthorizedException() }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)
        l.runOnce()
        l.forceHeartbeatNow()
        l.runOnce() // still failing — must NOT re-emit while already in the missing state
        assertEquals(
            1,
            events.events.count { it is LifecycleEvent.ReLoginNeeded && it.axis == "marketplace" },
            "ReLoginNeeded(marketplace) is emitted only on entry, not every failing cycle",
        )
        // Recover: a successful heartbeat clears the missing-connection state.
        mp.heartbeatThrowable = null
        l.forceHeartbeatNow()
        l.runOnce()
        assertFalse(l.marketplaceConnectionMissing)
        assertEquals(TrackerBlock.NONE, l.blockingState)
    }

    @Test
    fun missing_connection_outranks_a_stale_steam_account_mismatch() = runTest {
        // First heartbeat reports a linked Steam id that disagrees with the held token → mismatch.
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(linkedSteamId = SteamId("76561198000000099"), ttlSeconds = 60),
        )
        val l = loop(marketplace = mp)
        l.runOnce()
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, l.blockingState)
        assertTrue(l.linkedSteamIdMismatch)

        // The DMarket connection then drops on the next (forced) heartbeat; the mismatch flag is stale.
        mp.heartbeatThrowable = MarketplaceUnauthorizedException()
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState, "missing connection outranks the mismatch")
        assertTrue(l.linkedSteamIdMismatch, "the raw mismatch flag is untouched — only precedence changes")
    }

    // ---- DMarket server error (non-401) ----------------------------------------------------

    @Test
    fun heartbeat_404_sets_connection_error_and_emits_server_error() = runTest {
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(404) }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)

        val result = l.runOnce()
        assertEquals(TickOutcome.EMPTY, result, "a DMarket server error blocks the whole cycle")
        assertTrue(l.marketplaceServerError)
        assertFalse(l.marketplaceConnectionMissing, "a 404 is NOT a missing connection (the token is fine)")
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, l.blockingState)
        assertEquals(
            listOf(404),
            events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().map { it.statusCode },
            "a deterministic 4xx surfaces on the first failure, emitting exactly one MarketplaceServerError",
        )

        // Recover: a successful heartbeat clears the connection-error state.
        mp.heartbeatThrowable = null
        l.forceHeartbeatNow()
        l.runOnce()
        assertFalse(l.marketplaceServerError)
        assertEquals(TrackerBlock.NONE, l.blockingState)
    }

    @Test
    fun transient_5xx_only_surfaces_after_two_consecutive_failures() = runTest {
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(503) }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)

        // First 5xx: a single blip must NOT flash the error prompt.
        l.runOnce()
        assertFalse(l.marketplaceServerError, "one transient 5xx stays an idle tick")
        assertEquals(TrackerBlock.NONE, l.blockingState)
        assertTrue(events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().isEmpty())

        // Second consecutive 5xx crosses the threshold → surface DM_CONNECTION_ERROR + emit once.
        l.forceHeartbeatNow()
        l.runOnce()
        assertTrue(l.marketplaceServerError)
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, l.blockingState)
        assertEquals(1, events.events.count { it is LifecycleEvent.MarketplaceServerError })
    }

    @Test
    fun server_error_after_relogin_clears_stale_missing_connection() = runTest {
        // Start logged out of DMarket → DM_SESSION_MISSING (sets the sticky flag).
        val mp = FakeMarketplaceClient()
        val credentials = FakeMarketplaceCredentialProvider(result = null)
        val provider = marketplaceProvider(credentials)
        val l = loop(marketplace = mp, marketplaceCredentials = provider)

        l.runOnce()
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)
        assertTrue(l.marketplaceConnectionMissing)

        // User logs back in (cookie returns) but the heartbeat endpoint 404s (e.g. not deployed yet). The
        // request REACHED DMarket, so the connection is no longer missing — the stale DM_SESSION_MISSING
        // must give way to DM_CONNECTION_ERROR instead of masking it forever (it never clears otherwise,
        // since the 404 endpoint never produces a successful heartbeat).
        credentials.result = fakeMarketplaceCredential()
        mp.heartbeatThrowable = MarketplaceServerErrorException(404)
        l.forceHeartbeatNow()
        l.runOnce()
        assertFalse(l.marketplaceConnectionMissing, "a reached-but-erroring endpoint is NOT a missing connection")
        assertTrue(l.marketplaceServerError)
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, l.blockingState)
    }

    @Test
    fun network_level_heartbeat_failures_surface_connection_error_after_the_debounce() = runTest {
        // A fetch rejection (network down, DNS, CORS / missing host permission) has no HTTP status, so it
        // lands in the generic Throwable catch. The heartbeat did not round-trip — tracking is NOT live —
        // so after the same debounce as a transient 5xx it must surface DM_CONNECTION_ERROR (statusCode 0)
        // instead of being swallowed forever while the host keeps claiming tracking is on.
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = RuntimeException("Failed to fetch") }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)

        l.runOnce() // a single blip stays silent
        assertEquals(TrackerBlock.NONE, l.blockingState)
        assertTrue(events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().isEmpty())

        l.forceHeartbeatNow()
        l.runOnce() // second consecutive failure crosses the threshold
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, l.blockingState)
        assertEquals(
            listOf(0),
            events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().map { it.statusCode },
            "a status-less failure surfaces once, with statusCode 0",
        )

        // Entry-only while it persists; a successful heartbeat clears it.
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(1, events.events.count { it is LifecycleEvent.MarketplaceServerError })
        mp.heartbeatThrowable = null
        l.forceHeartbeatNow()
        l.runOnce()
        assertFalse(l.marketplaceServerError)
        assertEquals(TrackerBlock.NONE, l.blockingState)
    }

    @Test
    fun persistent_network_outage_surfaces_across_a_worker_respawn() = runTest {
        // A network outage is exactly when nothing keeps the MV3 worker alive between retries, so the
        // status-less path must persist its streak like the 5xx path — an in-memory-only counter would
        // restart at 0 on every respawn and the outage would stay invisible forever.
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        val events = RecordingEventObserver()
        // Worker 1: first fetch rejection of the outage — below the threshold — then dies.
        val w1 = loop(
            marketplace = FakeMarketplaceClient().apply { heartbeatThrowable = RuntimeException("Failed to fetch") },
            clock = clock,
            loopState = store,
            eventObserver = events,
        )
        w1.runOnce()
        assertFalse(w1.marketplaceServerError, "one blip stays an idle tick")
        // Worker 2 (respawn): a fresh instance sharing the store — the restored streak makes its
        // failure the outage's second consecutive one, crossing the threshold.
        val w2 = loop(
            marketplace = FakeMarketplaceClient().apply { heartbeatThrowable = RuntimeException("Failed to fetch") },
            clock = clock,
            loopState = store,
            eventObserver = events,
        )
        w2.runOnce()
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, w2.blockingState)
        assertEquals(
            listOf(0),
            events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().map { it.statusCode },
            "exactly one entry event across both workers, with the status-less statusCode 0",
        )
    }

    @Test
    fun rate_limited_heartbeat_stays_an_idle_tick_not_an_error() = runTest {
        // A 429 is explicit backend backpressure — DMarket is reachable and the session is fine. It must
        // not count toward the server-error debounce however long it persists.
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = RateLimitedException(30) }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)
        repeat(3) {
            l.forceHeartbeatNow()
            l.runOnce()
        }
        assertEquals(TrackerBlock.NONE, l.blockingState, "429 backpressure is not an error state")
        assertTrue(events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().isEmpty())
    }

    @Test
    fun network_failure_while_logged_out_keeps_missing_connection_without_a_server_error_event() = runTest {
        // A status-less failure proves nothing about the session, so it must not clear the
        // missing-connection state (unlike a real HTTP error reply) — and with DM_SESSION_MISSING still
        // outranking, no misleading MarketplaceServerError event may fire.
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceUnauthorizedException() }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)
        l.runOnce()
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)

        mp.heartbeatThrowable = RuntimeException("Failed to fetch")
        repeat(2) {
            l.forceHeartbeatNow()
            l.runOnce()
        }
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)
        assertTrue(events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().isEmpty())
    }

    @Test
    fun relogin_during_a_persistent_server_error_re_emits_the_server_error_event() = runTest {
        // The heartbeat endpoint errors permanently (e.g. a 404 route). DM_CONNECTION_ERROR is entered and
        // its entry event emitted; the user then logs out of DMarket (401 era → DM_SESSION_MISSING
        // outranks; the server-error sticky is NOT cleared) and back in. The next failing heartbeat
        // clears the stale missing-connection state, re-exposing DM_CONNECTION_ERROR — a resolved-state
        // transition that MUST re-emit MarketplaceServerError: it is the only poke an event-mirroring
        // host gets, and a sticky-gated emit would leave it showing "log into DMarket" forever.
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(404) }
        val events = RecordingEventObserver()
        val l = loop(marketplace = mp, eventObserver = events)

        l.runOnce() // enter DM_CONNECTION_ERROR
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, l.blockingState)
        assertEquals(1, events.events.count { it is LifecycleEvent.MarketplaceServerError })

        mp.heartbeatThrowable = MarketplaceUnauthorizedException() // logout: the token is now rejected
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)

        mp.heartbeatThrowable = MarketplaceServerErrorException(404) // logged back in; endpoint still 404s
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, l.blockingState, "re-login must re-expose DM_CONNECTION_ERROR")
        assertEquals(
            2,
            events.events.count { it is LifecycleEvent.MarketplaceServerError },
            "the DM_SESSION_MISSING → DM_CONNECTION_ERROR transition must re-emit the entry event",
        )

        // Still entry-only while DM_CONNECTION_ERROR persists: further failing cycles do not spam events.
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(2, events.events.count { it is LifecycleEvent.MarketplaceServerError })
    }

    @Test
    fun blocking_state_already_resolves_to_connection_error_while_the_server_error_event_is_delivered() = runTest {
        // Events are delivered synchronously and hosts read blockingState from INSIDE the handler (the
        // web extension mirrors blockingReason() to its UI on each event). The event is entry-only, so
        // if the sticky were set after the emit, the host would read the pre-transition NONE and never
        // be poked again to correct it.
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(404) }
        lateinit var l: TradeTrackerLoop
        val stateAtEmit = mutableListOf<TrackerBlock>()
        val hostLikeObserver = object : EventObserver {
            override suspend fun onEvent(event: LifecycleEvent) {
                if (event is LifecycleEvent.MarketplaceServerError) stateAtEmit += l.blockingState
            }
        }
        l = loop(marketplace = mp, eventObserver = hostLikeObserver)
        l.runOnce()
        assertEquals(
            listOf(TrackerBlock.DM_CONNECTION_ERROR),
            stateAtEmit,
            "blockingState must already be DM_CONNECTION_ERROR when MarketplaceServerError is delivered",
        )
    }

    @Test
    fun blocking_state_already_resolves_to_missing_connection_while_the_relogin_event_is_delivered() = runTest {
        // Same synchronous-delivery contract for the heartbeat-401 path: with no marketplace credential
        // provider wired, marketplaceConnectionMissing is the sticky alone, so the state read inside the
        // handler proves the sticky was set before the emit.
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceUnauthorizedException() }
        lateinit var l: TradeTrackerLoop
        val stateAtEmit = mutableListOf<TrackerBlock>()
        val hostLikeObserver = object : EventObserver {
            override suspend fun onEvent(event: LifecycleEvent) {
                if (event is LifecycleEvent.ReLoginNeeded && event.axis == "marketplace") stateAtEmit += l.blockingState
            }
        }
        l = loop(marketplace = mp, eventObserver = hostLikeObserver)
        l.runOnce()
        assertEquals(
            listOf(TrackerBlock.DM_SESSION_MISSING),
            stateAtEmit,
            "blockingState must already be DM_SESSION_MISSING when ReLoginNeeded(marketplace) is delivered",
        )
    }

    // ---- heartbeat -------------------------------------------------------------------------

    @Test
    fun heartbeat_is_sent_with_device_id_and_steam_id() = runTest {
        val mp = FakeMarketplaceClient()
        loop(marketplace = mp).runOnce()
        assertEquals(1, mp.heartbeatsSent.size)
        assertEquals("test-device-1", mp.heartbeatsSent[0].deviceId.value)
    }

    @Test
    fun heartbeat_outcome_watching_count_matches_active_tracking() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1"), tracked("d2")),
                ttlSeconds = 60,
            ),
        )
        val outcome = loop(marketplace = mp).runOnce()
        assertEquals(2, outcome.watching)
    }

    @Test
    fun force_heartbeat_now_reheartbeats_before_the_ttl_cadence_elapses() = runTest {
        val mp = FakeMarketplaceClient() // ttl_seconds = 60
        val l = loop(marketplace = mp)
        l.runOnce()
        assertEquals(1, mp.heartbeatsSent.size)
        // Clock unchanged: the ttl cadence has not elapsed, so a plain cycle only watches — no re-heartbeat.
        l.runOnce()
        assertEquals(1, mp.heartbeatsSent.size, "must not re-heartbeat before the ttl_seconds cadence elapses")
        // forceHeartbeatNow() marks the heartbeat due now, so the next cycle POSTs a fresh /heartbeat.
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(2, mp.heartbeatsSent.size, "forceHeartbeatNow must trigger a fresh heartbeat")
    }

    // ---- directives (disabled) -------------------------------------------------------------

    @Test
    fun directives_not_executed_when_disabled() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                directives = listOf(createDirective()),
                ttlSeconds = 60,
            ),
        )
        loop(marketplace = mp, creator = creator, directivesEnabled = false).runOnce()
        assertTrue(creator.created.isEmpty(), "no offer created when directivesEnabled=false")
        assertTrue(mp.directiveOutcomes.isEmpty(), "no reportDirective when disabled")
    }

    @Test
    fun directives_arriving_while_disabled_emit_dropped_event() = runTest {
        // The gate answers nothing on /trade-actions, so the backend re-leases the directive on every
        // heartbeat for the life of the client and the deal parks — indistinguishable from a healthy idle
        // client unless it is reported. That is exactly how the web facade shipping with the gate off went
        // undiagnosed, so the silence itself is the regression under test.
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60),
        )
        loop(marketplace = mp, directivesEnabled = false, eventObserver = events).runOnce()
        val dropped = events.events.filterIsInstance<LifecycleEvent.DirectiveDropped>()
        assertEquals(1, dropped.size, "an ignored directive must not be silent")
        assertEquals("create_offer", dropped.single().kind)
        assertEquals("dir-1", dropped.single().directiveId)
    }

    // ---- directives (enabled) --------------------------------------------------------------

    @Test
    fun create_offer_directive_executes_and_reports_outcome() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                directives = listOf(createDirective()),
                ttlSeconds = 60,
            ),
        )
        val outcome = loop(marketplace = mp, creator = creator, directivesEnabled = true).runOnce()
        assertEquals(1, creator.created.size, "offerCreator should be called once")
        assertEquals(1, mp.directiveOutcomes.size, "reportDirective should be called once")
        assertEquals(DirectiveId("dir-1"), mp.directiveOutcomes[0].directiveId)
        assertEquals(1, outcome.directivesExecuted)
    }

    @Test
    fun create_that_throws_is_reported_failed_and_left_retriable() = runTest {
        // A throwing creator (rejected fetch, missing host permission, body drift) used to return early with
        // no report and no event: the lease stayed held, the backend re-leased forever, and the deal stalled
        // invisibly. It is a FAILED create like any other — reported, surfaced, and retriable.
        val events = RecordingEventObserver()
        val creator = FakeSteamOfferCreator(throwable = RuntimeException("Failed to fetch"))
        val progress = InMemoryTrackerProgressStore()
        val claims = PersistedDealWriteClaimStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60),
        )
        val outcome = loop(
            marketplace = mp,
            creator = creator,
            progress = progress,
            claims = claims,
            directivesEnabled = true,
            eventObserver = events,
        ).runOnce()

        assertEquals(1, creator.created.size, "the create was attempted")
        assertEquals(1, mp.directiveOutcomes.size, "the throw must still be reported on /trade-actions")
        assertEquals(DirectiveStatus.FAILED, mp.directiveOutcomes.single().status)
        // The reported error is a `redactedSummary()`: the exception class plus a scrubbed, capped message.
        // The class is kept because this string is the only diagnosis the backend gets for a failed create.
        assertEquals("RuntimeException: Failed to fetch", mp.directiveOutcomes.single().error)
        val executed = events.events.filterIsInstance<LifecycleEvent.DirectiveExecuted>()
        assertEquals(DirectiveStatus.FAILED.name, executed.single().status)
        // Nothing reached Steam, so the next re-lease must be free to try again: neither handled nor claimed.
        assertTrue(progress.loadHandledDirectives().isEmpty(), "a failed create stays unhandled")
        assertTrue(claims.all().isEmpty(), "a failed create releases its deal write claim")
        // The tick counts the directive as *answered*, not as written — the backend accepted the FAILED
        // report, which is what releases its lease. Being unhandled + unclaimed is what keeps it retriable.
        assertEquals(1, outcome.directivesExecuted)
    }

    @Test
    fun handled_directive_is_single_flight_across_cycles() = runTest {
        val creator = FakeSteamOfferCreator()
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                directives = listOf(createDirective()),
                ttlSeconds = 60,
            ),
        )
        val clock = FakeClock()
        val l = loop(marketplace = mp, creator = creator, progress = progress, directivesEnabled = true, clock = clock)
        l.runOnce()
        clock.advance(61.seconds) // past the ttl so the second cycle heartbeats and re-leases
        l.runOnce()
        assertEquals(1, creator.created.size, "handled directive must not be re-executed on second cycle")
    }

    @Test
    fun create_that_succeeds_on_steam_is_not_reexecuted_when_report_fails() = runTest {
        val creator = FakeSteamOfferCreator()
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60),
        ).apply { reportDirectiveThrows = true }
        val clock = FakeClock()
        val l = loop(marketplace = mp, creator = creator, progress = progress, directivesEnabled = true, clock = clock)
        l.runOnce()
        clock.advance(61.seconds) // past the ttl so the second cycle heartbeats and re-leases
        l.runOnce()
        assertEquals(1, creator.created.size, "a completed Steam write must never be re-executed, even if /trade-actions failed")
    }

    @Test
    fun malformed_create_offer_without_partner_is_skipped() = runTest {
        val creator = FakeSteamOfferCreator()
        val directive = Directive(
            directiveId = DirectiveId("dir-bad"),
            action = DirectiveAction.CREATE_OFFER,
            dealId = DealId("d1"),
            partnerSteamId = null,
            assetIds = listOf(AssetId("a1")),
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(directive), ttlSeconds = 60),
        )
        loop(marketplace = mp, creator = creator, directivesEnabled = true).runOnce()
        assertTrue(creator.created.isEmpty(), "malformed directive must be skipped")
    }

    @Test
    fun malformed_directive_emits_dropped_event() = runTest {
        // A silently-dropped malformed directive would be re-leased every heartbeat and stall the deal
        // invisibly; it must surface as a DirectiveDropped event.
        val events = RecordingEventObserver()
        val malformed = createDirective().copy(partnerSteamId = null)
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(malformed), ttlSeconds = 60),
        )
        loop(marketplace = mp, directivesEnabled = true, eventObserver = events).runOnce()
        val dropped = events.events.filterIsInstance<LifecycleEvent.DirectiveDropped>()
        assertEquals(1, dropped.size)
        assertEquals("create_offer", dropped.single().kind)
        assertEquals("dir-1", dropped.single().directiveId)
    }

    // ---- directives: the buyer-role write guard --------------------------------------------
    //
    // The backend serves active_tracking to BOTH sides of a deal (so either can report the Steam trade) but
    // leases create/cancel to the seller alone. A Directive carries no side of its own, so the tracking
    // entry's `role` is the only thing on this side that can tell the two apart.

    @Test
    fun create_offer_on_a_buyer_role_deal_never_reaches_steam() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked(role = DealRole.BUYER)),
                directives = listOf(createDirective()),
                ttlSeconds = 60,
            ),
        )
        val outcome = loop(marketplace = mp, creator = creator, directivesEnabled = true).runOnce()
        assertTrue(creator.created.isEmpty(), "a buyer must never write to Steam on the deal")
        assertEquals(0, outcome.directivesExecuted)
        // Reported, not silently dropped: an unanswered directive keeps the backend's lease and is re-leased
        // forever, stalling the deal invisibly on both sides.
        val reported = mp.directiveOutcomes.single()
        assertEquals(DirectiveStatus.FAILED, reported.status)
        assertTrue(reported.error!!.contains("buyer"), "the outcome should say why: ${reported.error}")
    }

    @Test
    fun cancel_offer_on_a_buyer_role_deal_never_reaches_steam() = runTest {
        val canceller = FakeSteamOfferCanceller()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked(role = DealRole.BUYER)),
                directives = listOf(cancelDirective()),
                ttlSeconds = 60,
            ),
        )
        loop(marketplace = mp, canceller = canceller, directivesEnabled = true).runOnce()
        assertTrue(canceller.cancelledOffers.isEmpty(), "the buyer cancelling the seller's offer would abort a live delivery")
        assertEquals(DirectiveStatus.FAILED, mp.directiveOutcomes.single().status)
    }

    @Test
    fun one_heartbeat_can_mix_sides_and_only_the_purchase_is_refused() = runTest {
        // The reason `role` is per-entry and not a build flag: one account sells and buys at the same time.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(
                    tracked(dealId = "sale-1", offerId = "offer-s", role = DealRole.SELLER),
                    tracked(dealId = "purchase-1", offerId = "offer-b", role = DealRole.BUYER),
                ),
                directives = listOf(
                    createDirective(id = "dir-sale", dealId = "sale-1"),
                    createDirective(id = "dir-purchase", dealId = "purchase-1"),
                ),
                ttlSeconds = 60,
            ),
        )
        val outcome = loop(marketplace = mp, creator = creator, directivesEnabled = true).runOnce()
        assertEquals(1, creator.created.size, "only the sale may reach Steam")
        assertEquals(1, outcome.directivesExecuted)
        val refused = mp.directiveOutcomes.single { it.directiveId == DirectiveId("dir-purchase") }
        assertEquals(DirectiveStatus.FAILED, refused.status)
    }

    @Test
    fun a_refused_write_is_not_marked_handled_so_a_corrected_role_lets_it_through() = runTest {
        // Nothing reached Steam, so the refusal must stay retryable: if the next heartbeat says we are the
        // seller after all (a backend index fix, or a role the first response simply omitted), the same
        // write proceeds. Marking it handled would strand the deal until a human noticed.
        val creator = FakeSteamOfferCreator()
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked(role = DealRole.BUYER)),
                directives = listOf(createDirective()),
                ttlSeconds = 60,
            ),
        )
        val l = loop(marketplace = mp, creator = creator, directivesEnabled = true, clock = clock)
        l.runOnce()
        assertTrue(creator.created.isEmpty())
        mp.heartbeatResponse = HeartbeatResponse(
            activeTracking = listOf(tracked(role = DealRole.SELLER)),
            directives = listOf(createDirective()),
            ttlSeconds = 60,
        )
        clock.advance(61.seconds) // past the ttl so the next cycle heartbeats and re-leases
        l.runOnce()
        assertEquals(1, creator.created.size, "a re-leased write must execute once the role no longer refuses it")
    }

    @Test
    fun an_unknown_or_absent_role_fails_open() = runTest {
        // `role` is not in the frozen contract yet: refusing writes on its absence would brick the seller
        // flow against any backend that does not send it. The cost of failing open is a write the backend
        // already declines to lease.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked()), // role defaults to UNKNOWN, as an older backend leaves it
                directives = listOf(createDirective()),
                ttlSeconds = 60,
            ),
        )
        loop(marketplace = mp, creator = creator, directivesEnabled = true).runOnce()
        assertEquals(1, creator.created.size, "an UNKNOWN role must not block a legitimate create")
    }

    @Test
    fun host_create_trade_is_refused_for_a_buyer_role_deal() = runTest {
        // The host path synthesises its own directive, so the backend's decision not to lease this write
        // never reaches it — the last heartbeat's tracking list is the only thing that can refuse it.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(tracked(role = DealRole.BUYER)), ttlSeconds = 60),
        )
        val l = loop(marketplace = mp, creator = creator)
        l.runOnce() // caches active_tracking
        val result = l.createTrade(
            directiveId = DirectiveId("dir-host"),
            dealId = DealId("deal-1"),
            draft = TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        assertIs<CreateOfferResult.Failed>(result)
        assertTrue(creator.created.isEmpty(), "the host must not be able to write on a deal we are buying")
        assertEquals(DirectiveStatus.FAILED, mp.directiveOutcomes.single().status)
    }

    @Test
    fun host_create_trade_still_works_before_the_first_heartbeat() = runTest {
        // A cold worker has no tracking list at all; the FE's create trigger must keep working.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient()
        val result = loop(marketplace = mp, creator = creator).createTrade(
            directiveId = DirectiveId("dir-host"),
            dealId = DealId("deal-1"),
            draft = TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        assertIs<CreateOfferResult.NeedsConfirmation>(result)
        assertEquals(1, creator.created.size)
    }

    // ---- watch: read failures + assetId caching --------------------------------------------

    @Test
    fun persistent_steam_offer_read_failure_emits_event() = runTest {
        // A non-auth Steam read failure collapses to an empty read; without an event it is an invisible
        // no-op tick. Surface it as SteamReadFailed.
        val events = RecordingEventObserver()
        val reader = FakeSteamReadClient().apply { offerStatusesThrows = true }
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(tracked()), ttlSeconds = 60),
        )
        loop(marketplace = mp, reader = reader, eventObserver = events).runOnce()
        val failures = events.events.filterIsInstance<LifecycleEvent.SteamReadFailed>()
        assertEquals(1, failures.size)
        assertEquals("offer", failures.single().axis)
    }

    @Test
    fun steam_read_failure_reason_is_sanitized() = runTest {
        // This is the contract test for what a host — and its crash reporter — actually receives. The
        // exceptions the core throws are already sanitized at the source, so the message here is one only a
        // foreign throwable could produce, which is exactly what the second layer exists for.
        val secret = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln"
        val events = RecordingEventObserver()
        val reader = FakeSteamReadClient().apply {
            offerStatusesThrows = true
            offerStatusesThrowMessage =
                "GET https://api.steampowered.com/IEconService/GetTradeOffers/v1/?access_token=$secret failed: 403"
        }
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(tracked()), ttlSeconds = 60),
        )

        loop(marketplace = mp, reader = reader, eventObserver = events).runOnce()

        val reason = events.events.filterIsInstance<LifecycleEvent.SteamReadFailed>().single().reason
        assertNotNull(reason)
        assertFalse(secret in reason, "token reached the host's event stream: $reason")
        assertTrue("access_token=<redacted>" in reason, reason)
        assertTrue("403" in reason, "the diagnosis must survive: $reason")
        assertTrue("IllegalStateException" in reason, "the exception class names the failure: $reason")
    }

    private fun trackedBothAxes(offerId: String = "offer-1") = TrackedDeal(
        dealId = DealId("deal-1"),
        steamOfferId = OfferId(offerId),
        watch = setOf(WatchTarget.GET_TRADE_OFFER, WatchTarget.GET_TRADE_HISTORY),
        proofRequired = false,
    )

    @Test
    fun an_offer_that_cannot_have_transferred_yet_costs_no_deal_read() = runTest {
        // The asset ref is only the FALLBACK correlation key, and learning it costs a `/p2p/deals/{id}` read.
        // An offer Steam reports as still Active provably has no transfer record, so that read buys nothing —
        // and it used to be issued for every history-watched deal on every cycle regardless.
        val reader = FakeSteamReadClient(
            initialOffers = mapOf(OfferId("offer-1") to 2),
            initialTransfers = listOf(SteamTransfer(partnerSteamId = null, assetIds = setOf(AssetId("asset-77")), status = 12)),
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(trackedBothAxes()), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val l = loop(marketplace = mp, reader = reader)
        l.runOnce()
        l.runOnce()
        assertEquals(0, mp.getDealCalls, "an in-flight offer must not spend a deal read")
    }

    @Test
    fun the_trade_id_from_the_offer_axis_correlates_with_no_deal_read_at_all() = runTest {
        // The primary correlation: Steam attaches `tradeid` to the offer on acceptance and it IS the history
        // row's id, so the join is exact and free — no asset ref, no `/p2p/deals/{id}`, nothing to re-key.
        val reader = FakeSteamReadClient(
            initialOffers = mapOf(OfferId("offer-1") to 3),
            initialTransfers = listOf(
                SteamTransfer(partnerSteamId = null, assetIds = setOf(AssetId("other")), status = 3, tradeId = TradeId("t-other")),
                SteamTransfer(partnerSteamId = null, assetIds = setOf(AssetId("asset-77")), status = 12, tradeId = TradeId("t-1")),
            ),
        ).apply { offerTradeIds = mapOf(OfferId("offer-1") to TradeId("t-1")) }
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(trackedBothAxes()), ttlSeconds = 60),
        )
        val l = loop(marketplace = mp, reader = reader)
        l.runOnce()
        l.runOnce()

        assertEquals(0, mp.getDealCalls, "the trade id makes the deal read unnecessary")
        val history = mp.tradeStatusReports.filter { it.source == TradeStatusSource.HISTORY }
        assertEquals(listOf(ROLLBACK_CODE), history.map { it.steamStatusCode })
    }

    @Test
    fun deal_asset_id_is_fetched_once_and_cached_across_cycles() = runTest {
        // The fallback path: an accepted offer Steam gives no `tradeid` for (it no longer lists the offer, or
        // never paired it) still correlates by the deal's asset ref — fetched via getDeal ONCE and cached,
        // never re-fetched every cycle (the N+1 fix).
        val reader = FakeSteamReadClient(
            initialOffers = mapOf(OfferId("offer-1") to 3),
            initialTransfers = listOf(SteamTransfer(partnerSteamId = null, assetIds = setOf(AssetId("asset-77")), status = 12)),
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(trackedBothAxes()), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val l = loop(marketplace = mp, reader = reader)
        l.runOnce() // heartbeat + watch → getDeal (call 1, then cached)
        l.runOnce() // between-heartbeat wake watches the cached tracking → getDeal served from cache
        assertEquals(1, mp.getDealCalls, "immutable assetId is fetched once, then cached")
    }

    @Test
    fun a_known_trade_id_whose_row_is_outside_the_window_does_not_fall_back_to_the_asset_ref() = runTest {
        // Falling back would be actively wrong, not merely wasteful: the asset ref can match a DIFFERENT trade
        // of the same asset (an item returns under its original id after a rollback and may be sold again), so
        // an out-of-window row would be answered with a stale trade's status.
        val events = RecordingEventObserver()
        val reader = FakeSteamReadClient(
            initialOffers = mapOf(OfferId("offer-1") to 3),
            initialTransfers = listOf(
                SteamTransfer(partnerSteamId = null, assetIds = setOf(AssetId("asset-77")), status = 3, tradeId = TradeId("older")),
            ),
        ).apply { offerTradeIds = mapOf(OfferId("offer-1") to TradeId("t-1")) }
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(trackedBothAxes()), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )

        loop(marketplace = mp, reader = reader, eventObserver = events).runOnce()

        assertEquals(0, mp.getDealCalls)
        assertTrue(mp.tradeStatusReports.none { it.source == TradeStatusSource.HISTORY }, "no status is better than a wrong one")
        assertEquals(1, events.events.count { it is LifecycleEvent.HistoryCorrelationMiss })
    }

    // ---- reversal attribution (history 12) --------------------------------------------------

    private val reverser = SteamId("76561198000045730")

    /**
     * A history read shaped like Steam's, not like a convenient single row: a rollback is **two records**,
     * so [status] 12 also emits the compensating `status 3` record the rollback adds — listed first, as
     * Steam lists newest first, and carrying the same asset because it mirrors the original's direction.
     * That twin is what a first-match correlation used to return, reporting a reversal as a completion.
     */
    private fun rollbackFixture(status: Int, modifiedAt: Instant? = Instant.fromEpochSeconds(1_781_697_600)) = FakeSteamReadClient(
        initialTransfers = listOfNotNull(
            SteamTransfer(
                partnerSteamId = SteamId(PARTNER),
                assetIds = setOf(AssetId("asset-77")),
                status = 3,
                tradeId = TradeId("trade-compensating"),
                initiatedAt = Instant.fromEpochSeconds(1_781_697_600),
                rollbackTradeId = TradeId("trade-original"),
            ).takeIf { status == ROLLBACK_CODE },
            SteamTransfer(
                partnerSteamId = SteamId(PARTNER),
                assetIds = setOf(AssetId("asset-77")),
                status = status,
                tradeId = TradeId("trade-original"),
                initiatedAt = Instant.fromEpochSeconds(1_781_697_000),
                modifiedAt = modifiedAt,
            ),
        ),
    )

    private val historyTracked = TrackedDeal(
        dealId = DealId("deal-1"),
        watch = setOf(WatchTarget.GET_TRADE_HISTORY),
        proofRequired = false,
    )

    /**
     * A loop watching one history-only deal. Every parameter past [reader] is defaulted, so the same fixture
     * serves the rollback-attribution tests (which supply a notification reader) and the revert-watch cadence
     * tests (which supply a clock they advance) without a second copy of the tracked deal + ttl + deal-for-get.
     */
    private fun rollbackLoop(
        reader: FakeSteamReadClient,
        notifications: SteamNotificationReader = NoOpSteamNotificationReader,
        progress: TrackerProgressStore = InMemoryTrackerProgressStore(),
        clock: FakeClock = FakeClock(),
    ) = loop(
        marketplace = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        ),
        reader = reader,
        progress = progress,
        notifications = notifications,
        clock = clock,
    )

    /** A plain completed transfer that still carries its Trade-Protection window. */
    private fun settledFixture(settlementAt: Instant? = Instant.fromEpochSeconds(1_786_356_000)) = FakeSteamReadClient(
        initialTransfers = listOf(
            SteamTransfer(
                partnerSteamId = SteamId(PARTNER),
                assetIds = setOf(AssetId("asset-77")),
                status = 3,
                tradeId = TradeId("trade-original"),
                initiatedAt = Instant.fromEpochSeconds(1_781_697_000),
                settlementAt = settlementAt,
            ),
        ),
    )

    @Test
    fun a_history_report_carries_the_trade_protection_window() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        loop(marketplace = mp, reader = settledFixture()).runOnce()

        val report = mp.tradeStatusReports.single { it.source == TradeStatusSource.HISTORY }
        assertEquals(Instant.fromEpochSeconds(1_786_356_000), report.settlementTime)
    }

    @Test
    fun a_window_that_appears_after_its_code_was_baselined_is_still_reported_once() = runTest {
        // The capture hole this closes: HISTORY 3 is already in the baseline, so pure dedup would suppress
        // the report that carries the window — and Steam clears `time_settlement` on the rollback flip, so
        // there is no later read that could recover it.
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 3)))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val l = loop(marketplace = mp, reader = settledFixture(), progress = progress)
        l.runOnce()
        l.runOnce()
        l.runOnce()

        val history = mp.tradeStatusReports.filter { it.source == TradeStatusSource.HISTORY }
        assertEquals(1, history.size, "the re-assert is one-shot, not a per-tick re-report")
        assertEquals(Instant.fromEpochSeconds(1_786_356_000), history.single().settlementTime)
        assertTrue(progress.loadReported()[DealId("deal-1")]?.historySettlementReported == true)
    }

    @Test
    fun a_window_already_on_record_is_never_re_sent() = runTest {
        // The terminating half. Without this the same unchanged code would buy a /trade-events POST forever.
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(
            mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 3, historySettlementReported = true)),
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        loop(marketplace = mp, reader = settledFixture(), progress = progress).runOnce()

        assertTrue(mp.tradeStatusReports.none { it.source == TradeStatusSource.HISTORY })
    }

    @Test
    fun a_rollback_is_reported_even_when_the_completion_it_undoes_was_already_reported() = runTest {
        // THE reported bug, end to end. The trade completed and HISTORY 3 is in the dedup baseline; then it
        // is reverted, which puts the compensating status-3 twin in front of the status-12 original. Reading
        // the twin re-observed a 3, which deduped to NO report — so the backend never learned of the revert.
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = 3)))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        loop(marketplace = mp, reader = rollbackFixture(ROLLBACK_CODE), progress = progress, notifications = notifications).runOnce()

        val report = mp.tradeStatusReports.single { it.source == TradeStatusSource.HISTORY }
        assertEquals(ROLLBACK_CODE, report.steamStatusCode)
        assertEquals(reverser, report.reversalInitiatorSteamId)
        assertEquals(ROLLBACK_CODE, progress.loadReported()[DealId("deal-1")]?.lastHistoryCode)
    }

    @Test
    fun a_revert_seen_before_any_completion_report_never_reports_a_completion() = runTest {
        // The worse half of the same defect: with an empty baseline the compensating twin was reported as a
        // positive Complete(3) — the backend's payout condition — for a trade that had just been reversed,
        // and persisting that 3 then sealed the 12 out for good.
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        loop(marketplace = mp, reader = rollbackFixture(ROLLBACK_CODE), progress = progress, notifications = notifications).runOnce()

        val history = mp.tradeStatusReports.filter { it.source == TradeStatusSource.HISTORY }
        assertEquals(listOf(ROLLBACK_CODE), history.map { it.steamStatusCode }, "a revert must never be reported as Complete(3)")
    }

    @Test
    fun a_rollback_report_carries_the_resolved_initiator() = runTest {
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        loop(marketplace = mp, reader = rollbackFixture(12), notifications = notifications).runOnce()

        val report = mp.tradeStatusReports.single { it.source == TradeStatusSource.HISTORY }
        assertEquals(12, report.steamStatusCode)
        assertEquals(reverser, report.reversalInitiatorSteamId)
        // The attribution read is correlated off the matched transfer row, not off the deal.
        assertEquals(SteamId(PARTNER), notifications.lastCounterparty)
        assertEquals(Instant.fromEpochSeconds(1_781_697_600), notifications.lastModifiedAt)
    }

    @Test
    fun a_non_rollback_history_code_never_touches_the_notification_stream() = runTest {
        // The notification payload is account-wide and unfiltered, so it must not be read on a normal tick.
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        rollbackLoop(rollbackFixture(3), notifications, InMemoryTrackerProgressStore()).runOnce()
        assertEquals(0, notifications.calls, "only a status-12 rollback may trigger the attribution read")
    }

    @Test
    fun an_unresolved_initiator_is_not_deduped_so_the_rollback_is_re_reported() = runTest {
        // Steam signs out whoever reversed the trade, so this read usually fails on exactly the tick 12
        // first appears. If the code entered the dedup baseline anyway it would never be re-sent, and the
        // backend — which reads a missing actor as "undecided" — would park the deal forever.
        val notifications = FakeSteamNotificationReader(initiator = null)
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val l = loop(marketplace = mp, reader = rollbackFixture(12), progress = progress, notifications = notifications)
        l.runOnce()
        l.runOnce()

        val history = mp.tradeStatusReports.filter { it.source == TradeStatusSource.HISTORY }
        assertEquals(2, history.size, "an unattributed rollback must be re-reported until attribution resolves")
        assertTrue(history.all { it.reversalInitiatorSteamId == null })
        assertNull(progress.loadReported()[DealId("deal-1")]?.lastHistoryCode, "the baseline stays unset")
    }

    @Test
    fun a_resolved_initiator_is_deduped_normally() = runTest {
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val l = loop(marketplace = mp, reader = rollbackFixture(12), progress = progress, notifications = notifications)
        l.runOnce()
        l.runOnce()

        assertEquals(1, mp.tradeStatusReports.count { it.source == TradeStatusSource.HISTORY }, "an attributed rollback dedups")
        assertEquals(12, progress.loadReported()[DealId("deal-1")]?.lastHistoryCode)
        assertEquals(1, notifications.calls, "and the notification stream is not re-read once deduped")
    }

    @Test
    fun a_rollback_without_a_time_mod_still_reports_without_an_initiator() = runTest {
        // time_mod is unverified on live payloads; absent simply means attribution cannot be attempted.
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        loop(marketplace = mp, reader = rollbackFixture(12, modifiedAt = null), notifications = notifications).runOnce()

        val report = mp.tradeStatusReports.single { it.source == TradeStatusSource.HISTORY }
        assertEquals(12, report.steamStatusCode, "the raw code is still reported")
        assertNull(report.reversalInitiatorSteamId)
        assertEquals(0, notifications.calls, "with no time_mod there is nothing to correlate against")
    }

    @Test
    fun a_permanently_unattributable_rollback_is_deduped_instead_of_re_reported_forever() = runTest {
        // The retry above must not become an infinite loop. If Steam never supplies the correlation inputs
        // (time_mod is still unverified on live payloads), no retry can ever resolve an actor — so the
        // rollback is reported once as undecided and deduped, rather than re-sent on every single tick.
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val l = loop(
            marketplace = mp,
            reader = rollbackFixture(12, modifiedAt = null),
            progress = progress,
            notifications = notifications,
        )
        l.runOnce()
        l.runOnce()
        l.runOnce()

        assertEquals(1, mp.tradeStatusReports.count { it.source == TradeStatusSource.HISTORY }, "reported once, then deduped")
        assertEquals(12, progress.loadReported()[DealId("deal-1")]?.lastHistoryCode, "the baseline is recorded")
    }

    @Test
    fun a_rollback_already_in_the_baseline_is_re_asserted_once_its_actor_resolves() = runTest {
        // The deadlock, end to end: the `12` was reported and accepted while Steam had signed the reverser
        // out, so it entered the dedup baseline with no actor. The attribution read used to be gated on the
        // code being *fresh* — the very field that dedup had just written — so the actor could never be
        // back-filled and the backend held an actor-undecided rollback forever, with the client silent.
        val notifications = FakeSteamNotificationReader(initiator = null)
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = ROLLBACK_CODE)))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val l = loop(marketplace = mp, reader = rollbackFixture(ROLLBACK_CODE), progress = progress, notifications = notifications)

        l.runOnce()
        assertTrue(mp.tradeStatusReports.none { it.source == TradeStatusSource.HISTORY }, "no actor yet: nothing to add")

        notifications.initiator = reverser
        l.runOnce()

        val report = mp.tradeStatusReports.single { it.source == TradeStatusSource.HISTORY }
        assertEquals(ROLLBACK_CODE, report.steamStatusCode)
        assertEquals(reverser, report.reversalInitiatorSteamId)
        assertTrue(progress.loadReported()[DealId("deal-1")]?.historyInitiatorReported == true)

        // And it terminates: with the actor on record the stream is not re-read and nothing is re-sent.
        val callsAfterResolution = notifications.calls
        l.runOnce()
        assertEquals(1, mp.tradeStatusReports.count { it.source == TradeStatusSource.HISTORY })
        assertEquals(callsAfterResolution, notifications.calls)
    }

    @Test
    fun a_rollback_is_reported_when_the_backend_serves_a_compound_asset_ref() = runTest {
        // The live defect, end to end, with the exact payloads: dev2 serves `Deal.assetId` as
        // `instanceid:classid:assetid:appid`, and correlating with that ref verbatim matched nothing — so the
        // history axis of every watched deal was silently blind and the rollback was never reported.
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "302028390:1989275999:44977997680:730"),
        )
        val reader = FakeSteamReadClient(
            initialTransfers = listOf(
                // Steam lists newest first: the compensating record of the rollback, then the deal's own row.
                SteamTransfer(
                    partnerSteamId = SteamId(PARTNER),
                    assetIds = setOf(AssetId("44977997680")),
                    status = 3,
                    tradeId = TradeId("594063661313558826"),
                    initiatedAt = Instant.fromEpochSeconds(1_785_929_822),
                    rollbackTradeId = TradeId("739304749312013446"),
                ),
                SteamTransfer(
                    partnerSteamId = SteamId(PARTNER),
                    assetIds = setOf(AssetId("44977997680")),
                    status = ROLLBACK_CODE,
                    tradeId = TradeId("739304749312013446"),
                    initiatedAt = Instant.fromEpochSeconds(1_785_929_600),
                    modifiedAt = Instant.fromEpochSeconds(1_785_929_822),
                ),
            ),
        )

        loop(marketplace = mp, reader = reader, progress = progress, notifications = notifications).runOnce()

        val report = mp.tradeStatusReports.single { it.source == TradeStatusSource.HISTORY }
        assertEquals(ROLLBACK_CODE, report.steamStatusCode)
        assertEquals(reverser, report.reversalInitiatorSteamId)
        assertEquals(1, mp.getDealCalls, "a ref that correlates needs no re-key")
    }

    @Test
    fun a_rejected_actor_re_assert_is_not_retried_on_every_tick() = runTest {
        // The re-assert cannot be bounded by acceptance: the backend may well refuse a duplicate terminal
        // code, and then nothing would ever clear the flag — one refusal would buy a notification read plus a
        // /trade-events POST on every tick for as long as the deal is tracked.
        val notifications = FakeSteamNotificationReader(initiator = reverser)
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastHistoryCode = ROLLBACK_CODE)))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        mp.tradeStatusAccepted = false
        val l = loop(marketplace = mp, reader = rollbackFixture(ROLLBACK_CODE), progress = progress, notifications = notifications)

        l.runOnce()
        l.runOnce()
        l.runOnce()

        assertEquals(1, mp.tradeStatusReports.count { it.source == TradeStatusSource.HISTORY }, "asserted once, not per tick")
        assertEquals(1, notifications.calls, "and the notification stream is read once")
    }

    @Test
    fun a_short_results_list_never_baselines_the_report_it_did_not_answer() = runTest {
        // A batch carries both axes of one deal; a backend that answers one result per DEAL used to have that
        // result zipped onto the first report while the second was dropped — and a `12` baselined off the
        // offer axis's acknowledgement is never sent again. Matched-or-not-accepted, and it says so.
        val events = RecordingEventObserver()
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(historyTracked.copy(steamOfferId = OfferId("offer-1"))),
                ttlSeconds = 60,
            ),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        mp.tradeStatusResults = { reports -> listOf(TradeStatusResult(reports.first().dealId, accepted = true)) }
        val reader = rollbackFixture(ROLLBACK_CODE).also { it.offers = mapOf(OfferId("offer-1") to 3) }

        loop(marketplace = mp, reader = reader, progress = progress, eventObserver = events).runOnce()

        val sent = mp.tradeStatusReports
        assertEquals(2, sent.size, "both axes were sent")
        assertNull(progress.loadReported()[DealId("deal-1")], "one deal-level result cannot answer either axis")
        val failures = events.events.filterIsInstance<LifecycleEvent.TradeStatusReportFailed>()
        assertEquals(setOf("offer", "history"), failures.mapTo(mutableSetOf()) { it.source })
        assertTrue(failures.any { it.steamStatusCode == ROLLBACK_CODE })
    }

    @Test
    fun a_thrown_trade_events_batch_is_named_and_nothing_is_baselined() = runTest {
        val events = RecordingEventObserver()
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        mp.reportTradeStatusThrows = true

        loop(marketplace = mp, reader = rollbackFixture(ROLLBACK_CODE), progress = progress, eventObserver = events).runOnce()

        assertNull(progress.loadReported()[DealId("deal-1")]?.lastHistoryCode)
        assertEquals(1, events.events.count { it is LifecycleEvent.TradeStatusReportFailed })
    }

    @Test
    fun a_history_watched_deal_that_correlates_to_nothing_re_keys_once_then_says_so() = runTest {
        // A wrong join key used to silence a deal's history axis for the life of the worker: the value is
        // cached, never re-validated, and `select` returning null was indistinguishable from "no transfer
        // yet". Now an accepted offer with no row re-reads the key once — and if that still finds nothing,
        // the cycle reports the miss instead of going quiet.
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(historyTracked.copy(steamOfferId = OfferId("offer-1"))),
                ttlSeconds = 60,
            ),
            dealsForGet = listOf(fakeDeal(dealId = "deal-1", assetId = "stale-key")),
        )
        val reader = rollbackFixture(ROLLBACK_CODE).also { it.offers = mapOf(OfferId("offer-1") to 3) }
        val l = loop(marketplace = mp, reader = reader, eventObserver = events)

        l.runOnce()
        assertEquals(2, mp.getDealCalls, "one cached-key read plus exactly one re-key attempt")
        val miss = events.events.filterIsInstance<LifecycleEvent.HistoryCorrelationMiss>().single()
        assertEquals("deal-1", miss.dealId)
        assertTrue(miss.refetched)
        assertTrue(mp.tradeStatusReports.none { it.source == TradeStatusSource.HISTORY })

        // Bounded: the re-key is once per deal per loop instance, not once per tick — the backend answered
        // with the same key, so there is nothing new to try and the next cycles cost no reads at all.
        l.runOnce()
        assertEquals(2, mp.getDealCalls, "no further /p2p/deals reads once the deal has spent its re-key")
        val misses = events.events.filterIsInstance<LifecycleEvent.HistoryCorrelationMiss>()
        assertEquals(2, misses.size, "but the miss keeps being reported — it is still unresolved")
        assertFalse(misses.last().refetched)
    }

    @Test
    fun a_re_keyed_asset_id_recovers_the_rollback_in_the_same_cycle() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(historyTracked.copy(steamOfferId = OfferId("offer-1"))),
                ttlSeconds = 60,
            ),
            dealsForGet = listOf(
                fakeDeal(dealId = "deal-1", assetId = "stale-key"),
                fakeDeal(dealId = "deal-1", assetId = "asset-77"),
            ),
        )
        val reader = rollbackFixture(ROLLBACK_CODE).also { it.offers = mapOf(OfferId("offer-1") to 3) }

        loop(marketplace = mp, reader = reader, notifications = FakeSteamNotificationReader(reverser)).runOnce()

        val report = mp.tradeStatusReports.single { it.source == TradeStatusSource.HISTORY }
        assertEquals(ROLLBACK_CODE, report.steamStatusCode)
    }

    @Test
    fun an_in_flight_offer_with_no_transfer_row_is_ordinary_silence_not_a_miss() = runTest {
        // The other side of the discriminator: state 2 means Steam cannot have a transfer record yet, so
        // there is nothing to re-key and nothing to report — this must not spend a /p2p/deals read either.
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(historyTracked.copy(steamOfferId = OfferId("offer-1"))),
                ttlSeconds = 60,
            ),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "stale-key"),
        )
        val reader = rollbackFixture(ROLLBACK_CODE).also { it.offers = mapOf(OfferId("offer-1") to 2) }

        loop(marketplace = mp, reader = reader, eventObserver = events).runOnce()

        assertEquals(0, mp.getDealCalls, "an offer that cannot have transferred yet buys no deal read at all")
        assertTrue(events.events.none { it is LifecycleEvent.HistoryCorrelationMiss })
    }

    @Test
    fun a_failed_deal_lookup_is_named_instead_of_swallowed() = runTest {
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
        )
        mp.getDealThrows = true

        loop(marketplace = mp, reader = rollbackFixture(ROLLBACK_CODE), eventObserver = events).runOnce()

        assertEquals(1, events.events.count { it is LifecycleEvent.DealLookupFailed })
        assertTrue(mp.tradeStatusReports.none { it.source == TradeStatusSource.HISTORY })
    }

    @Test
    fun a_watch_pass_that_reports_nothing_still_says_why() = runTest {
        // The observability hole that made the live rollback undiagnosable: `plan.isEmpty` returned before
        // anything was emitted, so a deduped cycle and a cycle that never saw the axis were the same silence.
        val events = RecordingEventObserver()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(
            mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = ROLLBACK_CODE, historyInitiatorReported = true)),
        )
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(historyTracked.copy(steamOfferId = OfferId("offer-1"))),
                ttlSeconds = 60,
            ),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val reader = rollbackFixture(ROLLBACK_CODE).also { it.offers = mapOf(OfferId("offer-1") to 3) }

        loop(marketplace = mp, reader = reader, progress = progress, eventObserver = events).runOnce()

        val summary = events.events.filterIsInstance<LifecycleEvent.WatchSummary>().single()
        assertEquals(1, summary.watched)
        assertEquals(1, summary.historyObserved)
        assertEquals(0, summary.uncorrelated)
        assertEquals(0, summary.planned)
        assertEquals(2, summary.suppressed, "both axes matched the baseline — THAT is why this cycle was quiet")
    }

    @Test
    fun an_unreadable_progress_store_skips_the_watch_pass_loudly_instead_of_unwinding_the_cycle() = runTest {
        // This read was the one unguarded call between the Steam reads and the POST: a chrome.storage
        // rejection threw out of the cycle with no event, no network trace and no console error on web.
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(historyTracked), ttlSeconds = 60),
            dealForGet = fakeDeal(dealId = "deal-1", assetId = "asset-77"),
        )
        val reader = rollbackFixture(ROLLBACK_CODE)
        val outcome = loop(
            marketplace = mp,
            reader = reader,
            progress = ThrowingReportedStore(),
            eventObserver = events,
        ).runOnce()

        assertEquals(1, events.events.count { it is LifecycleEvent.ProgressStoreFailed })
        assertEquals(0, reader.recentTransfersCalls, "no point reading Steam for a pass that cannot dedup or persist")
        assertTrue(mp.tradeStatusReports.isEmpty())
        // The cycle itself completed — the heartbeat landed and the schedule advanced.
        assertEquals(1, mp.heartbeatsSent.size)
        assertEquals(1, events.events.count { it is LifecycleEvent.CycleCompleted })
        assertEquals(0, outcome.reportsSent)
    }

    /** A store whose reported-codes read always fails, like a rejected `chrome.storage.local.get`. */
    private class ThrowingReportedStore : TrackerProgressStore by InMemoryTrackerProgressStore() {
        override suspend fun loadReported(): Map<DealId, ReportedStatus> = error("simulated storage failure")
    }

    // ---- directive re-lease (re-report of a stored outcome) ---------------------------------
    // A re-served handled directive means the earlier /trade-actions report never landed: the loop
    // re-SENDS the stored outcome, never re-executes the Steam write (the livelock fix).

    /** One loop whose first cycle's /trade-actions throws, so the outcome stays stored for a resend. */
    private class ReleaseFixture(directive: Directive) {
        val creator = FakeSteamOfferCreator()
        val canceller = FakeSteamOfferCanceller()
        val progress = InMemoryTrackerProgressStore()
        val observer = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(directive), ttlSeconds = 60),
        ).apply { reportDirectiveThrows = true }
        val clock = FakeClock()
    }

    private fun ReleaseFixture.loop() = loop(
        marketplace = mp,
        creator = creator,
        canceller = canceller,
        progress = progress,
        directivesEnabled = true,
        clock = clock,
        eventObserver = observer,
    )

    /** Advances past the heartbeat ttl so the next [TradeTrackerLoop.runOnce] heartbeats (= re-lease). */
    private fun ReleaseFixture.nextCycle() = clock.advance(61.seconds)

    @Test
    fun reserved_create_outcome_is_resent_not_reexecuted() = runTest {
        val f = ReleaseFixture(createDirective())
        val l = f.loop()
        l.runOnce() // create executes; the report throws → outcome stays stored
        f.mp.reportDirectiveThrows = false
        f.nextCycle()
        l.runOnce() // backend re-serves the handled directive
        assertEquals(1, f.creator.created.size, "a completed Steam write must never be re-executed")
        val resent = f.mp.directiveOutcomes.single()
        assertEquals(DirectiveId("dir-1"), resent.directiveId)
        assertEquals(DirectiveStatus.NEEDS_CONFIRMATION, resent.status)
        assertEquals(OfferId("offer-created"), resent.steamOfferId)
        assertEquals(DealId("deal-1"), resent.dealId)
    }

    @Test
    fun accepted_resend_prunes_stored_outcome() = runTest {
        val f = ReleaseFixture(createDirective())
        val l = f.loop()
        l.runOnce()
        f.mp.reportDirectiveThrows = false
        f.nextCycle()
        l.runOnce() // resend accepted → stored outcome pruned
        f.nextCycle()
        l.runOnce() // re-served again: nothing left to send
        assertEquals(1, f.mp.directiveOutcomes.size, "an accepted resend must not repeat")
        assertTrue(f.progress.loadDirectiveOutcomes().isEmpty(), "accepted outcome must be pruned")
        assertEquals(
            1,
            f.observer.events.filterIsInstance<LifecycleEvent.HandledDirectiveSkipped>().size,
            "a re-serve after the prune degrades to the visible skip",
        )
    }

    @Test
    fun failed_resend_keeps_outcome_for_next_cycle() = runTest {
        val f = ReleaseFixture(createDirective())
        val l = f.loop()
        l.runOnce()
        f.nextCycle()
        l.runOnce() // resend also throws → outcome must survive
        f.mp.reportDirectiveThrows = false
        f.nextCycle()
        l.runOnce() // third cycle finally lands it
        assertEquals(1, f.creator.created.size)
        assertEquals(DirectiveId("dir-1"), f.mp.directiveOutcomes.single().directiveId)
    }

    @Test
    fun reserved_handled_directive_without_stored_outcome_emits_skip_event() = runTest {
        // Legacy data: the handled id was recorded by a pre-outcome-persistence version.
        val progress = InMemoryTrackerProgressStore().apply {
            recordHandledDirectives(setOf(DirectiveId("dir-1")))
        }
        val creator = FakeSteamOfferCreator()
        val observer = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60),
        )
        loop(marketplace = mp, creator = creator, progress = progress, directivesEnabled = true, eventObserver = observer).runOnce()
        assertTrue(creator.created.isEmpty(), "handled directive must not execute")
        assertTrue(mp.directiveOutcomes.isEmpty(), "nothing stored → nothing to resend")
        val skipped = observer.events.filterIsInstance<LifecycleEvent.HandledDirectiveSkipped>().single()
        assertEquals("create_offer", skipped.kind)
        assertEquals("dir-1", skipped.directiveId)
    }

    @Test
    fun accepted_first_report_leaves_no_stored_outcome() = runTest {
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60),
        )
        loop(marketplace = mp, progress = progress, directivesEnabled = true).runOnce()
        assertTrue(progress.loadDirectiveOutcomes().isEmpty(), "happy path must prune immediately")
    }

    @Test
    fun reserved_cancel_outcome_is_resent_not_recancelled() = runTest {
        val f = ReleaseFixture(cancelDirective())
        val l = f.loop()
        l.runOnce()
        f.mp.reportDirectiveThrows = false
        f.nextCycle()
        l.runOnce()
        assertEquals(1, f.canceller.cancelledOffers.size, "a completed cancel must never be re-executed")
        val resent = f.mp.directiveOutcomes.single()
        assertEquals(DirectiveId("dir-c1"), resent.directiveId)
        assertEquals(DirectiveStatus.SUCCESS, resent.status)
    }

    @Test
    fun resend_does_not_count_as_directive_executed() = runTest {
        val f = ReleaseFixture(createDirective())
        val l = f.loop()
        l.runOnce()
        f.mp.reportDirectiveThrows = false
        f.nextCycle()
        val outcome = l.runOnce()
        assertEquals(0, outcome.directivesExecuted, "a resend is a report retry, not an execution")
    }

    @Test
    fun resend_emits_directive_outcome_resent_event() = runTest {
        val f = ReleaseFixture(createDirective())
        val l = f.loop()
        l.runOnce()
        f.mp.reportDirectiveThrows = false
        f.nextCycle()
        l.runOnce()
        val resent = f.observer.events.filterIsInstance<LifecycleEvent.DirectiveOutcomeResent>().single()
        assertEquals("create_offer", resent.kind)
        assertEquals("dir-1", resent.directiveId)
        assertEquals("NEEDS_CONFIRMATION", resent.status)
        assertTrue(resent.accepted)
    }

    @Test
    fun rejected_resend_emits_resent_event_with_accepted_false_and_report_failed() = runTest {
        val f = ReleaseFixture(createDirective())
        val l = f.loop()
        l.runOnce()
        f.mp.reportDirectiveThrows = false
        f.mp.directiveAccepted = false
        f.nextCycle()
        l.runOnce()
        val resent = f.observer.events.filterIsInstance<LifecycleEvent.DirectiveOutcomeResent>().single()
        assertEquals(false, resent.accepted)
        assertEquals(
            2,
            f.observer.events.filterIsInstance<LifecycleEvent.DirectiveReportFailed>().size,
            "both the original throw and the rejected resend must be visible",
        )
    }

    @Test
    fun create_trade_outcome_is_resent_by_next_heartbeat_when_report_fails() = runTest {
        val f = ReleaseFixture(createDirective(id = "dir-ft", dealId = "deal-ft"))
        val l = f.loop()
        l.createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        ) // report throws → outcome stays stored
        f.mp.reportDirectiveThrows = false
        l.runOnce() // heartbeat re-serves the same directive_id
        assertEquals(1, f.creator.created.size, "the FE-triggered create must not re-execute")
        val resent = f.mp.directiveOutcomes.single()
        assertEquals(DirectiveId("dir-ft"), resent.directiveId)
        assertEquals(DealId("deal-ft"), resent.dealId)
        assertEquals(OfferId("offer-created"), resent.steamOfferId)
    }

    @Test
    fun rejected_failed_create_report_is_retried_by_reexecution_not_resend() = runTest {
        // A FAILED create wrote nothing to Steam: it stays unhandled (safe to re-execute) and no
        // outcome is stored — the resend path is only for completed writes.
        val creator = FakeSteamOfferCreator(result = CreateOfferResult.Failed("boom"))
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60),
        ).apply { directiveAccepted = false }
        val clock = FakeClock()
        val l = loop(marketplace = mp, creator = creator, progress = progress, directivesEnabled = true, clock = clock)
        l.runOnce()
        clock.advance(61.seconds)
        l.runOnce()
        assertEquals(2, creator.created.size, "an unacknowledged FAILED create is retried by re-execution")
        assertTrue(progress.loadDirectiveOutcomes().isEmpty())
    }

    @Test
    fun accepted_failed_create_report_is_still_retried_by_reexecution() = runTest {
        // Regression: an *accepted* FAILED create report means "received", not "stop retrying". The
        // create wrote nothing to Steam, so it must stay unhandled and re-execute on the next re-lease
        // (the backend ends retries by not re-serving the directive), not be filed as permanently handled.
        val creator = FakeSteamOfferCreator(result = CreateOfferResult.Failed("boom"))
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60),
        ) // directiveAccepted defaults to true
        val clock = FakeClock()
        val l = loop(marketplace = mp, creator = creator, progress = progress, directivesEnabled = true, clock = clock)
        l.runOnce()
        clock.advance(61.seconds) // past the ttl so the second cycle heartbeats and re-leases
        l.runOnce()
        assertEquals(2, creator.created.size, "an accepted FAILED create must still be retried by re-execution")
        assertEquals(2, mp.directiveOutcomes.size, "each re-execution reports its FAILED outcome")
        assertTrue(progress.loadHandledDirectives().isEmpty(), "a write-nothing failure must not be filed as handled")
        assertTrue(progress.loadDirectiveOutcomes().isEmpty())
    }

    @Test
    fun accepted_failed_cancel_report_is_still_retried_by_reexecution() = runTest {
        // Same regression for cancel_offer: a failed cancel changed nothing on Steam, so an accepted
        // FAILED report must not suppress the retry on the next re-lease.
        val canceller = FakeSteamOfferCanceller(failWith = IllegalStateException("cancel boom"))
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(cancelDirective()), ttlSeconds = 60),
        ) // directiveAccepted defaults to true
        val clock = FakeClock()
        val l = loop(marketplace = mp, canceller = canceller, progress = progress, directivesEnabled = true, clock = clock)
        l.runOnce()
        clock.advance(61.seconds)
        l.runOnce()
        assertEquals(2, mp.directiveOutcomes.size, "an accepted FAILED cancel must still be retried by re-execution")
        assertTrue(progress.loadHandledDirectives().isEmpty(), "a write-nothing cancel failure must not be filed as handled")
        assertTrue(progress.loadDirectiveOutcomes().isEmpty())
    }

    // ---- report_inventory ------------------------------------------------------------------

    private fun inventoryDirective(id: String = "dir-inv", vararg assetIds: String = arrayOf("a1", "a2")) = Directive(
        directiveId = DirectiveId(id),
        action = DirectiveAction.REPORT_INVENTORY,
        assetIds = assetIds.map { AssetId(it) },
        contextId = 2,
    )

    @Test
    fun noop_inventory_reader_reports_incomplete_scan_not_empty_complete_one() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(inventoryDirective()), ttlSeconds = 60),
        )
        loop(marketplace = mp, directivesEnabled = true).runOnce()
        assertEquals(1, mp.inventoryReports.size)
        assertEquals(false, mp.inventoryReports[0].scanComplete, "NoOp reader must not claim a complete scan")
        assertTrue(mp.inventoryReports[0].presentAssetIds.isEmpty())
    }

    @Test
    fun real_inventory_reader_reports_present_subset_with_complete_scan() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(inventoryDirective()), ttlSeconds = 60),
        )
        val inventory = FakeSteamInventoryReader(assets = setOf(AssetId("a1"), AssetId("other")))
        loop(marketplace = mp, inventoryReader = inventory, directivesEnabled = true).runOnce()
        assertEquals(1, mp.inventoryReports.size)
        assertEquals(true, mp.inventoryReports[0].scanComplete)
        assertEquals(listOf(AssetId("a1")), mp.inventoryReports[0].presentAssetIds)
    }

    @Test
    fun truncated_scan_forwards_scan_complete_false_with_the_partial_subset() = runTest {
        // A truncated read must never claim completeness: the backend would treat every unread on-sale
        // asset as stale and cancel it. The partial intersection still rides along — the backend ignores
        // the payload when the flag is false, and partial is strictly safer than empty.
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(inventoryDirective()), ttlSeconds = 60),
        )
        val inventory = FakeSteamInventoryReader(assets = setOf(AssetId("a1")), complete = false)
        loop(marketplace = mp, inventoryReader = inventory, directivesEnabled = true).runOnce()
        assertEquals(1, mp.inventoryReports.size)
        assertEquals(false, mp.inventoryReports[0].scanComplete)
        assertEquals(listOf(AssetId("a1")), mp.inventoryReports[0].presentAssetIds)
    }

    @Test
    fun throwing_inventory_reader_reports_incomplete_scan() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(inventoryDirective()), ttlSeconds = 60),
        )
        val inventory = FakeSteamInventoryReader(throws = true)
        loop(marketplace = mp, inventoryReader = inventory, directivesEnabled = true).runOnce()
        assertEquals(1, mp.inventoryReports.size)
        assertEquals(false, mp.inventoryReports[0].scanComplete)
        assertTrue(mp.inventoryReports[0].presentAssetIds.isEmpty())
    }

    @Test
    fun truncated_scan_is_not_marked_handled_so_a_re_lease_rescans() = runTest {
        // An accepted-but-incomplete scan told the backend nothing actionable. Marking it handled would
        // single-flight the directive out and the inventory would never be re-scanned, so the seller stays
        // permanently un-reconciled.
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(inventoryDirective()), ttlSeconds = 60),
        )
        val inventory = FakeSteamInventoryReader(assets = setOf(AssetId("a1")), complete = false)
        val l = loop(marketplace = mp, inventoryReader = inventory, progress = progress, directivesEnabled = true)
        l.runOnce()
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(2, inventory.calls, "an incomplete scan must be retried when the backend re-leases it")
        assertEquals(2, mp.inventoryReports.size)
    }

    @Test
    fun complete_scan_is_marked_handled_and_not_rescanned() = runTest {
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(inventoryDirective()), ttlSeconds = 60),
        )
        val inventory = FakeSteamInventoryReader(assets = setOf(AssetId("a1")), complete = true)
        val l = loop(marketplace = mp, inventoryReader = inventory, progress = progress, directivesEnabled = true)
        l.runOnce()
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(1, inventory.calls, "a complete scan is single-flighted by directive id")
    }

    // ---- watch + report --------------------------------------------------------------------

    @Test
    fun active_tracking_reports_new_offer_code() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 2))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value)),
                ttlSeconds = 60,
            ),
        )
        val outcome = loop(marketplace = mp, reader = reader).runOnce()
        assertEquals(1, mp.tradeStatusReports.size)
        assertEquals(DealId("d1"), mp.tradeStatusReports[0].dealId)
        assertEquals(2, mp.tradeStatusReports[0].steamStatusCode)
        assertEquals(1, outcome.reportsSent)
    }

    @Test
    fun unchanged_offer_code_is_not_re_reported_on_second_cycle() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 2))
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value)),
                ttlSeconds = 60,
            ),
        )
        val l = loop(marketplace = mp, reader = reader, progress = progress)
        l.runOnce()
        l.runOnce()
        assertEquals(1, mp.tradeStatusReports.size, "same code must not be re-reported on second cycle")
    }

    @Test
    fun changed_offer_code_is_reported_on_second_cycle() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 2))
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value)),
                ttlSeconds = 60,
            ),
        )
        val l = loop(marketplace = mp, reader = reader, progress = progress)
        l.runOnce()
        reader.offers = mapOf(offerId to 3)
        l.runOnce()
        assertEquals(2, mp.tradeStatusReports.size, "changed code triggers a second report")
        assertEquals(3, mp.tradeStatusReports.last().steamStatusCode)
    }

    @Test
    fun rejected_trade_status_result_is_resent_next_cycle() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 2))
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(tracked("d1", offerId.value)), ttlSeconds = 60),
        ).apply { tradeStatusAccepted = false }
        val l = loop(marketplace = mp, reader = reader, progress = progress)
        l.runOnce()
        l.runOnce()
        assertEquals(2, mp.tradeStatusReports.size, "a rejected report must not enter the dedup baseline")
    }

    // ---- proof routing ---------------------------------------------------------------------

    @Test
    fun decisive_code_with_proof_required_submits_proof() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val notary = FakeNotaryProver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        )
        val outcome = loop(marketplace = mp, reader = reader, notary = notary).runOnce()
        assertEquals(1, mp.proofsSubmitted.size, "proof must be submitted for decisive + proof_required")
        assertEquals(DealId("d1"), mp.proofsSubmitted[0].dealId)
        assertEquals(1, outcome.proofsSubmitted)
    }

    @Test
    fun failed_proof_submission_is_retried_next_cycle() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val notary = FakeNotaryProver()
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        ).apply { submitProofThrows = true }
        val l = loop(marketplace = mp, reader = reader, notary = notary, progress = progress)
        l.runOnce()
        l.runOnce()
        assertEquals(2, mp.proofsSubmitted.size, "an undelivered decisive proof must be retried, not deduped away")
    }

    @Test
    fun delivered_but_unverified_proof_is_not_retried() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val notary = FakeNotaryProver()
        val progress = InMemoryTrackerProgressStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        ).apply { proofVerified = false }
        val l = loop(marketplace = mp, reader = reader, notary = notary, progress = progress)
        l.runOnce()
        l.runOnce()
        assertEquals(1, mp.proofsSubmitted.size, "verified=false is terminal (MVP mock verify) — no retry loop")
    }

    @Test
    fun the_revert_watch_goes_sparse_once_every_watched_deal_has_been_seen() = runTest {
        // The contract calls the revert watch the sparse axis (~hourly) but nothing enforced it:
        // PollClass.RevertWatch is selected by no production caller and there was no interval gate inside a
        // cycle, so GetTradeHistory fired on EVERY wake — 47 reads x 14.6 KB in 72 minutes on a real session.
        val clock = FakeClock()
        val reader = rollbackFixture(3)
        val l = rollbackLoop(reader, clock = clock)

        l.runOnce()
        assertEquals(1, reader.recentTransfersCalls, "the first cycle must read: nothing is baselined yet")

        // Well inside the 1 h revert interval.
        clock.advance(5.minutes)
        l.runOnce()
        assertEquals(1, reader.recentTransfersCalls, "a cycle inside the interval must not spend a history read")

        clock.advance(56.minutes)
        l.runOnce()
        assertEquals(2, reader.recentTransfersCalls, "the interval elapsed — the revert watch runs again")
    }

    @Test
    fun an_unobserved_deal_is_read_promptly_so_its_settlement_window_is_not_lost() = runTest {
        // This is why the gate is not a plain interval. Steam CLEARS `time_settlement` on the row it flips to
        // 12, so a rollback carries no protection window and the only chance to capture one is a read taken
        // while the row still had it. A flat hourly gate would lose it for any deal that rolls back inside
        // its first hour. No transfers here, so the read correlates to nothing and nothing is baselined.
        val clock = FakeClock()
        val reader = FakeSteamReadClient(initialTransfers = emptyList())
        val l = rollbackLoop(reader, clock = clock)

        l.runOnce()
        clock.advance(1.minutes)
        l.runOnce()
        clock.advance(1.minutes)
        l.runOnce()

        assertEquals(3, reader.recentTransfersCalls, "an unobserved history axis must keep being read promptly")
    }

    @Test
    fun a_rollback_missing_its_actor_stays_on_the_prompt_cadence() = runTest {
        // The second reason the gate is not a plain interval, and the one a pre-existing test caught rather
        // than reasoning: Steam signs out whoever reversed a trade, so attribution usually fails on the very
        // tick a 12 appears — and the backend reads a missing actor as "undecided" and PARKS the deal. Going
        // sparse there would leave it parked for an hour.
        val clock = FakeClock()
        val reader = rollbackFixture(ROLLBACK_CODE)
        // No notification reader wired here, so the actor never resolves — the state this must not abandon.
        val l = rollbackLoop(reader, clock = clock)

        l.runOnce()
        clock.advance(2.minutes)
        l.runOnce()
        clock.advance(2.minutes)
        l.runOnce()

        assertTrue(
            reader.recentTransfersCalls >= 3,
            "a rollback whose actor is unresolved must keep re-reading, not wait out the interval " +
                "(calls=${reader.recentTransfersCalls})",
        )
    }

    @Test
    fun a_failed_history_read_does_not_start_the_sparse_interval() = runTest {
        // Stamping a failure would turn one Steam blip into an hour of not watching for a rollback.
        val clock = FakeClock()
        val reader = rollbackFixture(3)
        val l = rollbackLoop(reader, clock = clock)

        l.runOnce() // succeeds, baselines status 3, stamps the interval
        assertEquals(1, reader.recentTransfersCalls)

        clock.advance(61.minutes)
        reader.recentTransfersThrows = true
        l.runOnce() // due, attempted, failed — must NOT stamp
        assertEquals(2, reader.recentTransfersCalls)

        reader.recentTransfersThrows = false
        clock.advance(1.minutes) // nowhere near a new interval
        l.runOnce()
        assertEquals(3, reader.recentTransfersCalls, "a failed read must leave the axis due, not stamped")
    }

    // ---- the per-cycle proving budget ------------------------------------------------------------
    //
    // Proofs are minted one at a time, inline, under `cycleMutex`, so N due proofs serialize into N × the
    // prover's own timeout of held mutex — and the heartbeat cannot run for any of it. Measured on dev
    // 2026-08-26: six `proofRequired` deals against a prover that wedged on every attempt held one cycle for
    // ~16 min and left 412 s between heartbeats on a 90 s advertised cadence, so presence lapsed and the FE
    // told the seller their extension was offline. The budget bounds the CHAIN; a single proof outliving the
    // whole cadence is the host's own timeout to cap.

    /** Three `proof_required` deals, all sitting on the decisive offer code, on one 60s-ttl heartbeat. */
    private fun threeProofDeals() = FakeMarketplaceClient(
        heartbeatResponse = HeartbeatResponse(
            activeTracking = listOf(
                tracked("d1", "offer-1", proofRequired = true),
                tracked("d2", "offer-2", proofRequired = true),
                tracked("d3", "offer-3", proofRequired = true),
            ),
            ttlSeconds = 60,
        ),
    )

    @Test
    fun proving_stops_once_the_next_heartbeat_is_due() = runTest {
        val clock = FakeClock()
        val notary = FakeNotaryProver()
        val events = RecordingEventObserver()
        val mp = threeProofDeals()
        // 40s per proof against a 60s ttl: the first fits, the second lands exactly on the due time, and
        // everything after it must be given up rather than held for.
        notary.onProve = { clock.advance(40.seconds) }
        val l = loop(
            marketplace = mp,
            reader = FakeSteamReadClient(
                initialOffers = mapOf(OfferId("offer-1") to 3, OfferId("offer-2") to 3, OfferId("offer-3") to 3),
            ),
            notary = notary,
            clock = clock,
            eventObserver = events,
        )

        l.runOnce()

        assertEquals(2, notary.proven.size, "the chain must stop at the heartbeat's due time, not run past it")
        val suppressed = events.events.filterIsInstance<LifecycleEvent.ProofSuppressed>()
        assertEquals(1, suppressed.size, "the proof the budget refused must say so, not vanish")
        assertEquals("this cycle's proving budget is spent; the next heartbeat is due", suppressed[0].reason)
        // The report it would have corroborated is withheld, not sent — a budget skip is not a verdict.
        assertEquals(2, mp.tradeStatusReports.size)
        val deferred = events.events.filterIsInstance<LifecycleEvent.TradeStatusReportDeferred>()
        assertEquals(listOf("d3"), deferred.map { it.dealId })
    }

    @Test
    fun a_budget_skipped_proof_is_retried_on_the_next_cycle() = runTest {
        // The skip must cost the transition nothing: its code stays out of the dedup baseline, so the next
        // wake re-plans it. Otherwise the budget would trade a starved heartbeat for a stranded deal.
        val clock = FakeClock()
        val notary = FakeNotaryProver()
        val mp = threeProofDeals()
        notary.onProve = { clock.advance(40.seconds) }
        val l = loop(
            marketplace = mp,
            reader = FakeSteamReadClient(
                initialOffers = mapOf(OfferId("offer-1") to 3, OfferId("offer-2") to 3, OfferId("offer-3") to 3),
            ),
            notary = notary,
            clock = clock,
        )

        l.runOnce()
        assertEquals(2, notary.proven.size)
        notary.onProve = null // the prover recovers; the leftover must now go through
        l.runOnce()

        assertEquals(
            DealId("d3"),
            notary.proven.last().first,
            "the deal the budget skipped must be proved on a later cycle, not dropped",
        )
        assertEquals(3, mp.tradeStatusReports.size)
    }

    @Test
    fun enabling_a_proven_write_is_refused_because_the_prover_would_perform_it() {
        // A proven write is a write the PROVER makes — TLSN requires the prover to be the TLS client, so there
        // is no way to witness the POST `SteamOfferCreator` already made. Enabling one therefore has to REPLACE
        // this loop's write, and that routing does not exist: unguarded, one create directive would produce two
        // live Steam offers against a partner quota of five.
        val failure = assertFailsWith<IllegalArgumentException> {
            loop(
                tunables = TrackerConfig.defaults().let { defaults ->
                    defaults.copy(
                        notary = defaults.notary.copy(
                            reads = ProvenReadRegistry(
                                enabled = setOf(ProvenReadKind.TRADE_OFFER, ProvenReadKind.CANCEL_OFFER),
                            ),
                        ),
                    )
                },
            )
        }
        assertTrue("CANCEL_OFFER" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun the_loop_proves_each_axis_through_the_endpoint_that_witnesses_it() = runTest {
        // The axis→endpoint mapping asserted rather than trusted. It is not the identity: the history axis is
        // witnessed by `GetTradeStatus`, not the `GetTradeHistory` the polling path reads, because a filterless
        // reveal path cannot address a row in a 50-row response.
        val notary = FakeNotaryProver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", "offer-1", proofRequired = true)),
                ttlSeconds = 60,
            ),
        )
        val l = loop(
            marketplace = mp,
            reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3)),
            notary = notary,
        )

        l.runOnce()

        assertEquals(
            listOf(DealId("d1") to ProvenReadKind.TRADE_OFFER),
            notary.provenKinds,
            "an offer-axis transition must be witnessed by GetTradeOffer",
        )
    }

    @Test
    fun the_first_proof_of_a_cycle_is_never_refused_by_the_budget() = runTest {
        // Progress has to be guaranteed: if a single proof can outlast the whole cadence, blocking the first
        // one would mean no deal ever gets proved again. One per cycle is the floor.
        val clock = FakeClock()
        val notary = FakeNotaryProver()
        val mp = threeProofDeals()
        notary.onProve = { clock.advance(10.minutes) } // one proof, ten times the cadence
        val l = loop(
            marketplace = mp,
            reader = FakeSteamReadClient(
                initialOffers = mapOf(OfferId("offer-1") to 3, OfferId("offer-2") to 3, OfferId("offer-3") to 3),
            ),
            notary = notary,
            clock = clock,
        )

        l.runOnce()

        assertEquals(1, notary.proven.size, "exactly one — the first always runs, the rest are refused")
    }

    // ---- the backend-seeded dedup baseline -------------------------------------------------------

    @Test
    fun a_backend_that_sends_no_last_offer_code_behaves_exactly_as_before() = runTest {
        // The guarantee that matters until the backend ships the field: absent must be byte-for-byte today.
        // Asserted as a PAIR against the seeded case, so this cannot pass by the feature being inert.
        val offers = mapOf(OfferId("offer-1") to 3)
        val absent = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(activeTracking = listOf(tracked("d1", "offer-1", proofRequired = true)), ttlSeconds = 60),
        )
        val notaryAbsent = FakeNotaryProver()
        loop(marketplace = absent, reader = FakeSteamReadClient(initialOffers = offers), notary = notaryAbsent).runOnce()

        assertEquals(1, absent.tradeStatusReports.size, "no seed → the transition is detected and reported")
        assertEquals(1, notaryAbsent.proven.size, "…and proved, exactly as before the field existed")

        val seeded = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", "offer-1", proofRequired = true).copy(lastOfferCode = 3)),
                ttlSeconds = 60,
            ),
        )
        val notarySeeded = FakeNotaryProver()
        loop(marketplace = seeded, reader = FakeSteamReadClient(initialOffers = offers), notary = notarySeeded).runOnce()

        assertEquals(emptyList(), seeded.tradeStatusReports, "the backend already has code 3 — nothing to report")
        assertEquals(emptyList(), notarySeeded.proven, "…and nothing to prove")
    }

    // ---- the learned online-decryption budget ----------------------------------------------------

    @Test
    fun a_budget_refusal_teaches_the_next_attempt_what_the_read_needs() = runTest {
        // The prover is the only oracle for this number: the response head's size is a property of the offer
        // (it grows with item count), and a refusal states the requirement outright. Learned per deal and
        // persisted, so the MPC session it cost is spent once rather than on every wake.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        val notary = FakeNotaryProver()
        notary.proveThrows = IllegalStateException(
            "record layer error: attempted to decrypt more data in the online phase than was configured, " +
                "increase `max_recv_online` in the config: current=2048, additional=900, max=",
        )
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val l = proofEnforcedLoop(mp, progress, clock = clock, notary = notary)

        l.runOnce()
        assertEquals(listOf<Int?>(null), notary.provenOnlineBudgets, "the first attempt runs on the configured default")

        // 2048 + 900 = 2948, +25% = 3685 — above the 1024 floor, so it is worth remembering.
        assertEquals(mapOf(DealId("d1") to 3685), progress.loadOnlineBudgets())

        notary.proveThrows = null
        clock.advance(2.minutes)
        l.runOnce()

        assertEquals(3685, notary.provenOnlineBudgets.last(), "the retry must carry what the refusal taught")
    }

    @Test
    fun a_learned_budget_is_forgotten_once_its_deal_stops_being_tracked() = runTest {
        // The row is permanent storage, so without this a device accumulates one per deal it ever proved —
        // the same bound the accepted-proof ledger keeps, for the same reason.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        val notary = FakeNotaryProver()
        notary.proveThrows = IllegalStateException(
            "record layer error: ... online phase ...: current=2048, additional=900, max=",
        )
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val l = proofEnforcedLoop(mp, progress, clock = clock, notary = notary)

        l.runOnce()
        assertEquals(mapOf(DealId("d1") to 3685), progress.loadOnlineBudgets(), "learned while the deal is live")

        // The deal settles and leaves `active_tracking`, but another one keeps the pass reaching a proof —
        // the prune must not depend on the terminated deal still being mentioned.
        mp.heartbeatResponse = HeartbeatResponse(
            activeTracking = listOf(tracked("d2", "offer-1", proofRequired = true)),
            ttlSeconds = 60,
        )
        clock.advance(2.minutes)
        l.runOnce()

        // d2 learns its own row from the same refusal — which is the point: the prune drops what left the
        // tracked set, rather than clearing the map whenever it runs.
        assertEquals(mapOf(DealId("d2") to 3685), progress.loadOnlineBudgets(), "d1 is gone, so its row must be too")
    }

    @Test
    fun a_wedge_teaches_nothing_about_the_budget() = runTest {
        // Every proof failure reaches the parser, and only this one kind says anything. A wedge must not be
        // mistaken for a sizing problem — raising the budget for it would make the next attempt cost MORE.
        val progress = InMemoryTrackerProgressStore()
        val notary = FakeNotaryProver()
        notary.proveThrows = IllegalStateException("prover worker discarded: no liveness ping for 25000ms — wedged inside the wasm")
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }

        proofEnforcedLoop(mp, progress, notary = notary).runOnce()

        assertEquals(emptyMap(), progress.loadOnlineBudgets())
    }

    // ---- the parked-prover breaker ---------------------------------------------------------------
    //
    // A failed proof is not free: each attempt garbles and uploads `maxSentData` bytes of circuits, measured
    // at ~30 MB, and the loop re-plans the same intent on every wake for as long as its report stays withheld.
    // On dev 2026-08-26 a prover wedged on 12 consecutive attempts across six tracked deals: ~230 MB to the
    // notary in ten minutes, zero proofs. The per-cycle budget above bounds the chain inside one cycle; this
    // bounds the retry across them.

    /** A prover that always fails *generation* — the wedge's shape, which the marketplace fake cannot mimic. */
    private fun brokenNotary() = FakeNotaryProver().apply { proveThrows = IllegalStateException("wedged inside the wasm") }

    /**
     * A prover whose failures cost real time on [clock] — the live wedge takes 30-75 s. Needed wherever a test
     * expects the breaker to arm: an instantly-thrown failure is BELOW `countedFailureMinMs` and deliberately
     * does not count (see `instant_failures_do_not_arm_the_breaker`).
     */
    private fun sloweredBrokenNotary(clock: FakeClock) = brokenNotary().apply { onProve = { clock.advance(30.seconds) } }

    /** The three-deal, three-offer setup every parked-prover test shares. */
    private fun threeProofLoop(
        notary: NotaryProver,
        clock: FakeClock = FakeClock(),
        events: EventObserver = NoOpEventObserver,
        // Threshold pinned rather than inherited from the shipped default: these tests are about the mechanism
        // (park, persist, resume), and tuning the default is a separate decision that must not break them.
        notaryThrottle: NotaryProofThrottleStore = PersistedNotaryProofThrottleStore(
            limits = NotaryBreakerConfig(breakerThreshold = 2),
            random = Random(7),
        ),
        marketplace: FakeMarketplaceClient = threeProofDeals(),
    ) = loop(
        marketplace = marketplace,
        reader = FakeSteamReadClient(
            initialOffers = mapOf(OfferId("offer-1") to 3, OfferId("offer-2") to 3, OfferId("offer-3") to 3),
        ),
        notary = notary,
        clock = clock,
        eventObserver = events,
        notaryThrottle = notaryThrottle,
    )

    private fun parkedSuppressions(events: RecordingEventObserver) = events.events
        .filterIsInstance<LifecycleEvent.ProofSuppressed>()
        .filter { it.reason == "the prover is parked after repeated proof failures" }

    @Test
    fun two_failures_park_the_prover_and_the_rest_of_the_cycle_is_given_up() = runTest {
        // The dev incident in miniature: three deals, a prover that fails every time. Deals one and two pay
        // for the diagnosis; the third must not.
        val clock = FakeClock()
        val notary = sloweredBrokenNotary(clock)
        val events = RecordingEventObserver()

        threeProofLoop(notary, clock, events = events).runOnce()

        assertEquals(2, notary.proven.size, "the third deal must not spend an MPC session on a parked prover")
        val parked = parkedSuppressions(events)
        assertEquals(1, parked.size)
        assertTrue(
            (parked.single().retryAfterSeconds ?: 0) > 0,
            "the skip must carry a deadline as a field, or a host has to parse English to show a countdown",
        )
    }

    @Test
    fun a_parked_prover_stays_parked_on_the_next_cycle() = runTest {
        val clock = FakeClock()
        val notary = sloweredBrokenNotary(clock)
        val l = threeProofLoop(notary, clock)

        l.runOnce()
        assertEquals(2, notary.proven.size)
        clock.advance(20.seconds) // inside the first rung (>= the 30s floor)
        l.runOnce()

        assertEquals(2, notary.proven.size, "a wake inside the cooldown must mint nothing at all")
    }

    @Test
    fun proving_resumes_once_the_cooldown_elapses() = runTest {
        // The park is a cooldown, not a latch: a prover that recovers has to be given another chance, or a
        // transient notary outage would strand every deal until the extension restarted.
        val clock = FakeClock()
        val notary = sloweredBrokenNotary(clock)
        val l = threeProofLoop(notary, clock)

        l.runOnce()
        assertEquals(2, notary.proven.size)
        clock.advance(31.minutes) // past the 30-min ceiling, so past any rung
        notary.proveThrows = null // …and the prover has recovered
        notary.onProve = null
        l.runOnce()

        assertTrue(notary.proven.size > 2, "an elapsed cooldown must let the prover try again")
    }

    @Test
    fun the_park_survives_a_worker_respawn() = runTest {
        // The whole reason it is persisted: on web the MV3 worker dies between most cycles, so an in-memory
        // cooldown would be forgotten every few minutes and each spawn would re-spend ~30 MB.
        val clock = FakeClock()
        // One shared device store, two loop instances — the storage row is what crosses between them, so each
        // instance builds its own throttle over it exactly as a respawned worker would.
        val storage = InMemoryDeviceKeyValueStore()
        fun throttleOverStorage() = PersistedNotaryProofThrottleStore(
            limits = NotaryBreakerConfig(breakerThreshold = 2),
            storage = storage,
            random = Random(7),
        )
        threeProofLoop(sloweredBrokenNotary(clock), clock, notaryThrottle = throttleOverStorage()).runOnce()

        val respawnedNotary = brokenNotary()
        threeProofLoop(respawnedNotary, clock, notaryThrottle = throttleOverStorage()).runOnce()

        assertEquals(0, respawnedNotary.proven.size, "a fresh instance must honour the persisted cooldown")
    }

    @Test
    fun instant_failures_do_not_arm_the_breaker() = runTest {
        // The post-reload shape from dev 2026-08-26: the core still held a proving context whose offscreen
        // realm was gone, two proofs failed in 7 ms and 15 ms with "prover worker errored", and that armed a
        // 40 s park over nine healthy deals. A failure that never engaged the prover spent nothing the breaker
        // exists to bound and proves nothing about the prover — all three deals must still be attempted.
        val notary = brokenNotary() // throws immediately: FakeClock does not advance during the attempt
        val events = RecordingEventObserver()

        threeProofLoop(notary, events = events).runOnce()

        assertEquals(3, notary.proven.size, "instant failures must not park the deals behind them")
        assertTrue(parkedSuppressions(events).isEmpty(), "nothing should have been parked")
    }

    @Test
    fun slow_failures_still_arm_the_breaker() = runTest {
        // The counterpart that keeps the breaker meaningful: a failure that consumed real prover time (the
        // wedge takes 30-75 s) counts, exactly as before the floor existed.
        val clock = FakeClock()
        val notary = sloweredBrokenNotary(clock) // each attempt costs 30 s before failing
        val events = RecordingEventObserver()

        threeProofLoop(notary, clock, events = events).runOnce()

        assertEquals(2, notary.proven.size, "two slow failures must park the third deal")
        assertEquals(1, parkedSuppressions(events).size)
    }

    @Test
    fun a_generated_proof_between_failures_prevents_the_park() = runTest {
        // The streak is evidence *about the prover*, so a working proof is evidence against it. Without the
        // clearing, alternating luck would accumulate to a park and take a healthy prover offline.
        val notary = FakeNotaryProver()
        val events = RecordingEventObserver()
        var call = 0
        // fail, succeed, fail — so the streak reaches 1, resets, and reaches 1 again: never the threshold.
        notary.onProve = {
            call += 1
            notary.proveThrows = if (call % 2 == 1) IllegalStateException("one blip") else null
        }
        threeProofLoop(notary, events = events).runOnce()

        assertEquals(3, notary.proven.size, "all three must be attempted — the streak never reached two in a row")
        assertTrue(parkedSuppressions(events).isEmpty(), "nothing should have been parked")
    }

    @Test
    fun a_corroborated_transition_is_reported_even_when_the_cycle_has_no_proving_budget_left() = runTest {
        // Regression: the budget check used to sit ABOVE the already-accepted skip, so a transition the
        // backend had already vouched for was suppressed as "budget spent" and its report withheld — for a
        // reason that has nothing to do with it, since that path mints no proof at all.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        val notary = FakeNotaryProver()
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val l = proofEnforcedLoop(mp, progress, clock = clock, notary = notary)

        l.runOnce() // mints the proof, backend verifies it, report refused → verdict stored
        assertEquals(setOf(offerIntent), progress.loadAcceptedProofs().keys)

        // A later cycle whose budget is already gone: the heartbeat is due the moment the pass begins.
        val reportsBefore = mp.tradeStatusReports.size
        notary.onProve = { clock.advance(10.minutes) }
        clock.advance(2.minutes)
        l.runOnce()

        assertEquals(
            reportsBefore + 1,
            mp.tradeStatusReports.size,
            "an already-corroborated report must still go out — it needs no prover, so no proving gate applies",
        )
    }

    // ---- the accepted-proof safeguard ------------------------------------------------------------
    //
    // The mirror image of the refused-proof latch below, and the regime neither it nor the dedup baseline can
    // bound: the backend answers `/notary` with `verified: true` and then refuses the report it corroborates
    // with `P2P_PROOF_REQUIRED` anyway (observed on dev 2026-08-25 at the payout place, history 3, inside
    // Steam's 7-day protection window). Only an ACCEPTED report is baselined, so the transition stays live and
    // the loop re-minted the identical proof every wake — 17.5 s and 63 MB to the notary per attempt.

    /** One `proof_required` deal sitting on offer 3, with both backend verdicts under the test's control. */
    private fun proofEnforcedLoop(
        mp: FakeMarketplaceClient,
        progress: TrackerProgressStore,
        events: EventObserver = NoOpEventObserver,
        clock: FakeClock = FakeClock(),
        tunables: TrackerConfig = TrackerConfig.defaults(),
        notary: NotaryProver = FakeNotaryProver(),
    ) = loop(
        marketplace = mp,
        reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3)),
        notary = notary,
        progress = progress,
        clock = clock,
        eventObserver = events,
        tunables = tunables,
    )

    private fun proofEnforcedMarketplace(dealId: String = "d1") = FakeMarketplaceClient(
        heartbeatResponse = HeartbeatResponse(
            activeTracking = listOf(tracked(dealId, "offer-1", proofRequired = true)),
            ttlSeconds = 60,
        ),
    )

    private val offerIntent = ProofIntent(DealId("d1"), TradeStatusSource.OFFER, 3)

    @Test
    fun an_accepted_proof_is_not_re_minted_while_its_report_keeps_being_refused() = runTest {
        val progress = InMemoryTrackerProgressStore()
        val events = RecordingEventObserver()
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val l = proofEnforcedLoop(mp, progress, events)

        repeat(5) { l.runOnce() }

        assertEquals(
            1,
            mp.proofsSubmitted.size,
            "a proof the backend already verified must not be re-minted just because the report it " +
                "corroborates was refused — each re-mint is a full MPC session",
        )
        // …and the report keeps going out, which is what separates this from a REFUSED proof. The backend
        // holds corroboration, so withholding the report too would leave the deal unproven AND silent, and
        // the deal can only move on the cycle the backend finally accepts it.
        assertEquals(5, mp.tradeStatusReports.size, "the report must still be retried on every cycle")
        val suppressed = events.events.filterIsInstance<LifecycleEvent.ProofSuppressed>()
        assertEquals(4, suppressed.size, "each skipped cycle must say it is idle by choice, not silently")
        assertEquals("the backend already verified a proof for this transition", suppressed[0].reason)
        assertEquals("offer", suppressed[0].source)
        // The verdict survives for the next wake — it is what the skip is keyed on.
        assertEquals(setOf(offerIntent), progress.loadAcceptedProofs().keys)
    }

    @Test
    fun an_accepted_proof_is_re_minted_once_its_reuse_window_elapses() = runTest {
        // Why the safeguard is a window and not a permanent latch: the verifier bounds replay
        // (`provenance.max_attestation_age`), so a backend that only becomes ready to accept the report after
        // the held proof has aged out must still be able to get a fresh one. A permanent latch would strand
        // the deal — and its escrow — forever.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val l = proofEnforcedLoop(mp, progress, clock = clock)

        l.runOnce()
        clock.advance(59.minutes) // still inside the 1h default
        l.runOnce()
        assertEquals(1, mp.proofsSubmitted.size, "the window has not elapsed yet")

        clock.advance(2.minutes) // now past it
        l.runOnce()
        assertEquals(2, mp.proofsSubmitted.size, "an aged-out verdict must be re-proved, not trusted forever")
    }

    @Test
    fun a_zero_reuse_window_mints_a_proof_every_cycle() = runTest {
        // The remote kill-switch. If the reuse ever turns out to be the wrong call for a backend, `0` restores
        // the pre-safeguard behaviour without a client release.
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val tunables = TrackerConfig.defaults().let { it.copy(notary = it.notary.copy(acceptedProofTtlMs = 0)) }
        val l = proofEnforcedLoop(mp, InMemoryTrackerProgressStore(), tunables = tunables)

        repeat(3) { l.runOnce() }

        assertEquals(3, mp.proofsSubmitted.size, "a zero window must disable the reuse entirely")
    }

    @Test
    fun an_accepted_verdict_is_dropped_once_its_report_is_accepted() = runTest {
        // Nothing left to corroborate: the code is in the dedup baseline, so no further intent is planned for
        // it. Pruned rather than left to expire so the ledger only ever holds live work.
        val progress = InMemoryTrackerProgressStore()
        val mp = proofEnforcedMarketplace() // tradeStatusAccepted defaults to true

        proofEnforcedLoop(mp, progress).runOnce()

        assertEquals(1, mp.proofsSubmitted.size)
        assertEquals(1, mp.tradeStatusReports.size)
        assertEquals(emptyMap(), progress.loadAcceptedProofs(), "an accepted report leaves nothing to corroborate")
    }

    @Test
    fun a_refused_proof_drops_an_earlier_acceptance_of_the_same_transition() = runTest {
        // Fail-closed across a respawn. The refused-proof latch is in-memory, so without this a worker restart
        // would reuse a verdict the backend has since overturned and keep sending a report it cannot accept.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val l = proofEnforcedLoop(mp, progress, clock = clock)

        l.runOnce()
        assertEquals(setOf(offerIntent), progress.loadAcceptedProofs().keys)

        mp.proofVerified = false
        clock.advance(61.minutes) // past the window, so a fresh proof is minted — and refused
        l.runOnce()

        assertEquals(2, mp.proofsSubmitted.size)
        assertEquals(emptyMap(), progress.loadAcceptedProofs(), "the backend's latest word is a refusal")
    }

    @Test
    fun an_accepted_verdict_is_dropped_once_its_deal_is_no_longer_tracked() = runTest {
        // Bounds the ledger the same way the assetId cache and the latches beside it are bounded: a terminated
        // deal must not leave a row behind for the life of the install.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        val mp = proofEnforcedMarketplace().apply { tradeStatusAccepted = false }
        val l = proofEnforcedLoop(mp, progress, clock = clock)

        l.runOnce()
        assertEquals(setOf(offerIntent), progress.loadAcceptedProofs().keys)

        mp.heartbeatResponse = HeartbeatResponse(
            activeTracking = listOf(tracked("d2", "offer-1", proofRequired = true)),
            ttlSeconds = 60,
        )
        // Past the heartbeat ttl so the new tracking list is actually fetched — a watch-only wake reuses the
        // cached one — but well inside the reuse window, so d2's own row is written by a real proof.
        clock.advance(2.minutes)
        l.runOnce()

        assertEquals(
            setOf(ProofIntent(DealId("d2"), TradeStatusSource.OFFER, 3)),
            progress.loadAcceptedProofs().keys,
            "only the deals still tracked keep a row",
        )
    }

    @Test
    fun rejected_proof_is_not_retried_even_when_the_report_is_refused() = runTest {
        // The regime the sibling test above could not reach, and the one a proof-enforcing backend actually
        // produces. Terminality used to be enforced ONLY through the dedup baseline, which is persisted for
        // ACCEPTED reports — but a backend that gates acceptance on the proof refuses the report as well
        // (`P2P_PROOF_REQUIRED`), so nothing was baselined, the plan re-minted both every tick, and the
        // identical proof went out once a minute until the deal's deadline killed it. The sibling test passed
        // throughout because `FakeMarketplaceClient.tradeStatusAccepted` defaults to true.
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val notary = FakeNotaryProver()
        val progress = InMemoryTrackerProgressStore()
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        ).apply {
            proofVerified = false
            tradeStatusAccepted = false
        }
        val l = loop(marketplace = mp, reader = reader, notary = notary, progress = progress, eventObserver = events)
        repeat(5) { l.runOnce() }

        assertEquals(
            1,
            mp.proofsSubmitted.size,
            "a refused proof must not be re-submitted just because the report it corroborates was also refused",
        )
        // …and the report is WITHHELD, not retried. Proofs run first now, and a backend enforcing
        // `proof_required` refuses this code until its proof verifies — so every send would be a guaranteed
        // round trip to a rejection. Re-based from the old contract, which asserted 5 sends here.
        assertEquals(0, mp.tradeStatusReports.size, "a report whose proof has not verified must not be sent")
        // Withheld is not the same as silent: every one of the five cycles has to say it decided not to send.
        val deferred = events.events.filterIsInstance<LifecycleEvent.TradeStatusReportDeferred>()
        assertEquals(5, deferred.size, "each withheld report must be narrated, not silent")
        assertEquals("d1", deferred[0].dealId)
        assertEquals("offer", deferred[0].source)
        assertEquals(3, deferred[0].steamStatusCode)
        // …and every one of those four idle cycles must SAY it is idle by choice. Without this the log carries
        // no proof event at all after the first cycle, which is indistinguishable from a prover that is never
        // invoked — the reading that cost a live debugging session.
        val suppressed = events.events.filterIsInstance<LifecycleEvent.ProofSuppressed>()
        assertEquals(4, suppressed.size, "each suppressed cycle must be narrated, not silent")
        assertEquals("d1", suppressed[0].dealId)
        assertEquals("offer", suppressed[0].source)
    }

    @Test
    fun a_verified_proof_lets_its_report_through_and_the_proof_goes_first() = runTest {
        // The headline of the reorder. A backend enforcing `proof_required` refuses the report until the
        // proof for that exact transition has verified, so the report now FOLLOWS its proof. Ordering is the
        // assertion, not an incidental detail: reporting first cost one guaranteed-refused POST per cycle.
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        ).apply { proofVerified = true }
        loop(marketplace = mp, reader = reader, notary = FakeNotaryProver(), eventObserver = events).runOnce()

        assertEquals(1, mp.proofsSubmitted.size)
        assertEquals(1, mp.tradeStatusReports.size, "a verified proof must not withhold its report")
        assertTrue(
            events.events.none { it is LifecycleEvent.TradeStatusReportDeferred },
            "nothing was withheld, so nothing should say it was",
        )
        val proofAt = events.events.indexOfFirst { it is LifecycleEvent.ProofSubmitted }
        val reportAt = events.events.indexOfFirst { it is LifecycleEvent.TradeStatusReported }
        assertTrue(proofAt in 0 until reportAt, "the proof must precede its report (proof=$proofAt report=$reportAt)")
    }

    @Test
    fun a_report_with_no_proof_due_is_never_gated() = runTest {
        // The carve-out that stops a broken prover silencing everything. `proofRequired = false` mints no
        // intent, so there is no proof to wait for and the report goes out unconditionally — the same for a
        // non-decisive code. Without this the gate would mean a dead notary taught the backend nothing at all.
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = false)),
                ttlSeconds = 60,
            ),
        )
        loop(marketplace = mp, reader = reader, notary = FakeNotaryProver(), eventObserver = events).runOnce()

        assertEquals(0, mp.proofsSubmitted.size, "no proof is due for a deal the backend does not gate")
        assertEquals(1, mp.tradeStatusReports.size, "and its report must still go out")
        assertTrue(events.events.none { it is LifecycleEvent.TradeStatusReportDeferred })
    }

    @Test
    fun a_rejected_proof_carries_the_backends_reason_and_the_prover_that_produced_it() = runTest {
        // Both fields were reachable and both were dropped: `ProofResult.reason` was projected away by the
        // loop, and nothing named the prover at all — so "the backend rejected a real proof" and "we sent an
        // empty stub because no notary is configured" were the same log line, needing opposite responses.
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        ).apply {
            proofVerified = false
            proofReason = "empty proof_payload"
        }
        loop(marketplace = mp, reader = reader, notary = NoOpNotaryProver, eventObserver = events).runOnce()

        val submitted = events.events.filterIsInstance<LifecycleEvent.ProofSubmitted>()
        assertEquals(1, submitted.size)
        assertEquals(false, submitted[0].verified)
        assertEquals("empty proof_payload", submitted[0].reason, "the backend's own diagnosis must survive")
        assertEquals("noop", submitted[0].prover, "a stub submission must not read like a real proof")
    }

    @Test
    fun a_proof_that_cannot_be_generated_is_narrated() = runTest {
        // The failure this exists for: the prover throws (refused notary handshake, dead offscreen document,
        // MPC abort) and the loop's runCatching swallows the Throwable whole. The only other trace is a
        // withheld dedup baseline, which re-reports the same code every tick — indistinguishable from broken
        // dedup unless the proof failure says so itself.
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val notary = FakeNotaryProver().apply { proveThrows = IllegalStateException("notary handshake failed") }
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        )
        val outcome = loop(marketplace = mp, reader = reader, notary = notary, eventObserver = events).runOnce()

        assertTrue(mp.proofsSubmitted.isEmpty(), "generation failed — nothing should have been delivered")
        assertEquals(0, outcome.proofsSubmitted)
        val failed = events.events.filterIsInstance<LifecycleEvent.ProofFailed>()
        assertEquals(1, failed.size, "a proof that could not be generated must be narrated, not swallowed")
        assertEquals("d1", failed[0].dealId)
        assertEquals("offer", failed[0].source)
        assertTrue(
            failed[0].reason?.contains("notary handshake failed") == true,
            "the reason must survive redaction, else the event says only 'something failed': ${failed[0].reason}",
        )
    }

    // ---- DMA-280: answering a backend freshness mark -------------------------------------------

    /** The instant every mark below is stamped at: an hour past the clock these tests start from. */
    private fun FakeClock.mark(offset: kotlin.time.Duration = 1.hours): Instant = now() + offset

    private fun marked(
        dealId: String = "deal-1",
        tradeId: String? = "744935517744884653",
        proveAfter: Instant?,
        proofRequired: Boolean = true,
        role: DealRole = DealRole.UNKNOWN,
        watch: Set<WatchTarget> = setOf(WatchTarget.GET_TRADE_STATUS),
    ) = TrackedDeal(
        dealId = DealId(dealId),
        steamOfferId = OfferId("offer-1"),
        watch = watch,
        proofRequired = proofRequired,
        role = role,
        steamTradeId = tradeId?.let(::TradeId),
        proveAfter = proveAfter,
    )

    private fun markedHeartbeat(vararg deals: TrackedDeal) =
        FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(activeTracking = deals.toList(), ttlSeconds = 60))

    @Test
    fun a_mark_is_answered_with_one_proof_bound_to_the_trade_the_backend_named() = runTest {
        // The headline. Nothing changed on either Steam axis — the offer sits at 3 and its code is already
        // baselined — so the change detector has nothing to say, and this proof exists only because the
        // backend asked for one.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val mp = markedHeartbeat(marked(proveAfter = clock.mark()))
        val events = RecordingEventObserver()

        val outcome = loop(
            marketplace = mp,
            reader = reader,
            notary = notary,
            progress = progress,
            eventObserver = events,
            clock = clock,
        ).runOnce()

        assertEquals(listOf(DealId("deal-1") to TradeStatusSource.HISTORY), notary.proven)
        assertEquals(listOf<TradeId?>(TradeId("744935517744884653")), notary.provenTradeIds)
        assertEquals(1, mp.proofsSubmitted.size)
        assertEquals(1, outcome.proofsSubmitted)
        val demanded = events.events.filterIsInstance<LifecycleEvent.FreshProofDemanded>()
        assertEquals(1, demanded.size, "the mark must be on record before the gate")
        assertEquals("744935517744884653", demanded[0].tradeId)
        assertEquals(clock.mark().toString(), demanded[0].proveAfter)
        assertTrue(
            events.events.filterIsInstance<LifecycleEvent.ProofSubmitted>().single().demanded,
            "the frame must say it answered a mark, or a lone ProofSubmitted with no report reads as noise",
        )
    }

    @Test
    fun answering_a_mark_costs_no_steam_history_read() = runTest {
        // The timing trap this design exists to avoid. `historyDue` goes SPARSE once every watched deal is
        // baselined and attributed — `revertWatchIntervalMs` is an hour — so a design that observed the axis
        // before proving it would miss the backend's ~2-minute release grace by 30x. The proven read needs
        // only the trade id the backend supplied, so no poll is required at all.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(
            mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3, historyInitiatorReported = true)),
        )
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val loopState = InMemoryLoopStateStore().apply { setRevertWatchAt(clock.now()) }
        val notary = FakeNotaryProver()

        loop(
            marketplace = markedHeartbeat(marked(proveAfter = clock.mark())),
            reader = reader,
            notary = notary,
            progress = progress,
            loopState = loopState,
            clock = clock,
        ).runOnce()

        assertEquals(1, notary.proven.size, "the mark was answered")
        assertEquals(0, reader.recentTransfersCalls, "a demand must not wait on the hourly history poll")
    }

    @Test
    fun a_satisfied_mark_is_persisted_and_not_answered_twice() = runTest {
        // The exact-proof-count contract the harness asserts: one demand, one proof, then silence. The mark is
        // re-presented on every heartbeat and on every watch-only wake, so anything weaker than a persisted
        // latch is one full MPC session per wake for the life of the deal.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val l =
            loop(
                marketplace = markedHeartbeat(marked(proveAfter = clock.mark())),
                reader = reader,
                notary = notary,
                progress = progress,
                clock = clock,
            )

        l.runOnce()
        l.runOnce()
        l.runOnce()

        assertEquals(1, notary.proven.size, "the mark must be answered exactly once")
        assertEquals(clock.mark(), progress.loadFreshProofProgress().getValue(DealId("deal-1")).satisfied)
    }

    @Test
    fun a_greater_mark_is_answered_again() = runTest {
        // The backend's manual re-check — the only greater mark there is; maturity stamps once and then
        // republishes the same instant.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        progress.recordFreshProofProgress(DealId("deal-1"), FreshProofProgress(satisfied = clock.mark()))
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))

        loop(
            marketplace = markedHeartbeat(marked(proveAfter = clock.mark(2.hours))),
            reader = reader,
            notary = notary,
            progress = progress,
            clock = clock,
        ).runOnce()

        assertEquals(1, notary.proven.size)
        assertEquals(clock.mark(2.hours), progress.loadFreshProofProgress().getValue(DealId("deal-1")).satisfied)
    }

    @Test
    fun a_refused_demand_is_not_latched_off_and_leaves_the_mark_unsatisfied() = runTest {
        // The ticket's own requirement, and the thing the transition path CANNOT do: any `verified = false`
        // there latches the intent off for the life of the worker. A demand instead advances a ladder, and the
        // mark stays unsatisfied so it is re-answered rather than abandoned.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val mp = markedHeartbeat(marked(proveAfter = clock.mark())).apply { proofVerified = false }
        val events = RecordingEventObserver()

        val outcome = loop(
            marketplace = mp,
            reader = reader,
            notary = notary,
            progress = progress,
            eventObserver = events,
            clock = clock,
        ).runOnce()

        assertEquals(0, outcome.proofsSubmitted, "a refused proof moves no counter")
        val standing = progress.loadFreshProofProgress().getValue(DealId("deal-1"))
        assertNull(standing.satisfied, "a refusal must NOT mark the demand satisfied")
        assertEquals(1, standing.attempts, "it must arm the ladder instead")
        assertTrue(standing.retryAt != null && standing.retryAt!! > clock.now())
        assertTrue(
            events.events.filterIsInstance<LifecycleEvent.ProofSubmitted>().single().let { !it.verified && it.demanded },
        )
    }

    @Test
    fun a_demand_inside_its_retry_window_is_skipped_with_a_deadline_and_no_mpc_session() = runTest {
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        progress.recordFreshProofProgress(
            DealId("deal-1"),
            FreshProofProgress(attempting = clock.mark(), attempts = 1, retryAt = clock.now() + 5.minutes),
        )
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val events = RecordingEventObserver()

        loop(
            marketplace = markedHeartbeat(marked(proveAfter = clock.mark())),
            reader = reader,
            notary = notary,
            progress = progress,
            eventObserver = events,
            clock = clock,
        ).runOnce()

        assertTrue(notary.proven.isEmpty(), "the ladder must cost no MPC session")
        val skip = events.events.filterIsInstance<LifecycleEvent.ProofSuppressed>().single()
        assertEquals(ProofSkipReason.FRESHNESS_RETRY_PENDING.message, skip.reason)
        assertEquals(300, skip.retryAfterSeconds)
    }

    @Test
    fun a_mark_naming_no_trade_is_said_out_loud_rather_than_dropped() = runTest {
        // Unanswerable by anyone — the proven read addresses one trade by id — and invisible everywhere else,
        // since the deal reports nothing and the backend's view is "asked, unanswered".
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val events = RecordingEventObserver()

        loop(
            marketplace = markedHeartbeat(marked(tradeId = null, proveAfter = clock.mark())),
            reader = reader,
            notary = notary,
            progress = progress,
            eventObserver = events,
            clock = clock,
        ).runOnce()

        assertTrue(notary.proven.isEmpty())
        assertEquals(
            ProofFreshness.UNBINDABLE,
            events.events.filterIsInstance<LifecycleEvent.ProofSuppressed>().single().reason,
        )
        assertTrue(
            events.events.none { it is LifecycleEvent.FreshProofDemanded },
            "an unservable mark is not a demand this pass intends to answer",
        )
    }

    @Test
    fun a_demand_posts_nothing_when_no_real_prover_is_configured() = runTest {
        // What every host with no proving context gets. `NoOpNotaryProver` answers with an EMPTY
        // payload, which IS delivered and refused — so without this gate every marked deal would POST
        // `/notary` for nothing and arm a ladder over a proof that could never have worked.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val mp = markedHeartbeat(marked(proveAfter = clock.mark()))
        val events = RecordingEventObserver()

        loop(
            marketplace = mp,
            reader = reader,
            notary = NoOpNotaryProver,
            progress = progress,
            eventObserver = events,
            clock = clock,
        ).runOnce()

        assertTrue(mp.proofsSubmitted.isEmpty(), "an empty payload must not be delivered")
        assertTrue(progress.loadFreshProofProgress().isEmpty(), "and no ladder may be armed over it")
        assertEquals(1, events.events.filterIsInstance<LifecycleEvent.ProofSuppressed>().size)
    }

    @Test
    fun the_watch_summary_counts_a_demanded_cycle_so_it_does_not_read_as_nothing_changed() = runTest {
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))
        val events = RecordingEventObserver()

        loop(
            marketplace = markedHeartbeat(marked(proveAfter = clock.mark())),
            reader = reader,
            progress = progress,
            eventObserver = events,
            clock = clock,
        ).runOnce()

        val summary = events.events.filterIsInstance<LifecycleEvent.WatchSummary>().single()
        assertEquals(1, summary.demanded)
        assertEquals(0, summary.planned, "nothing changed — which is exactly why the counter has to be there")
    }

    @Test
    fun a_mark_is_answered_on_a_buyer_deal_and_without_proof_required() = runTest {
        // "React regardless of role" is the ticket's own settled answer, and the watch entry is served to both
        // parties — so gating on the side would silently halve the coverage the dual index buys. `role` gates
        // only the two Steam WRITE directives. `proofRequired` is likewise not a conjunct: the mark IS the
        // request, and the same backend sets both, so a flag lagging it by a deploy would park the payout.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        progress.recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 3)))
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(initialOffers = mapOf(OfferId("offer-1") to 3))

        loop(
            marketplace = markedHeartbeat(marked(role = DealRole.BUYER, proofRequired = false, proveAfter = clock.mark())),
            reader = reader,
            notary = notary,
            progress = progress,
            clock = clock,
        ).runOnce()

        assertEquals(1, notary.proven.size)
    }

    @Test
    fun a_demand_supersedes_a_live_transition_intent_on_the_same_axis() = runTest {
        // Both would prove the IDENTICAL read (`GetTradeStatus?tradeid=…`) and `/notary` carries only
        // `{dealId, proofPayload}`, so the backend cannot tell them apart: two ~30 MB sessions for one fact,
        // and one proof too many against a smoke that asserts an exact count. Reachable exactly when a mark is
        // stamped, since that is while a history transition is still live through the hold.
        val clock = FakeClock()
        val progress = InMemoryTrackerProgressStore()
        val notary = FakeNotaryProver()
        val reader = FakeSteamReadClient(
            initialOffers = mapOf(OfferId("offer-1") to 3),
            initialTransfers = listOf(
                SteamTransfer(
                    partnerSteamId = null,
                    assetIds = setOf(AssetId("asset-1")),
                    status = 3,
                    tradeId = TradeId("744935517744884653"),
                ),
            ),
            // The exact correlation path, so the history axis really does raise its own intent — the whole
            // point of the test is that TWO would otherwise prove the same read.
        ).apply { offerTradeIds = mapOf(OfferId("offer-1") to TradeId("744935517744884653")) }
        val events = RecordingEventObserver()

        loop(
            marketplace = markedHeartbeat(
                marked(proveAfter = clock.mark(), watch = setOf(WatchTarget.GET_TRADE_OFFER, WatchTarget.GET_TRADE_STATUS)),
            ),
            reader = reader,
            notary = notary,
            progress = progress,
            eventObserver = events,
            clock = clock,
        ).runOnce()

        assertEquals(
            1,
            notary.proven.count { it.second == TradeStatusSource.HISTORY },
            "the history axis must be proved once, by the demand: ${notary.proven}",
        )
        assertTrue(
            events.events.filterIsInstance<LifecycleEvent.ProofSuppressed>().any { it.reason.contains("freshness demand") },
            "and the superseded intent must say why it stood down",
        )
    }

    @Test
    fun a_cancelled_proof_is_teardown_and_says_nothing() = runTest {
        // The one branch of the mint path that had NO test, found by probing whether the extracted
        // `mintAndSubmit` was still guarded: a cancelled cycle is teardown (`stopTracker`, a debug endpoint
        // switch, the host's proof deadline), not a notary problem. Narrating it would put a ProofFailed in
        // the session log for every ordinary restart, and — because the failure fold shares the same carve-out
        // — would also let a teardown climb the breaker ladder and park a healthy prover.
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val notary = FakeNotaryProver().apply { proveThrows = CancellationException("cycle cancelled") }
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        )
        loop(marketplace = mp, reader = reader, notary = notary, eventObserver = events).runOnce()

        assertTrue(
            events.events.none { it is LifecycleEvent.ProofFailed },
            "a cancelled cycle is teardown, not a proof failure: ${events.events.filterIsInstance<LifecycleEvent.ProofFailed>()}",
        )
        assertTrue(events.events.none { it is LifecycleEvent.ProofSubmitted }, "nothing was delivered")
    }

    @Test
    fun an_undelivered_proof_is_narrated() = runTest {
        // The other half: generation succeeded, `/notary` did not.
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val events = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
                ttlSeconds = 60,
            ),
        ).apply { submitProofThrows = true }
        loop(marketplace = mp, reader = reader, eventObserver = events).runOnce()

        assertEquals(1, events.events.filterIsInstance<LifecycleEvent.ProofFailed>().size)
        assertTrue(
            events.events.none { it is LifecycleEvent.ProofSubmitted },
            "a proof the backend never acknowledged must not be reported as submitted",
        )
    }

    @Test
    fun a_delivered_proof_reports_the_backend_verdict_either_way() = runTest {
        // verified=false is the least visible outcome of the three: it moves no counter and takes no retry
        // path, so the event carrying the verdict is the only thing separating it from success.
        val offerId = OfferId("offer-1")
        val heartbeat = HeartbeatResponse(
            activeTracking = listOf(tracked("d1", offerId.value, proofRequired = true)),
            ttlSeconds = 60,
        )
        for (verdict in listOf(true, false)) {
            val events = RecordingEventObserver()
            val mp = FakeMarketplaceClient(heartbeatResponse = heartbeat).apply { proofVerified = verdict }
            loop(
                marketplace = mp,
                reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3)),
                eventObserver = events,
            ).runOnce()

            val submitted = events.events.filterIsInstance<LifecycleEvent.ProofSubmitted>()
            assertEquals(listOf(verdict), submitted.map { it.verified }, "verdict $verdict must be reported as itself")
            assertEquals("d1", submitted[0].dealId)
            assertTrue(events.events.none { it is LifecycleEvent.ProofFailed }, "the proof was delivered")
        }
    }

    @Test
    fun decisive_code_without_proof_required_does_not_submit_proof() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 3))
        val notary = FakeNotaryProver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value, proofRequired = false)),
                ttlSeconds = 60,
            ),
        )
        loop(marketplace = mp, reader = reader, notary = notary).runOnce()
        assertTrue(mp.proofsSubmitted.isEmpty(), "no proof when proof_required=false even for decisive code")
        assertEquals(1, mp.tradeStatusReports.size, "raw code still reported")
    }

    // ---- expedited cadence while a deal awaits the seller's mobile confirmation (state 9) --------
    // (ttl 3600 puts the heartbeat far out so the deal-watch poll class is the binding constraint.)

    @Test
    fun create_offer_needing_confirmation_expedites_the_next_wake() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 3600),
        )
        // Default FakeSteamOfferCreator returns NeedsConfirmation (state 9).
        val l = loop(marketplace = mp, directivesEnabled = true)
        l.runOnce()
        // Web floor 60s (the 15s expedited target clamps up), vs the 3-min active-offer baseline.
        assertEquals(60.seconds, l.nextWakeDelay())
    }

    @Test
    fun wake_is_the_baseline_active_offer_cadence_without_a_transient_deal() = runTest {
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 3600))
        val l = loop(marketplace = mp)
        l.runOnce()
        assertEquals(3.minutes, l.nextWakeDelay())
    }

    @Test
    fun observed_state_9_keeps_the_wake_expedited() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 9))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value)),
                ttlSeconds = 3600,
            ),
        )
        val l = loop(marketplace = mp, reader = reader)
        l.runOnce()
        assertEquals(60.seconds, l.nextWakeDelay())
    }

    @Test
    fun expedited_window_lapses_back_to_baseline_when_no_transient_deal_recurs() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 9))
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value)),
                ttlSeconds = 3600,
            ),
        )
        val l = loop(marketplace = mp, reader = reader, clock = clock)
        l.runOnce()
        assertEquals(60.seconds, l.nextWakeDelay()) // within the 5-min window
        clock.advance(6.minutes) // past the window, with no further state-9 observation
        assertEquals(3.minutes, l.nextWakeDelay())
    }

    @Test
    fun expedited_window_slides_while_state_9_persists() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 9))
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value)),
                ttlSeconds = 3600,
            ),
        )
        val l = loop(marketplace = mp, reader = reader, clock = clock)
        l.runOnce() // window → t0 + 5m
        clock.advance(4.minutes) // still inside
        l.runOnce() // state 9 still observed (deduped to no report) → window re-armed to t0 + 9m
        clock.advance(2.minutes) // t0 + 6m: past the original window, inside the re-armed one
        assertEquals(60.seconds, l.nextWakeDelay())
    }

    @Test
    fun create_trade_arms_expedited_and_rearms_the_alarm_immediately() = runTest {
        val scheduler = FakeScheduler()
        val l = loop(scheduler = scheduler) // default creator → NeedsConfirmation
        val result = l.createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        assertTrue(result is CreateOfferResult.NeedsConfirmation)
        // The out-of-band FE trigger re-arms the wake now (no heartbeat yet → poll cadence is direct):
        // the expedited 60s, so we don't idle on a stale ~3-min alarm before the first re-check.
        assertEquals(60.seconds, scheduler.scheduledDelays.last())
    }

    @Test
    fun create_trade_reports_deal_id_on_trade_actions() = runTest {
        val mp = FakeMarketplaceClient()
        val l = loop(marketplace = mp)
        l.createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        // The /trade-actions outcome must carry the DMarket deal key — the backend rejects the
        // report ("deal_id is required") and holds the directive lease without it.
        assertEquals(1, mp.directiveOutcomes.size)
        assertEquals(DealId("deal-ft"), mp.directiveOutcomes[0].dealId)
        assertEquals(DirectiveId("dir-ft"), mp.directiveOutcomes[0].directiveId)
    }

    @Test
    fun rejected_trade_actions_report_emits_directive_report_failed() = runTest {
        val mp = FakeMarketplaceClient().apply {
            directiveAccepted = false
            directiveRejectReason = "deal_id is required"
        }
        val observer = RecordingEventObserver()
        loop(marketplace = mp, eventObserver = observer).createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        val failed = observer.events.filterIsInstance<LifecycleEvent.DirectiveReportFailed>().single()
        assertEquals("create_offer", failed.kind)
        assertEquals("dir-ft", failed.directiveId)
        assertEquals("deal_id is required", failed.reason)
    }

    @Test
    fun thrown_trade_actions_report_emits_directive_report_failed() = runTest {
        val mp = FakeMarketplaceClient().apply { reportDirectiveThrows = true }
        val observer = RecordingEventObserver()
        loop(marketplace = mp, eventObserver = observer).createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        val failed = observer.events.filterIsInstance<LifecycleEvent.DirectiveReportFailed>().single()
        assertEquals("create_offer", failed.kind)
        // A `redactedSummary()`: exception class + scrubbed, capped message (this reason reaches the host).
        assertEquals("IllegalStateException: simulated /trade-actions failure", failed.reason)
    }

    // ---- respawn survival (MV3 worker teardown): schedule state persisted via LoopStateStore ------
    // A "respawn" is a second loop instance sharing the first one's LoopStateStore + clock.

    @Test
    fun expedited_window_survives_a_worker_respawn() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        // Worker 1: FE create → NeedsConfirmation arms + persists the expedited window, then dies.
        loop(clock = clock, loopState = store).createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        // Worker 2: fresh instance; the backend does NOT yet track the deal (empty active_tracking),
        // so state 9 cannot be observed — the restored window alone must keep the wake expedited.
        val respawned = loop(
            marketplace = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 3600)),
            clock = clock,
            loopState = store,
        )
        respawned.runOnce()
        assertEquals(60.seconds, respawned.nextWakeDelay(), "restored window must expedite the respawned loop")
    }

    @Test
    fun expired_expedited_window_is_not_restored_after_respawn() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        loop(clock = clock, loopState = store).createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        clock.advance(6.minutes) // past the 5-min window before the respawn
        val respawned = loop(
            marketplace = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 3600)),
            clock = clock,
            loopState = store,
        )
        respawned.runOnce()
        assertEquals(3.minutes, respawned.nextWakeDelay(), "an expired persisted window must not fast-poll")
    }

    @Test
    fun restored_ttl_schedule_bounds_the_wake_of_an_idle_respawn() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        // Worker 1 heartbeats with ttl=90 → aimed one 60s poll floor inside it, i.e. clamped back up to the
        // 60s web heartbeat floor.
        loop(
            marketplace = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 90)),
            clock = clock,
            loopState = store,
        ).runOnce()
        // Worker 2 idles (not due, nothing to watch) — but the persisted, already-clamped ttl schedule
        // must still bound its next wake instead of drifting to the 3-min poll cadence.
        val respawned = loop(marketplace = FakeMarketplaceClient(), clock = clock, loopState = store)
        assertEquals(TickOutcome.EMPTY, respawned.runOnce())
        assertEquals(60.seconds, respawned.nextWakeDelay(), "backend ttl must survive the respawn")
    }

    @Test
    fun persistent_5xx_outage_surfaces_across_a_worker_respawn() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        val events = RecordingEventObserver()
        // Worker 1: first 503 of the outage — below the threshold, so no error state yet — then dies.
        val w1 = loop(
            marketplace = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(503) },
            clock = clock,
            loopState = store,
            eventObserver = events,
        )
        w1.runOnce()
        assertFalse(w1.marketplaceServerError, "one transient 5xx stays an idle tick")
        // Worker 2 (respawn): a fresh instance sharing the store. Its 503 is the outage's SECOND
        // consecutive failure — the restored streak must cross the threshold instead of restarting at 0
        // (which would keep a persistent outage invisible forever, one blip per respawn).
        val w2 = loop(
            marketplace = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(503) },
            clock = clock,
            loopState = store,
            eventObserver = events,
        )
        w2.runOnce()
        assertTrue(w2.marketplaceServerError, "the persisted streak must cross the threshold on the respawned worker")
        assertEquals(TrackerBlock.DM_CONNECTION_ERROR, w2.blockingState)
        assertEquals(
            1,
            events.events.count { it is LifecycleEvent.MarketplaceServerError },
            "exactly one entry event across both workers",
        )
    }

    @Test
    fun successful_heartbeat_clears_the_persisted_5xx_streak() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        // Worker 1: one 503 (streak persisted), then a successful heartbeat — the reset must be
        // persisted too, or the stale streak would resurface on the next respawn.
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(503) }
        val w1 = loop(marketplace = mp, clock = clock, loopState = store)
        w1.runOnce()
        mp.heartbeatThrowable = null
        w1.forceHeartbeatNow()
        w1.runOnce()
        // Worker 2 (respawn), past the schedule the success persisted so its heartbeat is due:
        // a single fresh 503 is still just a blip — it must NOT surface.
        clock.advance(2.minutes)
        val events = RecordingEventObserver()
        val w2 = loop(
            marketplace = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceServerErrorException(503) },
            clock = clock,
            loopState = store,
            eventObserver = events,
        )
        w2.runOnce()
        // Positive proof the 503 actually round-tripped (an idle skip would also satisfy the negative
        // assertions below): the fresh blip must have re-seeded the persisted streak to exactly 1.
        assertEquals(1, store.serverErrorCount(), "worker 2's heartbeat must really fire and fail once")
        assertFalse(w2.marketplaceServerError, "a cleared streak must not resurrect an old blip")
        assertEquals(TrackerBlock.NONE, w2.blockingState)
        assertTrue(events.events.filterIsInstance<LifecycleEvent.MarketplaceServerError>().isEmpty())
    }

    // ---- heartbeat cadence: backend ttl_seconds honoured, platform-clamped ------------------------

    @Test
    fun short_ttl_moves_the_wake_earlier_than_the_poll_cadence() = runTest {
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 90))
        val l = loop(marketplace = mp)
        l.runOnce()
        // 60s: the 90s cadence less its 60s wake-grid margin, clamped up to the 60s heartbeat floor. Still
        // well inside the 3-min poll target, which is what this test is about.
        assertEquals(60.seconds, l.nextWakeDelay(), "a ttl shorter than the poll target must pull the wake in")
    }

    @Test
    fun heartbeat_stays_on_ttl_cadence_between_watch_wakes() = runTest {
        val offerId = OfferId("offer-1")
        val reader = FakeSteamReadClient(initialOffers = mapOf(offerId to 2))
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("d1", offerId.value)),
                ttlSeconds = 300,
            ),
        )
        val l = loop(marketplace = mp, reader = reader, clock = clock)
        l.runOnce() // heartbeat #1 (due: nothing scheduled yet)
        assertEquals(1, mp.heartbeatsSent.size)

        clock.advance(60.seconds) // an expedited-style wake well before the 300s ttl
        reader.offers = mapOf(offerId to 9)
        l.runOnce() // between heartbeats: watch-only on the cached tracking list
        assertEquals(1, mp.heartbeatsSent.size, "a wake before the ttl must not heartbeat")
        assertEquals(9, mp.tradeStatusReports.last().steamStatusCode, "the watch still runs on the cached list")

        clock.advance(300.seconds) // past the ttl
        l.runOnce()
        assertEquals(2, mp.heartbeatsSent.size, "the heartbeat resumes once its ttl is due")
    }

    @Test
    fun respawned_worker_honours_the_persisted_cadence_and_idles_until_due() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300))
        // Worker 1: first start — no persisted due-time, so the boot heartbeat is inherently due
        // (the loop cannot bootstrap otherwise); persists due = t0+300s.
        loop(marketplace = mp, clock = clock, loopState = store).runOnce()
        assertEquals(1, mp.heartbeatsSent.size, "the first start must bootstrap with a heartbeat")
        // Worker 2 (respawn): the restored backend cadence is honoured — not due + nothing to watch
        // → idle. A worker respawn alone must not self-initiate a heartbeat.
        val respawned = loop(marketplace = mp, clock = clock, loopState = store)
        assertEquals(TickOutcome.EMPTY, respawned.runOnce())
        assertEquals(1, mp.heartbeatsSent.size, "a respawn before the due-time must not heartbeat")
        // The due tick still heartbeats.
        clock.advance(300.seconds)
        respawned.runOnce()
        assertEquals(2, mp.heartbeatsSent.size, "the heartbeat resumes once its ttl is due")
    }

    @Test
    fun force_heartbeat_bypasses_the_idle_respawn() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300))
        loop(marketplace = mp, clock = clock, loopState = store).runOnce()
        // A respawned worker asked for an explicit heartbeat (debug force-tick / host nudge) must still
        // send one: forceHeartbeatNow() marks the heartbeat due, which outranks the idle decision.
        val respawned = loop(marketplace = mp, clock = clock, loopState = store)
        respawned.forceHeartbeatNow()
        respawned.runOnce()
        assertEquals(2, mp.heartbeatsSent.size, "an explicit force must bypass the idle respawn")
    }

    @Test
    fun idle_respawn_does_no_steam_credential_work() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        loop(
            marketplace = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300)),
            clock = clock,
            loopState = store,
        ).runOnce()
        // The idle decision is taken BEFORE any credential work: a respawn inside a live ttl window
        // does no Steam session scrape and emits no ReLoginNeeded noise, even with Steam logged out.
        val scraper = FakeSteamSessionScraper(result = null)
        val events = RecordingEventObserver()
        val respawned = loop(clock = clock, loopState = store, credential = false, scraper = scraper, eventObserver = events)
        assertEquals(TickOutcome.EMPTY, respawned.runOnce())
        assertEquals(0, scraper.scrapeCalls, "an idle cycle must not touch the Steam session")
        assertTrue(events.events.none { it is LifecycleEvent.ReLoginNeeded })
    }

    @Test
    fun push_nudge_on_a_fresh_worker_heartbeats_instead_of_idling() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300))
        loop(marketplace = mp, clock = clock, loopState = store).runOnce()
        // A push / FE request-cycle nudge usually SPAWNS the worker it lands on. With no cached
        // tracking list there is nothing cadence-respecting to do — the nudge must be honoured with
        // a heartbeat, not a silent idle.
        val respawned = loop(marketplace = mp, clock = clock, loopState = store)
        respawned.wakeFromPush(PushSignal.WakeAll)
        assertEquals(2, mp.heartbeatsSent.size, "a push nudge on a fresh worker must heartbeat")
    }

    @Test
    fun idle_wake_is_bound_to_the_due_tick_not_the_expedited_cadence() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        // Worker 1: an offer create arms + persists the 5-min expedited window, then a heartbeat
        // schedules due = t0+10min. Both survive to the respawn.
        val w1 = loop(
            marketplace = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 600)),
            clock = clock,
            loopState = store,
        )
        w1.createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        w1.runOnce()
        // Worker 2 has nothing to watch: the restored expedited window must not burn 60s wakes on
        // cycles that would idle — the only useful wake is the due tick.
        val respawned = loop(clock = clock, loopState = store)
        assertEquals(TickOutcome.EMPTY, respawned.runOnce())
        // 9 min, not 10: the schedule aims one poll floor inside the advertised 600s cadence.
        assertEquals(9.minutes, respawned.nextWakeDelay(), "an idle-bound worker wakes at the due tick only")
    }

    @Test
    fun no_ttl_heartbeat_schedules_the_fallback_interval() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        // Backend sends no ttl_seconds → the client's own fallback interval (clamped like a ttl)
        // drives the schedule instead of the bare 60s web floor.
        val l = loop(
            marketplace = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 0)),
            clock = clock,
            loopState = store,
            tunables = TrackerConfig(cadence = CadenceConfig(fallbackHeartbeatIntervalMs = 300_000)),
        )
        l.runOnce()
        assertEquals(clock.now() + 5.minutes, store.nextHeartbeatAt())
    }

    // ---- linkedSteamId wrong-account guard --------------------------------------------------
    // fakeSteamCredential() holds SteamId 76561198000000001; MISMATCH_ID is a different account.

    private val matchingSteamId = SteamId("76561198000000001")
    private val mismatchSteamId = SteamId("76561198000000099")

    @Test
    fun mismatched_heartbeat_never_advances_the_schedule_so_the_mismatch_survives_a_respawn() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = mismatchSteamId),
        )
        val w1 = loop(marketplace = mp, clock = clock, loopState = store)
        w1.runOnce()
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, w1.blockingState)
        // A mismatched heartbeat is a blocked state: like the failure paths it must NOT advance the
        // persisted schedule, or a fresh worker inside an advanced ttl window would idle without ever
        // re-evaluating the binding (the persisted verdict keeps the prompt honest in the meantime, but
        // only a heartbeat can retire it).
        assertNull(store.nextHeartbeatAt(), "a blocked heartbeat must leave the schedule due")
        // The respawn is therefore due: it re-heartbeats and re-establishes the mismatch (no idle NONE).
        val respawned = loop(marketplace = mp, clock = clock, loopState = store)
        respawned.runOnce()
        assertEquals(2, mp.heartbeatsSent.size)
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, respawned.blockingState)
        // Resolution: the ids agree again → the next wake clears the block and resumes the ttl cadence.
        mp.heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId)
        respawned.runOnce()
        assertEquals(TrackerBlock.NONE, respawned.blockingState)
        assertEquals(clock.now() + 240.seconds, store.nextHeartbeatAt(), "the ttl cadence, less its wake-grid margin")
    }

    @Test
    fun mismatch_clears_when_the_user_signs_back_into_the_linked_steam_account() = runTest {
        // The real-world recovery direction, and the one every other test here misses: the BACKEND's
        // linkedSteamId never changes (the DMarket account is still bound to the same Steam account) — what
        // changes is the browser session, when the user signs out of the wrong account and into the linked
        // one. The token side must follow, or the block outlives the re-login for the cached credential's
        // whole ~24h life. The vault starts on the wrong account; the cookie session and the scrape move to
        // the linked one, exactly as a Steam re-login leaves them.
        val reader = FakeSteamReadClient()
        val creator = FakeSteamOfferCreator()
        val clock = FakeClock()
        val refresher = FakeSteamSessionRefresher(cookieSteamId = mismatchSteamId)
        val scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = mismatchSteamId.value))
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked()),
                directives = listOf(createDirective()),
                ttlSeconds = 300,
                linkedSteamId = matchingSteamId,
            ),
        )
        val l = loop(
            marketplace = mp,
            creator = creator,
            reader = reader,
            directivesEnabled = true,
            credential = true,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            clock = clock,
            scraper = scraper,
            sessionRefresher = refresher,
        )
        l.runOnce()
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, l.blockingState, "precondition: the wrong account is blocked")
        assertTrue(creator.created.isEmpty())

        // The user signs into the linked account: Steam re-Set-Cookies steamLoginSecure for the new account,
        // and the community page would now yield that account's token. The cached credential is still fresh
        // (24h JWT), so nothing but the session's own identity can tell the loop it is stale.
        refresher.cookieSteamId = matchingSteamId
        scraper.result = fakeSteamCredential(steamId = matchingSteamId.value)
        l.runOnce()

        assertEquals(TrackerBlock.NONE, l.blockingState, "signing into the linked account must clear the block")
        assertTrue(!l.linkedSteamIdMismatch)
        assertEquals(1, creator.created.size, "directives resume on the very cycle that clears the block")
        assertTrue(reader.offerStatusesCalls >= 1, "Steam reads resume too")
    }

    @Test
    fun a_full_steam_logout_then_login_as_the_linked_account_clears_the_block() = runTest {
        // The other shape of the same episode: the user signs OUT first (session GONE →
        // STEAM_SESSION_MISSING) and only then signs in as the linked account. The logout must not leave
        // the wrong account's token behind, or the block would just switch to STEAM_ACCOUNT_MISMATCH and
        // stick there.
        val clock = FakeClock()
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE, cookieSteamId = mismatchSteamId)
        val scraper = FakeSteamSessionScraper(result = null)
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId))
        val l = loop(
            marketplace = mp,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            clock = clock,
            scraper = scraper,
            sessionRefresher = refresher,
        )
        l.runOnce()
        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, l.blockingState, "signed out of Steam")

        // Signed back in — as the LINKED account this time.
        refresher.state = SteamWebSessionState.ALIVE
        refresher.cookieSteamId = matchingSteamId
        scraper.result = fakeSteamCredential(steamId = matchingSteamId.value)
        l.runOnce()

        assertEquals(TrackerBlock.NONE, l.blockingState, "the logged-out token must not resurface as a mismatch")
    }

    @Test
    fun a_minted_session_for_another_account_does_not_replay_the_previous_token() = runTest {
        // The mint path recovers a session that expired while nothing was running. Whoever the platform's
        // durable credential brings back is who the loop must act as — not whoever the vault remembers.
        val clock = FakeClock()
        val refresher = FakeSteamSessionRefresher(
            state = SteamWebSessionState.GONE,
            mintRestoresSession = true,
            cookieSteamId = matchingSteamId,
        )
        val scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = matchingSteamId.value))
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId))
        val l = loop(
            marketplace = mp,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            clock = clock,
            scraper = scraper,
            sessionRefresher = refresher,
        )

        l.runOnce()

        assertEquals(TrackerBlock.NONE, l.blockingState, "the minted session's own account is the one that counts")
        assertEquals(1, mp.heartbeatsSent.size)
    }

    @Test
    fun heartbeat_linked_steam_id_mismatch_blocks_all_writes_and_reads() = runTest {
        val creator = FakeSteamOfferCreator()
        val reader = FakeSteamReadClient()
        val observer = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked()),
                directives = listOf(createDirective()),
                ttlSeconds = 60,
                linkedSteamId = mismatchSteamId,
            ),
        )
        val l = loop(marketplace = mp, creator = creator, reader = reader, directivesEnabled = true, eventObserver = observer)
        val outcome = l.runOnce()

        assertTrue(creator.created.isEmpty(), "no Steam offer may be created on a wrong-account session")
        assertTrue(mp.directiveOutcomes.isEmpty(), "directive execution is skipped entirely on a mismatch")
        assertEquals(0, reader.offerStatusesCalls, "no Steam reads on a wrong-account session")
        assertEquals(0, reader.recentTransfersCalls, "no Steam reads on a wrong-account session")
        assertTrue(mp.tradeStatusReports.isEmpty(), "no /trade-events reports on a wrong-account session")
        assertEquals(0, outcome.directivesExecuted)
        assertTrue(l.linkedSteamIdMismatch, "the sticky flag is set")
        assertEquals(1, mp.heartbeatsSent.size, "the heartbeat itself still runs (detects the mismatch)")
        val event = observer.events.filterIsInstance<LifecycleEvent.LinkedSteamIdMismatch>().single()
        assertEquals("76561198000000099", event.linkedSteamId)
        assertEquals("76561198000000001", event.tokenSteamId)
    }

    @Test
    fun mismatch_blocks_reads_but_every_wake_reheartbeats_to_reevaluate() = runTest {
        val reader = FakeSteamReadClient()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked()),
                ttlSeconds = 300, // NOT honoured while mismatched: a blocked heartbeat never advances the schedule
                linkedSteamId = mismatchSteamId,
            ),
        )
        val l = loop(marketplace = mp, reader = reader, directivesEnabled = true)
        l.runOnce() // heartbeat detects the mismatch
        l.runOnce() // still due (blocked heartbeats leave the schedule due): re-heartbeats, reads stay blocked
        assertEquals(0, reader.offerStatusesCalls, "no Steam reads on a wrong-account session")
        assertEquals(2, mp.heartbeatsSent.size, "every wake re-heartbeats to re-evaluate the binding")
    }

    @Test
    fun a_stale_cached_token_is_re_acquired_when_the_backend_disagrees_with_it() = runTest {
        // THE reported bug. The browser is signed into the LINKED account and the heartbeat says so — but the
        // vault still holds the previous account's token, and a scraped Steam JWT stays fresh by its own clock
        // for ~24h regardless of who signed in since, so nothing about it looks stale. The only thing that
        // used to catch this was sessionState()'s zero-network cookie comparison, which is fail-open by
        // contract: here the refresher reports a plain ALIVE (an unreadable cookie store, an unparseable
        // value, or a stale duplicate cookie row all look like this), so that check is no help. And with the
        // block up, every Steam read is refused, which is what makes the reactive 401 re-scrape unreachable —
        // so before this fix the prompt was pinned for the rest of the token's life with no way out.
        val scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = matchingSteamId.value))
        val refresher = FakeSteamSessionRefresher() // ALIVE, no cookie identity: the fail-open case
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId))
        val l = loop(
            marketplace = mp,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            scraper = scraper,
            sessionRefresher = refresher,
        )

        l.runOnce() // the heartbeat disagrees with the held token → the token is the suspect half
        l.runOnce() // …so this wake re-acquires it before heartbeating again

        assertEquals(TrackerBlock.NONE, l.blockingState, "the re-acquired token matches, so nothing is blocking")
        assertTrue(!l.linkedSteamIdMismatch)
        assertTrue(scraper.scrapeCalls >= 1, "the credential really was re-acquired from Steam")
    }

    @Test
    fun a_truthful_mismatch_re_acquires_the_token_only_once_per_episode() = runTest {
        // The other half of the contract above: when the browser really IS on another account, re-scraping
        // Steam on every wake would be per-minute traffic that cannot change the answer (the shape of a bug
        // already fixed once on the mint path). Exactly one re-acquisition per episode.
        val scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = mismatchSteamId.value))
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId))
        val l = loop(
            marketplace = mp,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            scraper = scraper,
        )

        repeat(4) { l.runOnce() }

        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, l.blockingState, "still the wrong account")
        assertEquals(1, scraper.scrapeCalls, "one re-acquisition for the whole episode, not one per wake")
        assertEquals(4, mp.heartbeatsSent.size, "the heartbeat itself still runs every wake")
    }

    @Test
    fun a_forced_heartbeat_re_arms_the_wrong_account_re_acquisition() = runTest {
        // What makes a real re-login recover immediately instead of waiting out the latch above: the host
        // forces a heartbeat on every Steam session-cookie change, and the debug force-tick does the same.
        val scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = mismatchSteamId.value))
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId))
        val l = loop(
            marketplace = mp,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            scraper = scraper,
        )
        l.runOnce()
        l.runOnce() // spends the episode's one re-acquisition, still the wrong account
        assertEquals(1, scraper.scrapeCalls)

        // The user signs into the linked account: the cookie changes, the host forces a heartbeat.
        scraper.result = fakeSteamCredential(steamId = matchingSteamId.value)
        l.forceHeartbeatNow()
        l.runOnce()

        assertEquals(TrackerBlock.NONE, l.blockingState, "the forced cycle re-acquires and clears the block")
        assertEquals(2, scraper.scrapeCalls)
    }

    @Test
    fun a_respawned_worker_reports_the_wrong_account_block_before_its_first_heartbeat() = runTest {
        // Fail-closed across an MV3 respawn. The verdict is in-memory, and CycleStarted is emitted BEFORE
        // anything in the cycle could re-derive it — and a host mirrors blockingState synchronously from
        // inside that handler — so an unpersisted verdict published "nothing is blocking" over a correct
        // prompt on the first wake of every respawn, i.e. the popup claiming tracking is ON during a real
        // wrong-account episode.
        val store = InMemoryLoopStateStore()
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = mismatchSteamId))
        loop(marketplace = mp, clock = clock, loopState = store).runOnce()
        // The verdict names the TOKEN that was found bound elsewhere — here the default vault credential
        // (…001) — not the backend's linkedSteamId the heartbeat disagreed with.
        assertEquals(matchingSteamId.value, store.steamMismatchTokenId(), "precondition: the verdict is persisted")

        // A fresh instance (worker respawn), reading blockingState from INSIDE the first event it emits.
        val seen = mutableListOf<TrackerBlock>()
        var respawned: TradeTrackerLoop? = null
        val observer = object : EventObserver {
            override suspend fun onEvent(event: LifecycleEvent) {
                if (event is LifecycleEvent.CycleStarted) seen += respawned!!.blockingState
            }
        }
        respawned = loop(marketplace = mp, clock = clock, loopState = store, eventObserver = observer)
        respawned.runOnce()

        assertEquals(listOf(TrackerBlock.STEAM_ACCOUNT_MISMATCH), seen, "the restored verdict is visible from the first event")
    }

    @Test
    fun the_wrong_account_block_clears_as_soon_as_a_different_account_s_credential_is_held() = runTest {
        // The clear site that needs no heartbeat — which matters because the heartbeat is exactly what can
        // fail while the user waits for the prompt to disappear (offline, 5xx, or a prod endpoint that 404s by
        // design). A verdict is only ever computed against one specific token, so a credential that is not
        // that token is not evidence for it.
        val store = InMemoryLoopStateStore()
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId))
        loop(
            marketplace = mp,
            clock = clock,
            loopState = store,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = mismatchSteamId.value)),
        ).runOnce()
        assertEquals(mismatchSteamId.value, store.steamMismatchTokenId(), "precondition: blocked on the wrong token")

        // A respawned worker whose vault now holds the LINKED account, and whose heartbeat cannot complete.
        val down = FakeMarketplaceClient().also { it.heartbeatThrowable = MarketplaceServerErrorException(503) }
        val respawned = loop(marketplace = down, clock = clock, loopState = store)
        respawned.runOnce()

        assertTrue(!respawned.linkedSteamIdMismatch, "a credential for another account voids the verdict")
        assertEquals(null, store.steamMismatchTokenId())
    }

    @Test
    fun no_session_mint_is_attempted_while_the_wrong_account_block_is_set() = runTest {
        // The mint redeems whichever account the browser's durable "remember me" credential names, so on this
        // axis it is as likely to hand back the account the user is signing OUT of as the linked one — and the
        // host turns that very logout (the banner's own "Switch account") into this cycle via its cookie watch.
        val store = InMemoryLoopStateStore()
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = matchingSteamId))
        loop(
            marketplace = mp,
            clock = clock,
            loopState = store,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = mismatchSteamId.value)),
        ).runOnce()
        assertEquals(mismatchSteamId.value, store.steamMismatchTokenId(), "precondition: blocked on the wrong account")

        // The user signs out to switch accounts: the session is GONE and the durable credential would restore
        // the wrong account. The mint must not be attempted at all.
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE, mintRestoresSession = true)
        val respawned = loop(
            marketplace = mp,
            clock = clock,
            loopState = store,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            scraper = FakeSteamSessionScraper(result = null),
            sessionRefresher = refresher,
        )
        respawned.runOnce()

        assertTrue(refresher.forcedCalls.isEmpty(), "no mint while the wrong-account block is set")
        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, respawned.blockingState, "the signed-out prompt takes over")
    }

    @Test
    fun heartbeat_linked_steam_id_match_runs_directives_and_reads() = runTest {
        val creator = FakeSteamOfferCreator()
        val reader = FakeSteamReadClient()
        val observer = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked()),
                directives = listOf(createDirective()),
                ttlSeconds = 60,
                linkedSteamId = matchingSteamId,
            ),
        )
        val l = loop(marketplace = mp, creator = creator, reader = reader, directivesEnabled = true, eventObserver = observer)
        l.runOnce()
        assertEquals(1, creator.created.size, "a matching account executes directives normally")
        assertTrue(reader.offerStatusesCalls >= 1, "a matching account watches Steam normally")
        assertTrue(!l.linkedSteamIdMismatch)
        assertTrue(observer.events.none { it is LifecycleEvent.LinkedSteamIdMismatch })
    }

    @Test
    fun heartbeat_absent_linked_steam_id_is_not_a_mismatch() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60, linkedSteamId = null),
        )
        val l = loop(marketplace = mp, creator = creator, directivesEnabled = true)
        l.runOnce()
        assertEquals(1, creator.created.size, "a null linkedSteamId (old backend) never blocks")
        assertTrue(!l.linkedSteamIdMismatch)
    }

    @Test
    fun linked_steam_id_mismatch_flag_clears_on_matching_heartbeat() = runTest {
        val creator = FakeSteamOfferCreator()
        val clock = FakeClock()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60, linkedSteamId = mismatchSteamId),
        )
        val l = loop(marketplace = mp, creator = creator, directivesEnabled = true, clock = clock)
        l.runOnce()
        assertTrue(l.linkedSteamIdMismatch, "mismatch on the first heartbeat")
        assertTrue(creator.created.isEmpty())

        clock.advance(61.seconds) // past the ttl so the next cycle heartbeats again
        mp.heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective()), ttlSeconds = 60, linkedSteamId = matchingSteamId)
        l.runOnce()
        assertTrue(!l.linkedSteamIdMismatch, "the flag clears once the ids agree")
        assertEquals(1, creator.created.size, "directives resume once the account matches")
    }

    @Test
    fun create_trade_with_mismatched_linked_steam_id_blocks_write_and_returns_account_mismatch() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient()
        val observer = RecordingEventObserver()
        val result = loop(marketplace = mp, creator = creator, eventObserver = observer).createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
            linkedSteamId = mismatchSteamId,
        )
        assertTrue(creator.created.isEmpty(), "no Steam write when the FE-supplied linkedSteamId mismatches")
        assertTrue(result is CreateOfferResult.AccountMismatch)
        assertEquals(mismatchSteamId, result.linkedSteamId)
        assertEquals(matchingSteamId, result.tokenSteamId)
        assertEquals(1, mp.directiveOutcomes.size, "a FAILED outcome is reported to release the lease")
        assertEquals(DirectiveStatus.FAILED, mp.directiveOutcomes.single().status)
        assertTrue(observer.events.any { it is LifecycleEvent.LinkedSteamIdMismatch })
    }

    @Test
    fun create_trade_with_matching_linked_steam_id_creates() = runTest {
        val creator = FakeSteamOfferCreator()
        val result = loop(creator = creator).createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
            linkedSteamId = matchingSteamId,
        )
        assertEquals(1, creator.created.size)
        assertTrue(result is CreateOfferResult.NeedsConfirmation)
    }

    @Test
    fun create_trade_without_a_host_linked_steam_id_still_honours_a_known_mismatch() = runTest {
        // The host argument is optional, so omitting it must not buy a Steam write on a session the last
        // heartbeat already proved is wrong-account. The guard falls back to the loop's own verdict.
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(ttlSeconds = 300, linkedSteamId = mismatchSteamId),
        )
        val observer = RecordingEventObserver()
        val l = loop(marketplace = mp, creator = creator, eventObserver = observer)
        l.runOnce()
        assertTrue(l.linkedSteamIdMismatch, "precondition: the heartbeat detected the mismatch")

        val result = l.createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
            // linkedSteamId deliberately omitted, as a forgetful host would.
        )
        assertTrue(creator.created.isEmpty(), "the library already knew the account was wrong")
        assertTrue(result is CreateOfferResult.AccountMismatch)
        assertEquals(mismatchSteamId, result.linkedSteamId, "the id the heartbeat reported is surfaced")
        assertTrue(observer.events.any { it is LifecycleEvent.LinkedSteamIdMismatch })
    }

    @Test
    fun create_trade_without_a_host_linked_steam_id_creates_when_no_mismatch_is_known() = runTest {
        // The fallback must not become a block-by-default: with no mismatch on record, an omitted argument
        // stays "unknown" and the write proceeds (matching AccountBinding's fail-open on an absent id).
        val creator = FakeSteamOfferCreator()
        val result = loop(creator = creator).createTrade(
            DirectiveId("dir-ft"),
            DealId("deal-ft"),
            TradeDraft(SteamId(PARTNER), listOf(AssetId("asset-1")), "trade-token"),
        )
        assertEquals(1, creator.created.size)
        assertTrue(result is CreateOfferResult.NeedsConfirmation)
    }

    // ---- deal-keyed duplicate guard (one live Steam write per deal, whatever the caller sends) -----

    private fun draft(assetId: String = "asset-1") = TradeDraft(SteamId(PARTNER), listOf(AssetId(assetId)), "trade-token")

    /**
     * The incident this guard exists for: the FE relayed one "create trade" three times and the extension
     * POSTed `tradeoffer/new/send` three times — three live Steam offers for one deal.
     */
    @Test
    fun three_concurrent_create_trade_calls_for_one_deal_write_to_steam_once() = runTest {
        val creator = FakeSteamOfferCreator()
        val observer = RecordingEventObserver()
        val l = loop(creator = creator, eventObserver = observer)
        val results = (1..3).map { async { l.createTrade(DirectiveId("dir-ft"), DealId("deal-ft"), draft()) } }.map { it.await() }

        assertEquals(1, creator.created.size, "only one create may reach Steam")
        assertEquals(1, results.count { it is CreateOfferResult.NeedsConfirmation })
        assertEquals(2, observer.events.count { it is LifecycleEvent.DuplicateWriteSuppressed })
    }

    /** The worst case: the host presents a *fresh* directive_id each time, which directive-id dedup cannot see. */
    @Test
    fun repeated_create_trade_calls_under_distinct_directive_ids_write_to_steam_once() = runTest {
        val creator = FakeSteamOfferCreator()
        val l = loop(creator = creator)
        val first = l.createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        val second = l.createTrade(DirectiveId("dir-2"), DealId("deal-ft"), draft())
        val third = l.createTrade(DirectiveId("dir-3"), DealId("deal-ft"), draft())

        assertEquals(1, creator.created.size, "a fresh directive_id must not buy a second Steam write")
        assertIs<CreateOfferResult.NeedsConfirmation>(first)
        // The duplicates replay the FIRST result, so a retrying caller renders the real offer, not an error.
        assertEquals(first.offerId, assertIs<CreateOfferResult.AlreadyCreated>(second).offerId)
        assertEquals(first.offerId, assertIs<CreateOfferResult.AlreadyCreated>(third).offerId)
    }

    /** A suppressed duplicate still answers the lease it was given, or the backend parks the deal to its TTL. */
    @Test
    fun a_suppressed_duplicate_reports_the_first_offer_id_under_its_own_directive_id() = runTest {
        val mp = FakeMarketplaceClient()
        val l = loop(marketplace = mp, creator = FakeSteamOfferCreator())
        l.createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        l.createTrade(DirectiveId("dir-2"), DealId("deal-ft"), draft())

        assertEquals(listOf(DirectiveId("dir-1"), DirectiveId("dir-2")), mp.directiveOutcomes.map { it.directiveId })
        val restated = mp.directiveOutcomes.last()
        assertEquals(OfferId("offer-created"), restated.steamOfferId, "the restatement names the SAME offer")
        assertEquals(DealId("deal-ft"), restated.dealId)
    }

    /** Nothing was written, so the claim must not linger and block the user's next attempt. */
    @Test
    fun a_failed_create_releases_the_claim_so_a_retry_can_write() = runTest {
        val creator = FakeSteamOfferCreator(result = CreateOfferResult.Failed("steam said no"))
        val claims = PersistedDealWriteClaimStore()
        val l = loop(creator = creator, claims = claims)
        assertIs<CreateOfferResult.Failed>(l.createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft()))
        assertTrue(claims.all().isEmpty(), "a create that wrote nothing leaves no claim behind")

        val retryCreator = FakeSteamOfferCreator()
        assertIs<CreateOfferResult.NeedsConfirmation>(
            loop(creator = retryCreator, claims = claims).createTrade(DirectiveId("dir-2"), DealId("deal-ft"), draft()),
        )
        assertEquals(1, retryCreator.created.size)
    }

    /**
     * The reported bug: a create Steam refused over its 5-offers-per-partner cap reached the host as a bare
     * "failed" plus one English sentence, so the page could only say "failed". The loop reads that sentence
     * once, here, and hands back a code — nobody downstream re-parses Steam's prose.
     */
    @Test
    fun a_refused_create_names_its_cause_on_both_write_paths() = runTest {
        val perPartner = "Steam create returned HTTP 500: {\"strError\":\"You have sent too many trade offers, " +
            "or have too many outstanding trade offers with luckydm07. Please cancel some before sending more.\"}"
        val fastPath = loop(creator = FakeSteamOfferCreator(result = CreateOfferResult.Failed(perPartner)))
            .createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        assertEquals(SteamCreateFailureCause.COUNTERPARTY_OFFER_LIMIT, assertIs<CreateOfferResult.Failed>(fastPath).cause)

        // Steam named no partner: the account-wide cap, which no amount of cancelling with *this* partner fixes.
        val accountWide = "Steam create returned HTTP 500: {\"strError\":\"You have sent too many trade offers.\"}"
        val wide = loop(creator = FakeSteamOfferCreator(result = CreateOfferResult.Failed(accountWide)))
            .createTrade(DirectiveId("dir-2"), DealId("deal-ft2"), draft())
        assertEquals(SteamCreateFailureCause.OUTGOING_OFFER_LIMIT, assertIs<CreateOfferResult.Failed>(wide).cause)

        // A creator that throws is a failure too, and its cause is read from the same text.
        val threw = loop(creator = FakeSteamOfferCreator(throwable = IllegalStateException("Failed to fetch")))
            .createTrade(DirectiveId("dir-3"), DealId("deal-ft3"), draft())
        assertEquals(SteamCreateFailureCause.TRANSPORT, assertIs<CreateOfferResult.Failed>(threw).cause)

        // Unrecognised stays unrecognised: a guessed cause is worse on screen than an honest "it failed".
        val other = loop(creator = FakeSteamOfferCreator(result = CreateOfferResult.Failed("steam said no")))
            .createTrade(DirectiveId("dir-4"), DealId("deal-ft4"), draft())
        assertEquals(SteamCreateFailureCause.OTHER, assertIs<CreateOfferResult.Failed>(other).cause)
    }

    /** A create blocked before any Steam write must not consume the deal's claim either. */
    @Test
    fun an_account_mismatch_leaves_no_claim_behind() = runTest {
        val claims = PersistedDealWriteClaimStore()
        val creator = FakeSteamOfferCreator()
        val result = loop(creator = creator, claims = claims).createTrade(
            DirectiveId("dir-1"),
            DealId("deal-ft"),
            draft(),
            linkedSteamId = mismatchSteamId,
        )
        assertIs<CreateOfferResult.AccountMismatch>(result)
        assertTrue(creator.created.isEmpty())
        assertTrue(claims.all().isEmpty(), "nothing was written, so nothing is claimed")
    }

    // ---- the write axis: the browser cookie session, which the linked-id check cannot see ----------

    /**
     * The wrong-account hole in the inverse direction from the linked-id check. The two Steam axes
     * authenticate differently: reads pass the credential as an `access_token`, while `create_offer` /
     * `cancel_offer` POST with the browser's `steamLoginSecure` session and **never use** the credential
     * they are handed. So with the linked account's token cached and the browser signed in as someone
     * else, `AccountBinding` reads MATCH — both of its ids are the token's account — and the write goes
     * out from the wrong Steam account.
     *
     * The scraper here keeps insisting on the cached account while the cookie says otherwise, which is
     * what pins the disagreement in place for the test; in production the scrape reads its token off a
     * page Steam serves to that same cookie, so it converges on the cookie's account within one cycle.
     */
    private fun wrongAccountCookie() = FakeSteamSessionRefresher(cookieSteamId = mismatchSteamId)

    @Test
    fun a_create_is_refused_when_the_browser_session_is_another_account() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient()
        val observer = RecordingEventObserver()
        val result = loop(
            marketplace = mp,
            creator = creator,
            eventObserver = observer,
            sessionRefresher = wrongAccountCookie(),
        ).createTrade(DirectiveId("dir-ft"), DealId("deal-ft"), draft(), linkedSteamId = matchingSteamId)

        assertTrue(creator.created.isEmpty(), "the token axis agreed, but the write would have come from another account")
        assertIs<CreateOfferResult.AccountMismatch>(result)
        assertEquals(DirectiveStatus.FAILED, mp.directiveOutcomes.single().status, "a FAILED outcome releases the lease")
        val event = observer.events.filterIsInstance<LifecycleEvent.SteamSessionAccountMismatch>().single()
        assertEquals("create_offer", event.kind)
        assertEquals(matchingSteamId.value, event.tokenSteamId)
    }

    @Test
    fun a_leased_create_directive_is_refused_when_the_browser_session_is_another_account() = runTest {
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                directives = listOf(createDirective()),
                ttlSeconds = 300,
                linkedSteamId = matchingSteamId,
            ),
        )
        val l = loop(marketplace = mp, creator = creator, directivesEnabled = true, sessionRefresher = wrongAccountCookie())
        l.runOnce()

        assertTrue(creator.created.isEmpty(), "the leased directive path is gated too, not just the FE fast path")
        assertEquals(TrackerBlock.NONE, l.blockingState, "the heartbeat's own ids agree — this block lives at the write site")
        assertEquals(DirectiveStatus.FAILED, mp.directiveOutcomes.single().status)
    }

    /**
     * Cancel is gated on the same terms, and it matters for its own reason: a cancel POSTed from another
     * account's session cannot cancel our offer, yet the loop reads Steam's answer as the whole outcome —
     * so a cancel that changed nothing could be reported SUCCESS while the offer is still live, and its
     * create claim released for a re-create.
     */
    @Test
    fun a_cancel_is_refused_when_the_browser_session_is_another_account() = runTest {
        val canceller = FakeSteamOfferCanceller()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                directives = listOf(cancelDirective()),
                ttlSeconds = 300,
                linkedSteamId = matchingSteamId,
            ),
        )
        val observer = RecordingEventObserver()
        val l = loop(
            marketplace = mp,
            canceller = canceller,
            directivesEnabled = true,
            eventObserver = observer,
            sessionRefresher = wrongAccountCookie(),
        )
        l.runOnce()

        assertTrue(canceller.cancelledOffers.isEmpty(), "no cancel POST from a session that cannot own our offer")
        assertEquals(DirectiveStatus.FAILED, mp.directiveOutcomes.single().status, "reported FAILED, never a phantom SUCCESS")
        assertEquals("cancel_offer", observer.events.filterIsInstance<LifecycleEvent.SteamSessionAccountMismatch>().single().kind)
    }

    /** Fail-open: an unreadable cookie store must never block a legitimate write. */
    @Test
    fun writes_proceed_when_the_session_owner_cannot_be_read() = runTest {
        val creator = FakeSteamOfferCreator()
        val result = loop(
            creator = creator,
            sessionRefresher = FakeSteamSessionRefresher(stateFailsWith = RuntimeException("cookie store gone")),
        ).createTrade(DirectiveId("dir-ft"), DealId("deal-ft"), draft(), linkedSteamId = matchingSteamId)

        assertIs<CreateOfferResult.NeedsConfirmation>(result)
        assertEquals(1, creator.created.size)
    }

    /** The claim guards the deal only while a duplicate is possible — the heartbeat is what releases it. */
    @Test
    fun a_claim_is_released_once_its_deal_leaves_active_tracking() = runTest {
        val claims = PersistedDealWriteClaimStore()
        val mp = FakeMarketplaceClient(heartbeatResponse = HeartbeatResponse(ttlSeconds = 300))
        val l = loop(marketplace = mp, creator = FakeSteamOfferCreator(), claims = claims)
        l.createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        assertEquals(1, claims.all().size, "precondition: the completed create holds a claim")

        l.forceHeartbeatNow()
        l.runOnce() // heartbeat reports no active_tracking → the deal is done or gone
        assertTrue(claims.all().isEmpty(), "a deal the backend no longer watches needs no guard")
    }

    /** A deal the backend still watches *with* the created offer must keep its guard. */
    @Test
    fun a_claim_survives_a_heartbeat_that_still_reports_the_created_offer() = runTest {
        val claims = PersistedDealWriteClaimStore()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(
                activeTracking = listOf(tracked("deal-ft", "offer-created")),
                ttlSeconds = 300,
            ),
        )
        val creator = FakeSteamOfferCreator()
        val l = loop(marketplace = mp, creator = creator, claims = claims)
        l.createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        l.forceHeartbeatNow()
        l.runOnce()

        assertEquals(1, claims.all().size)
        assertIs<CreateOfferResult.AlreadyCreated>(l.createTrade(DirectiveId("dir-2"), DealId("deal-ft"), draft()))
        assertEquals(1, creator.created.size)
    }

    /** The directive path inherits the same guard — a re-lease under a new id must not re-write. */
    @Test
    fun a_re_leased_create_under_a_new_directive_id_does_not_write_to_steam_again() = runTest {
        val claims = PersistedDealWriteClaimStore()
        val creator = FakeSteamOfferCreator()
        val observer = RecordingEventObserver()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective(id = "dir-1")), ttlSeconds = 300),
        )
        val l = loop(marketplace = mp, creator = creator, claims = claims, directivesEnabled = true, eventObserver = observer)
        l.runOnce()
        assertEquals(1, creator.created.size, "precondition: the first lease created the offer")

        // The backend re-leases the same work under a FRESH id (our outcome report never landed), which the
        // handled-directive set cannot recognise.
        mp.heartbeatResponse = HeartbeatResponse(directives = listOf(createDirective(id = "dir-2")), ttlSeconds = 300)
        l.forceHeartbeatNow()
        l.runOnce()

        assertEquals(1, creator.created.size, "the re-lease must not create a second live offer")
        assertTrue(observer.events.any { it is LifecycleEvent.DuplicateWriteSuppressed })
        assertEquals(listOf(DirectiveId("dir-1"), DirectiveId("dir-2")), mp.directiveOutcomes.map { it.directiveId })
        assertEquals(OfferId("offer-created"), mp.directiveOutcomes.last().steamOfferId)
    }

    /** Two different deals must never block each other — the key is (deal, action), not the action alone. */
    @Test
    fun creates_for_different_deals_are_independent() = runTest {
        val creator = FakeSteamOfferCreator()
        val l = loop(creator = creator)
        assertIs<CreateOfferResult.NeedsConfirmation>(l.createTrade(DirectiveId("dir-1"), DealId("deal-a"), draft()))
        assertIs<CreateOfferResult.NeedsConfirmation>(l.createTrade(DirectiveId("dir-2"), DealId("deal-b"), draft("asset-2")))
        assertEquals(2, creator.created.size)
    }

    /** A cancel for a deal whose create is claimed must still run — a create claim is not a deal-wide lock. */
    @Test
    fun a_cancel_is_not_blocked_by_the_create_claim_for_the_same_deal() = runTest {
        val claims = PersistedDealWriteClaimStore()
        val canceller = FakeSteamOfferCanceller()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(cancelDirective(dealId = "deal-ft")), ttlSeconds = 300),
        )
        val l = loop(marketplace = mp, canceller = canceller, claims = claims, directivesEnabled = true)
        l.createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(1, canceller.cancelledOffers.size, "the cancel write is keyed separately from the create")
    }

    @Test
    fun a_re_leased_cancel_under_a_new_directive_id_does_not_cancel_twice() = runTest {
        val claims = PersistedDealWriteClaimStore()
        val canceller = FakeSteamOfferCanceller()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(cancelDirective(id = "dir-c1")), ttlSeconds = 300),
        )
        val l = loop(marketplace = mp, canceller = canceller, claims = claims, directivesEnabled = true)
        l.runOnce()
        assertEquals(1, canceller.cancelledOffers.size)

        mp.heartbeatResponse = HeartbeatResponse(directives = listOf(cancelDirective(id = "dir-c2")), ttlSeconds = 300)
        l.forceHeartbeatNow()
        l.runOnce()
        assertEquals(1, canceller.cancelledOffers.size, "the offer is already gone; a second cancel could only fail")
    }

    /**
     * The legitimate re-create flow: the offer was cancelled, so the deal genuinely needs a new one. The
     * cancel releases the create claim causally, instead of making the deal wait out the TTL.
     */
    @Test
    fun a_successful_cancel_releases_the_deals_create_claim_so_a_re_create_can_write() = runTest {
        val claims = PersistedDealWriteClaimStore()
        val creator = FakeSteamOfferCreator()
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(directives = listOf(cancelDirective(dealId = "deal-ft")), ttlSeconds = 300),
        )
        val l = loop(marketplace = mp, creator = creator, canceller = FakeSteamOfferCanceller(), claims = claims, directivesEnabled = true)
        l.createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        l.forceHeartbeatNow()
        l.runOnce() // the cancel lands → the created offer is gone

        assertIs<CreateOfferResult.NeedsConfirmation>(l.createTrade(DirectiveId("dir-2"), DealId("deal-ft"), draft()))
        assertEquals(2, creator.created.size, "a re-create after a cancel is not a duplicate")
    }

    /** A worker respawn must not reopen the duplicate window — that is what the persisted ledger is for. */
    @Test
    fun a_claim_survives_a_respawn_over_the_same_storage() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        val creator = FakeSteamOfferCreator()
        loop(creator = creator, claims = PersistedDealWriteClaimStore(storage))
            .createTrade(DirectiveId("dir-1"), DealId("deal-ft"), draft())
        assertEquals(1, creator.created.size)

        val respawnedCreator = FakeSteamOfferCreator()
        val result = loop(creator = respawnedCreator, claims = PersistedDealWriteClaimStore(storage))
            .createTrade(DirectiveId("dir-2"), DealId("deal-ft"), draft())
        assertTrue(respawnedCreator.created.isEmpty(), "the restored claim still blocks the duplicate")
        assertEquals(OfferId("offer-created"), assertIs<CreateOfferResult.AlreadyCreated>(result).offerId)
    }

    // ---- Steam-session block (STEAM_SESSION_MISSING) --------------------------------------------

    /**
     * The reported bug: signed out of Steam, the cycle stops at the credential gate before the heartbeat
     * and nothing is tracked — but `blockingState` used to stay NONE, so a host mirroring it kept telling
     * the user tracking was live. Read from INSIDE the event handler, because that is how a JS host reads
     * it (delivery is synchronous, and this path emits nothing else afterwards).
     */
    @Test
    fun steam_session_missing_is_the_blocking_state_when_the_relogin_event_is_delivered() = runTest {
        val stateAtEmit = mutableListOf<TrackerBlock>()
        lateinit var l: TradeTrackerLoop
        val observer = object : EventObserver {
            override suspend fun onEvent(event: LifecycleEvent) {
                if (event is LifecycleEvent.ReLoginNeeded && event.axis == "steam") stateAtEmit += l.blockingState
            }
        }
        val store = InMemoryLoopStateStore()
        l = loop(
            credential = false,
            loopState = store,
            sessionRefresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE),
            eventObserver = observer,
        )

        assertEquals(TickOutcome.EMPTY, l.runOnce())

        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, l.blockingState)
        assertEquals(
            listOf(TrackerBlock.STEAM_SESSION_MISSING),
            stateAtEmit,
            "blockingState must already be STEAM_SESSION_MISSING when ReLoginNeeded(steam) is delivered",
        )
        assertTrue(store.steamSessionMissing(), "the block is persisted, so it survives a worker respawn")
        assertNull(store.nextHeartbeatAt(), "a blocked cycle must leave the schedule due")
    }

    /**
     * A failed scrape on its own is NOT proof of a logout — the scraper maps a Steam rate-limit, 5xx or
     * page/regex drift to the same `null`. With the session cookie still there it stays the signal-only
     * re-login hint, so a Steam hiccup can never tell a signed-in user to sign in.
     */
    @Test
    fun a_failed_scrape_with_a_live_session_cookie_stays_signal_only() = runTest {
        val l = loop(credential = false, sessionRefresher = FakeSteamSessionRefresher(state = SteamWebSessionState.ALIVE))

        assertEquals(TickOutcome.EMPTY, l.runOnce())

        assertTrue(l.needsReLogin, "the signal-only re-login hint still fires")
        assertEquals(TrackerBlock.NONE, l.blockingState, "an unproven logout must not raise a user-facing block")
    }

    /**
     * A logout right after a successful heartbeat: the cached Steam token is still fresh, so `current()`
     * would return it without touching the network and the loop would keep claiming everything is fine for
     * the rest of that token's ~24h life. The provider's cookie liveness check is what catches it.
     */
    @Test
    fun a_logout_is_detected_while_the_cached_steam_token_is_still_fresh() = runTest {
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.ALIVE)
        val scraper = FakeSteamSessionScraper() // never reached: the vault credential is fresh
        val l = loop(credential = true, scraper = scraper, sessionRefresher = refresher)
        l.runOnce()
        assertEquals(TrackerBlock.NONE, l.blockingState)

        refresher.state = SteamWebSessionState.GONE // the user signs out of Steam; the cached token is untouched
        l.forceHeartbeatNow()
        assertEquals(TickOutcome.EMPTY, l.runOnce())

        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, l.blockingState)
        assertEquals(0, scraper.scrapeCalls, "the cookie read alone settles it — no scrape, no network")
    }

    /**
     * The other half of the reported bug: MV3 kills the worker constantly, and a respawn inside a live ttl
     * window with nothing to watch decides IDLE *before* any credential work — so with the flag in memory
     * only, a host that re-reads the reason on the cycle's first event would overwrite a correct prompt
     * with "nothing is blocking". The persisted flag must therefore be restored BEFORE that first emit.
     */
    @Test
    fun a_respawn_restores_the_steam_session_block_before_the_first_event() = runTest {
        val clock = FakeClock()
        val store = InMemoryLoopStateStore()
        store.setSteamSessionMissing(true)
        store.setNextHeartbeatAt(clock.now() + 300.seconds) // a live backend-ttl window

        val stateAtEmit = mutableListOf<TrackerBlock>()
        lateinit var respawned: TradeTrackerLoop
        val observer = object : EventObserver {
            override suspend fun onEvent(event: LifecycleEvent) {
                if (event is LifecycleEvent.CycleStarted) stateAtEmit += respawned.blockingState
            }
        }
        val scraper = FakeSteamSessionScraper(result = null)
        respawned = loop(
            clock = clock,
            loopState = store,
            credential = false,
            scraper = scraper,
            sessionRefresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE),
            eventObserver = observer,
        )

        assertEquals(TickOutcome.EMPTY, respawned.runOnce())

        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, respawned.blockingState)
        assertEquals(
            listOf(TrackerBlock.STEAM_SESSION_MISSING),
            stateAtEmit,
            "the restored block must already be readable when the first event of the cycle is delivered",
        )
        assertEquals(0, scraper.scrapeCalls, "an idle wake still does no Steam work")
    }

    /** Signing back into Steam clears the block on the first cycle that acquires a credential. */
    @Test
    fun steam_session_missing_clears_once_a_credential_is_acquired_again() = runTest {
        val store = InMemoryLoopStateStore()
        store.setSteamSessionMissing(true)
        val l = loop(loopState = store, credential = true)

        l.runOnce()

        assertEquals(TrackerBlock.NONE, l.blockingState)
        assertFalse(store.steamSessionMissing(), "the persisted block clears too, so a respawn stays clean")
    }

    /**
     * Precedence 1 > 2, end to end: signed out of BOTH must prompt for DMarket — it is the upstream
     * problem (nothing here works without a DMarket session) and the first thing a cycle establishes. The
     * second half is the anti-regression that matters: once DMarket is back the Steam prompt must take
     * over *immediately*, on a cycle that never reaches a heartbeat. That only holds because the guard
     * drops the missing-connection sticky the moment a credential is in hand — leaving it set would pin
     * "sign into DMarket" on screen for the whole Steam outage.
     */
    @Test
    fun a_missing_dmarket_connection_outranks_a_missing_steam_session() = runTest {
        val mpCredentials = FakeMarketplaceCredentialProvider(result = null) // DMarket logged out
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE) // …and Steam too
        val l = loop(
            marketplaceCredentials = marketplaceProvider(mpCredentials),
            credential = false,
            scraper = FakeSteamSessionScraper(result = null),
            sessionRefresher = refresher,
        )
        l.runOnce()
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState, "DMarket is the upstream prompt")

        mpCredentials.result = fakeMarketplaceCredential() // the user signs into DMarket; Steam still out
        l.forceHeartbeatNow()
        l.runOnce()

        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, l.blockingState, "the Steam prompt takes over at once")
    }

    /**
     * Re-deriving the missing-connection state every cycle must not re-emit its entry event: the sticky is
     * now cleared as soon as a credential is in hand, so the announcement is latched separately. Without
     * that latch a persistent DMarket logout would emit `ReLoginNeeded("marketplace")` on every wake.
     */
    @Test
    fun the_marketplace_relogin_event_stays_entry_only_while_the_state_is_re_derived_each_cycle() = runTest {
        // The shape that needs the latch: the provider hands out a token every cycle (so the guard clears
        // the sticky) and the backend rejects it every cycle (so the 401 path sets it again).
        val mpCredentials = FakeMarketplaceCredentialProvider()
        val mp = FakeMarketplaceClient().apply { heartbeatThrowable = MarketplaceUnauthorizedException() }
        val events = RecordingEventObserver()
        val l = loop(
            marketplace = mp,
            marketplaceCredentials = marketplaceProvider(mpCredentials),
            eventObserver = events,
        )

        l.runOnce()
        l.forceHeartbeatNow()
        l.runOnce()
        l.forceHeartbeatNow()
        l.runOnce()

        assertEquals(
            1,
            events.events.count { it is LifecycleEvent.ReLoginNeeded && it.axis == "marketplace" },
            "ReLoginNeeded(marketplace) is announced once per episode, not once per cycle",
        )
        assertEquals(TrackerBlock.DM_SESSION_MISSING, l.blockingState)
    }

    /**
     * Precedence 3 > 4, end to end, and the reason it is safe: the shipped prod `/heartbeat` route answers
     * 404 by design, so DM_CONNECTION_ERROR is permanently set there — ranking it above the wrong-account
     * verdict (as an earlier build did) meant the "wrong Steam account" prompt could never be displayed in
     * prod at all. The verdict is not left stale either: it is released by a credential naming a different
     * account, which needs no heartbeat.
     */
    @Test
    fun a_wrong_account_verdict_outranks_a_permanently_erroring_backend() = runTest {
        val mp = FakeMarketplaceClient(
            heartbeatResponse = HeartbeatResponse(linkedSteamId = matchingSteamId, ttlSeconds = 60),
        )
        val l = loop(
            marketplace = mp,
            vaultCredential = fakeSteamCredential(token = "wrong-account-token", steamId = mismatchSteamId.value),
            scraper = FakeSteamSessionScraper(result = fakeSteamCredential(steamId = mismatchSteamId.value)),
        )
        l.runOnce()
        assertEquals(TrackerBlock.STEAM_ACCOUNT_MISMATCH, l.blockingState, "precondition: the wrong account")

        // The endpoint now 404s on every wake (the prod shape), which sets DM_CONNECTION_ERROR for good.
        mp.heartbeatThrowable = MarketplaceServerErrorException(404)
        l.forceHeartbeatNow()
        l.runOnce()

        assertTrue(l.marketplaceServerError, "precondition: the backend error state really is set")
        assertEquals(
            TrackerBlock.STEAM_ACCOUNT_MISMATCH,
            l.blockingState,
            "an unactionable backend error must never mask the account prompt",
        )
    }

    /**
     * Once the Steam session is known to be gone, a wake must cost no Steam requests. The netlog of the
     * reported bug showed an `ajaxrefresh` + a community scrape every few minutes, forever, neither of
     * which can succeed without a session cookie. The one mint attempt per episode is the deliberate
     * exception (see the mint tests below); it does not repeat, and the scrape never runs at all —
     * without a session that page can only ever come back empty.
     */
    @Test
    fun a_known_missing_steam_session_costs_no_steam_requests_per_wake() = runTest {
        val scraper = FakeSteamSessionScraper(result = null)
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        val l = loop(credential = false, scraper = scraper, sessionRefresher = refresher)

        // No forceHeartbeatNow() between them: a blocked cycle never advances the schedule, so each of
        // these is an ordinary due wake. (An explicit force is a deliberate retry — see its own test.)
        l.runOnce()
        l.runOnce()
        l.runOnce()

        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, l.blockingState)
        assertEquals(1, refresher.refreshCalls, "one mint attempt for the episode, not one per wake")
        assertEquals(0, scraper.scrapeCalls, "three wakes must not produce a single scrape")
    }

    /** …and the moment a session cookie is back, the very next wake recovers with no host nudge needed. */
    @Test
    fun a_returned_steam_session_cookie_recovers_on_the_next_wake() = runTest {
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        val l = loop(credential = false, scraper = FakeSteamSessionScraper(), sessionRefresher = refresher)
        l.runOnce()
        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, l.blockingState)

        refresher.state = SteamWebSessionState.ALIVE // the user signed back into Steam
        l.forceHeartbeatNow()
        l.runOnce()

        assertEquals(TrackerBlock.NONE, l.blockingState)
    }

    /**
     * A session that expired while nothing was running cannot be renewed — only replaced. So the first
     * cycle that finds it gone asks Steam to mint a new one from the durable "remember me" credential the
     * browser still holds (the same handshake Steam's own login page runs), and tracking resumes with no
     * user action at all.
     */
    @Test
    fun a_gone_steam_session_is_minted_and_the_cycle_recovers() = runTest {
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE, mintRestoresSession = true)
        val l = loop(credential = true, sessionRefresher = refresher)

        l.runOnce()

        assertEquals(listOf(true), refresher.forcedCalls, "exactly one mint attempt, and it must be forced")
        assertEquals(TrackerBlock.NONE, l.blockingState, "a minted session clears the block with no user action")
    }

    /**
     * The counterweight: a refused mint must never become per-wake traffic. One attempt per episode — the
     * next one only after a session has existed again, which is the only thing that can change the answer.
     */
    @Test
    fun a_refused_mint_is_attempted_once_and_never_retried_per_wake() = runTest {
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE) // Steam refuses
        val l = loop(credential = true, sessionRefresher = refresher)

        l.runOnce()
        l.runOnce()
        l.runOnce()

        assertEquals(listOf(true), refresher.forcedCalls, "three ordinary wakes, one mint attempt")
        assertEquals(TrackerBlock.STEAM_SESSION_MISSING, l.blockingState, "and the host still gets the prompt")
    }

    /** A session merely due for renewal is renewed, never minted — the mint is only for a session that is gone. */
    @Test
    fun a_session_due_for_renewal_is_not_minted() = runTest {
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.NEEDS_REFRESH)
        val l = loop(credential = true, sessionRefresher = refresher)

        l.runOnce()

        assertTrue(refresher.forcedCalls.none { it }, "renewal must not be a mint")
        assertEquals(TrackerBlock.NONE, l.blockingState)
    }

    /**
     * The regression this pins, reported from a live build: gating the mint on "the block is not recorded
     * yet" meant a client that was ALREADY blocked never attempted one — every upgrading user, and every
     * respawn. The attempt must depend on whether we have asked this episode, not on when we noticed.
     */
    @Test
    fun a_mint_is_attempted_even_when_the_block_was_already_persisted() = runTest {
        val store = InMemoryLoopStateStore()
        store.setSteamSessionMissing(true) // an earlier cycle (or an older build) already recorded it
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE, mintRestoresSession = true)
        val l = loop(credential = true, loopState = store, sessionRefresher = refresher)

        l.runOnce()

        assertEquals(listOf(true), refresher.forcedCalls, "an already-blocked client still gets its attempt")
        assertEquals(TrackerBlock.NONE, l.blockingState)
        assertFalse(store.steamMintAttempted(), "the episode is over, so the latch clears with it")
    }

    /** The latch survives a respawn, so "once per episode" holds across the worker dying. */
    @Test
    fun a_mint_attempt_is_not_repeated_after_a_respawn() = runTest {
        val store = InMemoryLoopStateStore()
        val first = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        loop(credential = true, loopState = store, sessionRefresher = first).runOnce()
        assertEquals(listOf(true), first.forcedCalls)
        assertTrue(store.steamMintAttempted(), "the attempt is persisted")

        val respawned = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE)
        loop(credential = true, loopState = store, sessionRefresher = respawned).runOnce()

        assertTrue(respawned.forcedCalls.isEmpty(), "a fresh worker must not re-ask")
    }

    /** An explicit force is a request to retry the mint too — otherwise force-tick can't reach it at all. */
    @Test
    fun an_explicit_force_retries_the_mint() = runTest {
        val store = InMemoryLoopStateStore()
        store.setSteamSessionMissing(true)
        store.setSteamMintAttempted(true) // already asked this episode
        val refresher = FakeSteamSessionRefresher(state = SteamWebSessionState.GONE, mintRestoresSession = true)
        val l = loop(credential = true, loopState = store, sessionRefresher = refresher)

        l.runOnce()
        assertTrue(refresher.forcedCalls.isEmpty(), "the latch holds on an ordinary wake")

        l.forceHeartbeatNow()
        l.runOnce()

        assertEquals(listOf(true), refresher.forcedCalls, "an explicit force clears the latch and retries")
        assertEquals(TrackerBlock.NONE, l.blockingState)
    }
}
