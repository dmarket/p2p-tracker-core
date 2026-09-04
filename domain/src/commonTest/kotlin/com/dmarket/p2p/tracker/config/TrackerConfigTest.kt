package com.dmarket.p2p.tracker.config

import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.policy.CadencePolicy
import com.dmarket.p2p.tracker.policy.PollClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class TrackerConfigTest {
    private val defaults = TrackerConfig.defaults()

    @Test
    fun defaults_reproduce_the_in_code_baseline_verbatim() {
        // Cadence
        assertEquals(180_000, defaults.cadence.activeOfferIntervalMs)
        assertEquals(3_600_000, defaults.cadence.revertWatchIntervalMs)
        assertEquals(3_600_000, defaults.cadence.maxActionDelayMs)
        // Credentials + HTTP + notary
        assertEquals(60_000, defaults.credentials.steamSkewMs)
        assertEquals(60_000, defaults.credentials.marketplaceSkewMs)
        assertEquals(3_600_000, defaults.credentials.sessionGateHeadroomMs)
        assertEquals(30_000, defaults.http.requestTimeoutMs)
        // Marketplace 401 retry envelope
        assertEquals(3, defaults.marketplaceRetry.maxRetries)
        assertEquals(500, defaults.marketplaceRetry.retryBaseDelayMs)
        assertEquals(8_000, defaults.marketplaceRetry.retryMaxDelayMs)
        assertEquals(2, defaults.notary.maxConcurrency)
        // The notary a host that configures nothing attests through, and this file is its single owner —
        // `WasmNotaryProverTest` pins only that the default REACHES the prover, against the constant.
        //
        // Two assertions because they catch different mistakes, and neither implies the other:
        //  - the LITERAL, because this is a deployed endpoint: a typo in it is not a failing handshake in a
        //    test but a shipped build whose every proof dies in the field;
        //  - the CONSTANT, because the field default must be *sourced* from it. `PRODUCTION_NOTARY_URL` is
        //    public precisely so a host can report the notary its loop will use; a refactor that inlined the
        //    literal into the parameter would make the published constant a lie the line above cannot catch.
        //
        // The field stopped being nullable with this default — arming the prover is a property of the build
        // (a proving context) rather than of a remote-config publish that may never happen, which is the
        // state that left the reference extension proving nothing, invisibly.
        assertEquals("wss://api.dmarket.com/provenance/v1/", defaults.notary.notaryUrl)
        assertEquals(NotaryConfig.PRODUCTION_NOTARY_URL, defaults.notary.notaryUrl)
        assertEquals("wss://p2p-wss-proxy.dmarket.com", defaults.notary.proxyBaseUrl)
        // The MPC pre-processing bound, and the one notary value that costs bandwidth: the prover garbles
        // circuits for this many plaintext bytes whether the request uses them or not (42 MB uploaded for a
        // 717 B request, measured live at 2048). The proven request is `196 + len(token)` — 196 fixed for the
        // request line and the four injected headers on the larger axis — so 1024 admits an 828-char token
        // against the 521-char one actually traced. Pinned in BOTH directions: exceeding it fails every proof,
        // and growing it back silently doubles the upload.
        assertEquals(1_024, defaults.notary.maxSentData)
        // …and what `ProvenSentBudget` is allowed to give back of it. Pinned in both directions for the same
        // reason the ceiling above is: too small fails every proof, and a silent widening past 43 turns the
        // sizing off (the clamp hands back the ceiling) without anything else changing.
        assertEquals(15, defaults.notary.sentBudgetMarginPercent)
        assertEquals(16_384, defaults.notary.maxRecvData)
        // Pinned because it is only read on a FRESH wasm instance (the module memoizes one-time setup), so a
        // regression to 1 would not surface until some later proof stalled on a pool with no slack.
        assertEquals(4, defaults.notary.threadCount)
        // Steam endpoints + scrape
        assertEquals("https://api.steampowered.com", defaults.steamEndpoints.steamApiBaseUrl)
        assertEquals(50, defaults.steamEndpoints.historyMaxTrades)
        assertEquals("steamLoginSecure", defaults.steamScrape.steamSessionCookieName)
        // Marketplace scrape
        assertEquals("dm-trade-token", defaults.marketplaceScrape.cookieName)
        assertEquals("https://dmarket.com/", defaults.marketplaceScrape.refreshUrl)
        // Game
        assertEquals(2, defaults.game.cs2InventoryContextId)
        // Write claims + create-surface back-pressure
        assertEquals(900_000, defaults.writeClaims.claimTtlMs)
        assertEquals(5, defaults.steamWrites.maxCreatesPerPartnerPerCycle)
        assertEquals(20, defaults.steamWrites.maxCreatesPerCycle)
        assertEquals(4, defaults.steamWrites.maxConcurrentChains)
        assertEquals(3, defaults.steamWrites.globalBreakerThreshold)
        assertEquals(120_000, defaults.steamWrites.cooldownMinMs)
        assertEquals(120_000, defaults.steamWrites.cooldownBaseMs)
        assertEquals(1_800_000, defaults.steamWrites.cooldownMaxMs)
        assertEquals(false, defaults.steamWrites.reportThrottledWrites)
    }

    @Test
    fun the_create_cap_defaults_to_steams_own_outstanding_offer_limit() {
        // 5 is Steam's per-partner active-offer cap: past it every further create is refused, so attempting
        // more in one cycle can only produce doomed POSTs. Documented here so a bump is a deliberate act.
        assertEquals(5, defaults.steamWrites.maxCreatesPerPartnerPerCycle)
    }

    @Test
    fun a_proven_read_pins_its_host_to_the_allowed_steam_set() {
        // The host a proven read names is the host the MPC dials, and the request it issues carries the Steam
        // access token in its query string — so an unchecked value sends a device-only credential to whatever
        // origin it names, through our own proxy. `NotaryConfig` shipped claiming this was validated with no
        // `init` at all, so the guard is asserted rather than assumed.
        assertEquals("api.steampowered.com", NotaryConfig().offerRead.serverName)
        assertEquals("api.steampowered.com", NotaryConfig().historyRead.serverName)

        // A community host IS accepted — the structure has to be able to express a second host — but only
        // because it is a Steam host. Anything else is refused.
        //
        // It now also has to declare the auth model that host actually uses. The allow-list is per-auth:
        // `TOKEN_QUERY` is confined to the Web API host and `SESSION_COOKIE` to the web hosts, because a
        // token-authed community read is not a stricter version of anything — `steamcommunity.com` does not
        // accept `?access_token=`, so such a definition could only ever have issued an unauthenticated request.
        val community = ProvenRead(
            serverName = "steamcommunity.com",
            pathTemplate = "/x/{offerId}",
            revealJsonPaths = listOf("a"),
            auth = ProvenReadAuth.SESSION_COOKIE,
        )
        assertEquals("steamcommunity.com", community.serverName)

        // …and the two models cannot be mixed up: each host set rejects the other's auth.
        assertFails { community.copy(auth = ProvenReadAuth.TOKEN_QUERY) }
        assertFails { NotaryConfig().offerRead.copy(auth = ProvenReadAuth.SESSION_COOKIE) }

        assertFails { community.copy(serverName = "evil.example") }
        // Host-confusion shapes a bare string comparison would let through.
        assertFails { community.copy(serverName = "evil.example/api.steampowered.com") }
        assertFails { community.copy(serverName = "api.steampowered.com@evil.example") }
        assertFails { community.copy(serverName = "") }
    }

    @Test
    fun a_proven_read_rejects_a_definition_that_could_not_bind() {
        val ok = NotaryConfig().offerRead
        // No token slot ⇒ an unauthenticated read: Steam 401 inside MPC, opaque proof failure, every proof.
        assertFails { ok.copy(pathTemplate = "/IEconService/GetTradeOffer/v1/?tradeofferid={offerId}") }
        // Nothing disclosed ⇒ a well-formed attestation binding no trade, which a verifier cannot detect.
        assertFails { ok.copy(revealJsonPaths = emptyList()) }
        // An absolute or scheme-relative path would move the read off the allow-listed host.
        assertFails { ok.copy(pathTemplate = "https://evil.example/x?k={token}") }
    }

    @Test
    fun steam_write_limits_reject_nonsense() {
        assertFails { SteamWriteConfig(maxCreatesPerPartnerPerCycle = 0) }
        assertFails { SteamWriteConfig(maxCreatesPerCycle = 0) }
        assertFails { SteamWriteConfig(maxConcurrentChains = 0) }
        assertFails { SteamWriteConfig(globalBreakerThreshold = 0) }
        assertFails { SteamWriteConfig(cooldownBaseMs = 0) }
        assertFails { SteamWriteConfig(cooldownMinMs = -1) }
        // A cap below the floor would make the floor unreachable.
        assertFails { SteamWriteConfig(cooldownMinMs = 60_000, cooldownMaxMs = 30_000) }
    }

    @Test
    fun steam_write_failure_markers_cover_the_refusals_seen_in_the_wild() {
        val steamWrites = defaults.steamWrites
        assertTrue(steamWrites.rateLimitMarkers.any { "too many outstanding trade offers".contains(it) })
        assertTrue(steamWrites.transportFailureMarkers.any { "failed to fetch".contains(it) })
        // The verbatim refusal Steam sends for a per-counterparty cap must contain the discriminator, or the
        // split between the two offer limits collapses and one partner's cap parks the whole surface.
        assertTrue(
            "have too many outstanding trade offers with luckydm07. please cancel some"
                .contains(steamWrites.counterpartyLimitMarker),
        )
        // Every request-rate marker must be one the rate-limit set already matches; one that is not is dead
        // config (the refinement is only consulted for a text already read as a rate limit).
        assertTrue(
            steamWrites.requestRateLimitMarkers.all { refinement ->
                steamWrites.rateLimitMarkers.any { it == refinement }
            },
        )
    }

    @Test
    fun cadence_floors_match_the_per_surface_baseline() {
        val c = defaults.cadence
        assertEquals(60.seconds, c.pollFloor(RuntimeSurface.WebChrome, TrackerMode.Foreground))
        assertEquals(30.seconds, c.pollFloor(RuntimeSurface.IosNative, TrackerMode.Foreground))
        assertEquals(15.minutes, c.pollFloor(RuntimeSurface.IosNative, TrackerMode.Background))
        assertEquals(90.seconds, c.heartbeatFloor(RuntimeSurface.AndroidNative, TrackerMode.Foreground))
        assertEquals(15.minutes, c.heartbeatFloor(RuntimeSurface.AndroidNative, TrackerMode.Background))
    }

    @Test
    fun overriding_active_offer_interval_flows_through_cadence_policy() {
        val tuned = CadencePolicy(defaults.cadence.copy(activeOfferIntervalMs = 5 * 60_000))
        assertEquals(
            5.minutes,
            tuned.nextPollDelay(RuntimeSurface.WebChrome, TrackerMode.Foreground, PollClass.ActiveOffer),
        )
    }

    @Test
    fun a_remote_config_is_a_plain_value_so_copy_produces_an_independent_override() {
        val tuned = defaults.copy(http = HttpConfig(requestTimeoutMs = 5_000))
        assertEquals(5_000, tuned.http.requestTimeoutMs)
        assertEquals(30_000, defaults.http.requestTimeoutMs) // original untouched
        assertTrue(tuned != defaults)
    }
}
