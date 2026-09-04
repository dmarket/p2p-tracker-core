package com.dmarket.p2p.tracker.model

import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.Directive
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.model.marketplace.Money
import com.dmarket.p2p.tracker.model.marketplace.P2PDealState
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamSessionCookie
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.wire.DealDto
import com.dmarket.p2p.tracker.wire.DirectiveDto
import com.dmarket.p2p.tracker.wire.MoneyDto
import com.dmarket.p2p.tracker.wire.TrackerJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Every credential-bearing type in `:domain` must keep its secret out of `toString()`.
 *
 * `toString` is the one place a secret leaks by *omission* — nobody writes `log(token)`, but a data
 * class interpolated into an exception message, a test failure or a debug line prints every field it
 * has. The types here are exactly the ones that carry a live credential, so the redaction is asserted
 * rather than left to whoever adds the next field.
 */
class SecretToStringTest {

    private val now = Instant.parse("2026-01-01T00:00:00Z")

    // Deliberately distinctive: a substring assertion cannot pass by accident.
    private val secret = "SECRET-TOKEN-a1b2c3d4e5f6"

    @Test
    fun steam_credential_redacts_its_token() {
        val printed = SteamCredential(secret, SteamId("76561198000000001"), now).toString()
        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
        // The non-secret identity stays, which is what makes a redacted line still useful.
        assertTrue("76561198000000001" in printed, printed)
    }

    @Test
    fun marketplace_credential_redacts_its_bearer_token() {
        val printed = MarketplaceCredential(secret, now).toString()
        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
    }

    @Test
    fun steam_session_cookie_redacts_its_access_token() {
        val printed = SteamSessionCookie(SteamId("76561198000000001"), secret, now).toString()
        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
    }

    @Test
    fun web_cookie_redacts_its_value_because_the_value_is_the_credential() {
        // For the cookies this type carries (`steamLoginSecure`, `dm-trade-token`) the value IS the secret.
        val printed = WebCookie(secret, 1_800_000_000L).toString()
        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
        assertTrue("1800000000" in printed, "the non-secret expiry is still printed: $printed")
    }

    // ---- trade tokens ------------------------------------------------------------------------------
    // A trade token is a bearer capability: whoever holds it can send that Steam account an offer. These
    // types are interpolated on the create path, whose failure string is POSTed to DMarket, persisted, and
    // handed to the web page.

    @Test
    fun trade_draft_redacts_its_trade_token() {
        val printed = TradeDraft(SteamId("76561198000000001"), listOf(AssetId("a-1")), secret).toString()
        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
        assertTrue("a-1" in printed, "the non-secret asset stays: $printed")
    }

    @Test
    fun trade_draft_distinguishes_an_absent_token_from_a_redacted_one() {
        // "Was a token supplied at all" is the usual question, so null must not read as <redacted>.
        val printed = TradeDraft(SteamId("76561198000000001"), listOf(AssetId("a-1"))).toString()
        assertTrue("tradeToken=null" in printed, printed)
    }

    @Test
    fun directive_redacts_its_trade_token() {
        val printed = Directive(
            directiveId = DirectiveId("dir-1"),
            action = DirectiveAction.CREATE_OFFER,
            dealId = DealId("d-1"),
            tradeToken = secret,
        ).toString()
        assertFalse(secret in printed, printed)
        assertTrue("redacted" in printed, printed)
        assertTrue("dir-1" in printed, printed)
    }

    @Test
    fun directive_dto_redacts_its_trade_token_but_still_serializes_it() {
        val dto = DirectiveDto(directiveId = "dir-1", action = "create_offer", tradeToken = secret)
        assertFalse(secret in dto.toString(), dto.toString())
        assertTrue("redacted" in dto.toString())
        // The wire must be untouched — redaction is a logging concern, not a serialization one.
        assertTrue(secret in TrackerJson.encodeToString(dto), "redaction must not change the wire")
    }

    @Test
    fun deal_redacts_the_token_inside_its_trusted_accept_url() {
        val printed = aDeal("https://steamcommunity.com/tradeoffer/new/?partner=123&token=$secret").toString()
        assertFalse(secret in printed, printed)
        assertTrue("token=<redacted>" in printed, printed)
        // The link stays readable: host, path and the non-secret param survive.
        assertTrue("partner=123" in printed, printed)
        assertTrue("steamcommunity.com" in printed, printed)
    }

    @Test
    fun deal_dto_redacts_the_token_inside_its_trusted_accept_url_but_still_serializes_it() {
        val dto = DealDto(
            dealId = "d-1",
            state = "P2P_DEAL_STATE_COMMITTED",
            buyerAccountId = "b-1",
            sellerAccountId = "s-1",
            offerId = "o-1",
            assetId = "a-1",
            price = MoneyDto(currency = "USD", amount = "607"),
            trustedAcceptUri = "https://steamcommunity.com/tradeoffer/new/?partner=123&token=$secret",
            createTime = "2026-01-01T00:00:00Z",
            updateTime = "2026-01-01T00:00:00Z",
        )
        assertFalse(secret in dto.toString(), dto.toString())
        assertTrue("token=<redacted>" in dto.toString())
        assertTrue(secret in TrackerJson.encodeToString(dto), "redaction must not change the wire")
    }

    @Test
    fun deal_with_no_accept_uri_prints_null_not_a_redaction() {
        assertTrue("trustedAcceptUri=null" in aDeal(null).toString())
    }

    private fun aDeal(trustedAcceptUri: String?) = Deal(
        dealId = DealId("d-1"),
        state = P2PDealState.COMMITTED,
        buyerAccountId = AccountId("b-1"),
        sellerAccountId = AccountId("s-1"),
        offerId = OfferId("o-1"),
        assetId = AssetId("a-1"),
        price = Money(currencyCode = "USD", amountCents = 607),
        trustedAcceptUri = trustedAcceptUri,
        createdAt = now,
        updatedAt = now,
    )
}
