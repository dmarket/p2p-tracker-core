package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Covers the formatting of the wasm's `ProveProgress` events.
 *
 * Worth its own tests despite being one string template: the events are produced by the **vendored** prover
 * (`vendor/tlsn/VERSION` pins the artifact, not the event shape), the formatter is invoked BY the wasm
 * mid-issuance, and anything it throws unwinds through a wasm frame — turning a renamed field into a trapped
 * prover instance rather than a missing log line. So the degradation paths are the point, not the happy one.
 */
class WasmProverProgressTest {

    @Test
    fun a_full_event_renders_the_stage_its_percentage_and_its_message() {
        val event = js("({ step: 'mpc_setup', progress: 0.25, message: 'Setting up MPC', source: 'wasm' })")
        // `source` is deliberately absent from the line: it is always "wasm" on this path.
        assertEquals("stage mpc_setup 25% — Setting up MPC", progressLine(event))
    }

    @Test
    fun a_renamed_or_dropped_field_degrades_instead_of_throwing() {
        assertEquals("stage (unnamed stage)", progressLine(js("({})")))
        assertEquals("stage reveal", progressLine(js("({ step: 'reveal' })")))
        // A shape change that swaps the types rather than the names — reading `progress` as a percentage here
        // would print "NaN%" at best, and `roundToInt` throws on NaN.
        assertEquals("stage (unnamed stage)", progressLine(js("({ step: 7, progress: 'half', message: 3 })")))
    }

    @Test
    fun a_non_finite_percentage_is_omitted_rather_than_rounded() {
        // `Double.roundToInt()` throws IllegalArgumentException on NaN, and the caller is a wasm-invoked
        // callback — so this one is a trap, not a cosmetic defect.
        assertEquals("stage finalized — done", progressLine(js("({ step: 'finalized', progress: NaN, message: 'done' })")))
        assertEquals("stage finalized", progressLine(js("({ step: 'finalized', progress: Infinity })")))
    }

    @Test
    fun a_blank_message_does_not_leave_a_dangling_separator() {
        assertEquals("stage reveal 100%", progressLine(js("({ step: 'reveal', progress: 1, message: '   ' })")))
    }

    // ---- issuanceLine -------------------------------------------------------------------------------

    private val steamToken = "eyJhbGciOiJIUzI1NiJ9.the-steam-jwt-nobody-may-log.sig"
    private val notaryToken = "the-dmarket-bearer-nobody-may-log"
    private val pem = "-----BEGIN CERTIFICATE-----\nMIIBfixtureCA\n-----END CERTIFICATE-----\n"

    private fun request(rootStorePem: String? = null) = WasmProveRequest(
        notaryUrl = "wss://notary.test/",
        notaryToken = notaryToken,
        proxyBaseUrl = "wss://proxy.test",
        spec = SteamProofReadMapper(NotaryConfig()).readSpec(
            source = TradeStatusSource.OFFER,
            binding = ProvenReadBinding(
                dealId = DealId("d1"),
                steamOfferId = OfferId("off-1"),
                tradeId = TradeId("trade-1"),
            ),
            subjectSteamId = SteamId("76561198000000001"),
            adapter = Cs2GameAdapter(),
        ),
        steamAccessToken = steamToken,
        maxSentData = 1_024,
        maxRecvData = 16_384,
        maxRecvDataOnline = 512,
        rootStorePem = rootStorePem,
    )

    @Test
    fun the_issuance_line_carries_neither_credential() {
        // The reason this line exists at all is a config-suspect failure, so it names the whole issuance —
        // which is exactly how a credential gets into a log. Both are in scope on this path: the Steam JWT
        // (substituted into the path's {token} slot only at the IO edge) and the notary bearer.
        for (line in listOf(issuanceLine(request()), issuanceLine(request(pem)))) {
            assertFalse(line.contains(steamToken), "the Steam JWT must never be logged: $line")
            assertFalse(line.contains(notaryToken), "the notary token must never be logged: $line")
            // The PATH TEMPLATE, still holding its placeholder — a template with no slot is a read that would
            // go unauthenticated, which is the fact worth being able to see.
            assertContains(line, TOKEN_PLACEHOLDER)
            // And stated as a flag as well, because a host's log scrubber redacts `access_token=…` values and
            // takes the placeholder with them — leaving the path unable to answer the question it was for.
            assertContains(line, "tokenSlot=present")
        }
    }

    @Test
    fun the_root_store_is_reported_as_the_wasm_sees_it_and_never_as_bytes() {
        // `mozilla` when absent, because a PEM REPLACES that set rather than extending it — the difference
        // between "no fixture CA" and "fixture CA only" is the whole verdict on an UnknownIssuer failure.
        assertContains(issuanceLine(request()), "rootStore=mozilla")
        val withPem = issuanceLine(request(pem))
        assertContains(withPem, "rootStore=pem(${pem.length} chars)")
        assertFalse(withPem.contains("BEGIN CERTIFICATE"), "a multi-kB PEM must not be pasted into every proof")
    }

    @Test
    fun the_issuance_line_names_the_target_and_the_pipe_that_reaches_it() {
        // Both halves, because neither is a verdict alone: a fixture root against the production proxy fails
        // exactly like no root against a fixture target.
        val line = issuanceLine(request(pem))
        assertContains(line, "serverName=api.steampowered.com")
        assertContains(line, "proxy=wss://proxy.test")
        assertContains(line, "maxSent=1024")
    }
}
