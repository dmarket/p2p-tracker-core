package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.model.AssetId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SteamWriteBodyTest {

    @Test
    fun the_create_template_sends_every_field_steams_endpoint_expects() {
        // Field-for-field against what `FetchSteamOfferCreator.postCreate` sends. The two spellings must agree:
        // a proven create that differs from the real create proves a request Steam would have answered
        // differently.
        val template = SteamWriteBody.createOfferFormTemplate()

        for (field in listOf("sessionid", "serverid", "partner", "tradeoffermessage", "json_tradeoffer", "trade_offer_create_params")) {
            assertTrue("$field=" in template, "create body must send $field: $template")
        }
        assertTrue("serverid=1" in template, template)
    }

    @Test
    fun the_create_template_leaves_exactly_the_slots_someone_else_fills() {
        val template = SteamWriteBody.createOfferFormTemplate()

        // The IO edge's slot — and the one value a proof discloses.
        assertTrue(SESSION_ID_PLACEHOLDER in template, template)
        // The mapper's slots, filled from the binding.
        assertTrue(PARTNER_STEAM_ID in template, template)
        assertTrue(SteamWriteBody.ASSETS_PLACEHOLDER in template, template)
        assertTrue(SteamWriteBody.CREATE_PARAMS_PLACEHOLDER in template, template)
        // No Steam token: this endpoint authenticates by cookie and has no use for the JWT.
        assertFalse(TOKEN_PLACEHOLDER in template, template)
    }

    @Test
    fun placeholders_survive_form_encoding() {
        // The whole reason encoding happens once, inside the builder: a placeholder whose braces got
        // percent-encoded is a slot nobody can find again, and the request would go out with a literal
        // `%7BsessionId%7D` where Steam expects a token.
        val template = SteamWriteBody.createOfferFormTemplate()

        // Each slot survives verbatim, braces included. Checked per placeholder rather than by hunting for
        // `%7B` anywhere: the JSON documents have braces of their own, and those SHOULD be encoded — an
        // over-broad assertion here would fail on exactly the behaviour we want.
        for (slot in listOf(
            SESSION_ID_PLACEHOLDER,
            PARTNER_STEAM_ID,
            SteamWriteBody.ASSETS_PLACEHOLDER,
            SteamWriteBody.CREATE_PARAMS_PLACEHOLDER,
        )) {
            assertTrue(slot in template, "$slot must survive encoding: $template")
            val encoded = slot.replace("{", "%7B").replace("}", "%7D")
            assertFalse(encoded in template, "$slot must not appear percent-encoded: $template")
        }
        // …while the JSON structure around them IS encoded.
        assertTrue("%22newversion%22" in template, "the JSON must be form-encoded: $template")
        assertTrue("%7B%22newversion%22" in template, "the JSON's own braces must be encoded: $template")
    }

    @Test
    fun the_asset_fragment_carries_appid_contextid_and_every_asset() {
        val fragment = SteamWriteBody.assetsFragment(listOf(AssetId("111"), AssetId("222")), appId = 730, contextId = 2)

        assertTrue("%22appid%22%3A730" in fragment, fragment)
        assertTrue("%22contextid%22%3A%222%22" in fragment, fragment)
        assertTrue("111" in fragment && "222" in fragment, "both assets must appear: $fragment")
        assertTrue("%2C" in fragment, "assets are comma-separated (encoded): $fragment")
    }

    @Test
    fun an_empty_asset_list_produces_an_empty_fragment() {
        // Which lands inside the template's `"assets":[…]`, yielding a valid empty array rather than malformed
        // JSON.
        assertEquals("", SteamWriteBody.assetsFragment(emptyList(), appId = 730, contextId = 2))
    }

    @Test
    fun create_params_carry_the_trade_token_only_when_there_is_one() {
        assertTrue("trade_offer_access_token" in SteamWriteBody.createParamsFragment("tok"))
        // An absent token must produce `{}`, not a field with an empty value — Steam treats the two differently
        // for a public-inventory trade.
        assertEquals("%7B%7D", SteamWriteBody.createParamsFragment(null))
    }

    @Test
    fun a_hostile_trade_token_cannot_escape_its_field() {
        // The trade token is Steam-supplied text landing in a form body. Anything outside the unreserved set is
        // escaped, so an `&` cannot start a field Steam never sent.
        val fragment = SteamWriteBody.createParamsFragment("a&b=c")

        assertFalse("&" in fragment, "an ampersand would split the body: $fragment")
        assertTrue("%26" in fragment, fragment)
    }

    @Test
    fun the_cancel_body_is_the_session_id_and_nothing_else() {
        // The offer being cancelled is named by the path, which is why a cookie-authed write discloses its
        // request target — that disclosure is the binding, so the body needs nothing more.
        assertEquals("sessionid=$SESSION_ID_PLACEHOLDER", SteamWriteBody.cancelOfferFormTemplate())
    }
}
