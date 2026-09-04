package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.config.ProvenRead
import com.dmarket.p2p.tracker.config.ProvenReadAuth
import com.dmarket.p2p.tracker.net.SteamHosts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProvenReadCatalogTest {

    private val config = NotaryConfig()

    @Test
    fun every_kind_has_a_definition() {
        // THE test the catalog exists for: adding a ProvenReadKind without defining its read used to be
        // impossible to notice until a live proof asked for it. Iterating `entries` rather than a literal list
        // is the point — a new enum case fails here, in `:domain`, with no network involved.
        for (kind in ProvenReadKind.entries) {
            assertNotNull(config.provenRead(kind), "no ProvenRead defined for $kind")
        }
    }

    @Test
    fun the_two_named_config_fields_still_own_their_kinds() {
        // A host may already be overriding offerRead/historyRead — they are positional @JsExport constructor
        // slots that predate the catalog — so resolution must prefer them over any catalog entry.
        val custom = NotaryConfig(
            offerRead = ProvenRead(
                serverName = "api.steampowered.com",
                pathTemplate = "/IEconService/GetTradeOffer/v1/?tradeofferid={offerId}&access_token=$TOKEN_PLACEHOLDER",
                revealJsonPaths = listOf("response.offer.custom"),
            ),
        )
        assertEquals(listOf("response.offer.custom"), custom.provenRead(ProvenReadKind.TRADE_OFFER).revealJsonPaths)
    }

    @Test
    fun token_authed_reads_carry_the_token_slot_and_cookie_authed_ones_never_do() {
        for (kind in ProvenReadKind.entries) {
            val read = config.provenRead(kind)
            when (read.auth) {
                // No slot means the read goes out unauthenticated, Steam answers 401 inside MPC, and it
                // surfaces as an opaque failure for every proof of that kind.
                ProvenReadAuth.TOKEN_QUERY -> assertTrue(
                    TOKEN_PLACEHOLDER in read.pathTemplate,
                    "$kind is token-authed but has no $TOKEN_PLACEHOLDER: ${read.pathTemplate}",
                )
                // The mirror image, and it is about blast radius: the community host has no use for the Steam
                // JWT, so carrying it there would widen where a device-only credential travels for nothing.
                ProvenReadAuth.SESSION_COOKIE -> {
                    assertTrue(
                        TOKEN_PLACEHOLDER !in read.pathTemplate,
                        "$kind is cookie-authed but carries $TOKEN_PLACEHOLDER: ${read.pathTemplate}",
                    )
                    assertTrue(read.bodyTemplate?.contains(TOKEN_PLACEHOLDER) != true, "$kind body carries $TOKEN_PLACEHOLDER")
                }
            }
        }
    }

    @Test
    fun every_definition_keeps_its_request_on_an_allowed_steam_host() {
        // The composed URL, not just the host: a path can move the effective host
        // (`"@evil.example.com/"` turns the checked host into userinfo), and these requests carry device-only
        // credentials.
        for (kind in ProvenReadKind.entries) {
            val read = config.provenRead(kind)
            val allowed = when (read.auth) {
                ProvenReadAuth.TOKEN_QUERY -> SteamHosts.API
                ProvenReadAuth.SESSION_COOKIE -> SteamHosts.WEB
            }
            assertTrue(
                SteamHosts.isAllowed("https://${read.serverName}${read.pathTemplate}", allowed),
                "$kind resolves off its permitted hosts: ${read.serverName}${read.pathTemplate}",
            )
        }
    }

    /**
     * A valid proven write, with each field the tests below vary defaulted. Keeps the varied field the only
     * thing visible per test instead of a seven-argument constructor repeated three times.
     */
    private fun write(
        path: String = "/tradeoffer/new/send",
        body: String? = "sessionid=$SESSION_ID_PLACEHOLDER",
        acknowledged: Boolean = true,
    ) = ProvenRead(
        serverName = "steamcommunity.com",
        pathTemplate = path,
        revealJsonPaths = listOf("tradeofferid"),
        auth = ProvenReadAuth.SESSION_COOKIE,
        method = "POST",
        bodyTemplate = body,
        acknowledgeRequestBodyDisclosure = acknowledged,
    )

    @Test
    fun a_write_must_acknowledge_that_its_request_body_is_disclosed() {
        // The prover's RevealPolicy has no request-body field, so a proven write publishes its form body —
        // sessionid included. The compiler cannot force that decision, so the constructor does.
        val failure = assertFailsWith<IllegalArgumentException> { write(acknowledged = false) }
        assertTrue("acknowledgeRequestBodyDisclosure" in (failure.message ?: ""), failure.message ?: "")
    }

    @Test
    fun a_write_body_without_a_session_id_slot_is_rejected() {
        // Steam's community endpoints reject a body with no `sessionid`, and the failure inside MPC is opaque.
        assertFailsWith<IllegalArgumentException> { write(body = "serverid=1") }
        assertFailsWith<IllegalArgumentException> { write(body = null) }
    }

    @Test
    fun no_write_path_other_than_create_and_cancel_can_be_expressed() {
        // Hard-rule enforcement, structurally. Everywhere else in this codebase a write actual builds exactly
        // one fixed URL, so no confirm/mobileconf endpoint is reachable through it. The prover is the second
        // place a write URL can be built, so it is bounded the same way rather than trusted.
        for (path in listOf("/tradeoffer/{offerId}/accept", "/mobileconf/conf", "/tradeoffer/{offerId}/confirm")) {
            assertFailsWith<IllegalArgumentException>("write path '$path' must be rejected") { write(path = path) }
        }
        // …and the two that are permitted still construct.
        write(path = "/tradeoffer/new/send")
        write(path = "/tradeoffer/$OFFER_ID/cancel")
    }

    @Test
    fun the_default_enabled_set_is_exactly_the_two_axes_that_were_provable_before() {
        // Behaviour-neutrality, asserted rather than asserted-in-prose: widening the catalog must not widen
        // what a stock build proves.
        assertEquals(setOf(ProvenReadKind.TRADE_OFFER, ProvenReadKind.TRADE_STATUS), config.enabledReads)
    }

    @Test
    fun a_host_override_replaces_a_catalog_definition() {
        // The escape hatch that lets `reads` be an empty map by default: a host that needs a different template
        // for one endpoint supplies just that one, and everything else still resolves from the catalog.
        val mine = ProvenRead(
            serverName = "api.steampowered.com",
            pathTemplate = "/IEconService/GetTradeOffers/v1/?mine=1&access_token=$TOKEN_PLACEHOLDER",
            revealJsonPaths = listOf("response"),
        )
        val config = NotaryConfig(reads = ProvenReadRegistry(overrides = mapOf(ProvenReadKind.TRADE_OFFERS to mine)))

        assertEquals(mine, config.provenRead(ProvenReadKind.TRADE_OFFERS))
        // Untouched kinds keep the catalog's definition.
        assertTrue("GetTradeHistory" in config.provenRead(ProvenReadKind.TRADE_HISTORY).pathTemplate)
    }

    @Test
    fun the_narrowed_reads_pin_a_single_addressable_row() {
        // `max_trades=1` and `count=1` are load-bearing, not savings: the reveal-path syntax has no filters, so
        // a row index is only meaningful when the response holds one row. A future edit that "optimises" these
        // back to a page size would silently start proving the wrong row.
        assertTrue("max_trades=1" in config.provenRead(ProvenReadKind.TRADE_HISTORY).pathTemplate)
        assertTrue("count=1" in config.provenRead(ProvenReadKind.OWN_INVENTORY).pathTemplate)
    }
}
