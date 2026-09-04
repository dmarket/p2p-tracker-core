package com.dmarket.p2p.tracker.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkRedactionTest {
    private val jwt = "eyJ.HEADER.PAYLOAD.SIGNATURE"

    /**
     * A realistically **shaped** token (three base64url segments), for the shape-keyed [NetworkRedaction
     * .JWT_SHAPE] cases. [jwt] above is deliberately not JWT-shaped, so the name-keyed assertions keep
     * asserting the name-keyed patterns rather than accidentally passing via the shape rule.
     */
    private val realJwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI3NjU2MTE5ODAwMDAwMDAwMSJ9.c2lnbmF0dXJlLXZhbHVl"

    // ---- redactUrl ---------------------------------------------------------------------------------

    @Test
    fun url_without_query_is_unchanged() {
        val url = "https://api.steampowered.com/IEconService/GetTradeOffers/v1/"
        assertEquals(url, NetworkRedaction.redactUrl(url))
    }

    @Test
    fun access_token_query_param_is_redacted() {
        val redacted = NetworkRedaction.redactUrl("https://api.steampowered.com/x?access_token=$jwt&get_sent_offers=1")
        assertEquals("https://api.steampowered.com/x?access_token=<redacted>&get_sent_offers=1", redacted)
        assertFalse(jwt in redacted)
    }

    @Test
    fun secret_param_mid_query_is_redacted_and_others_preserved() {
        val redacted = NetworkRedaction.redactUrl("https://h/p?a=1&token=$jwt&b=2")
        assertEquals("https://h/p?a=1&token=<redacted>&b=2", redacted)
    }

    @Test
    fun multiple_secret_params_are_all_redacted() {
        val redacted = NetworkRedaction.redactUrl("https://h/p?access_token=$jwt&sessionid=abc123")
        assertEquals("https://h/p?access_token=<redacted>&sessionid=<redacted>", redacted)
    }

    @Test
    fun non_secret_params_are_left_intact() {
        val url = "https://h/p?get_sent_offers=1&active_only=true"
        assertEquals(url, NetworkRedaction.redactUrl(url))
    }

    @Test
    fun param_name_match_is_case_insensitive() {
        val redacted = NetworkRedaction.redactUrl("https://h/p?Access_Token=$jwt")
        assertEquals("https://h/p?Access_Token=<redacted>", redacted)
    }

    @Test
    fun fragment_is_preserved() {
        val redacted = NetworkRedaction.redactUrl("https://h/p?access_token=$jwt#frag")
        assertEquals("https://h/p?access_token=<redacted>#frag", redacted)
    }

    @Test
    fun config_supplied_param_name_is_honoured() {
        val redacted = NetworkRedaction.redactUrl("https://h/p?steam_jwt=$jwt", setOf("steam_jwt"))
        assertEquals("https://h/p?steam_jwt=<redacted>", redacted)
    }

    // ---- redactHeaders -----------------------------------------------------------------------------

    @Test
    fun credential_headers_are_redacted_case_insensitively_others_pass_through() {
        val headers = mapOf(
            "Authorization" to "Bearer $jwt",
            "cookie" to "steamLoginSecure=$jwt; sessionid=abc",
            "Set-Cookie" to "dm-trade-token=$jwt",
            "Content-Type" to "application/json",
            "Accept" to "text/html",
        )
        val redacted = NetworkRedaction.redactHeaders(headers)
        assertEquals("<redacted>", redacted["Authorization"])
        assertEquals("<redacted>", redacted["cookie"])
        assertEquals("<redacted>", redacted["Set-Cookie"])
        assertEquals("application/json", redacted["Content-Type"])
        assertEquals("text/html", redacted["Accept"])
        assertFalse(redacted.values.any { jwt in it })
    }

    // ---- redactBody --------------------------------------------------------------------------------

    @Test
    fun null_body_stays_null() {
        assertEquals(null, NetworkRedaction.redactBody(null))
    }

    @Test
    fun form_encoded_session_id_is_redacted() {
        val redacted = NetworkRedaction.redactBody("sessionid=$jwt&serverid=1&partner=76561198000000001")
        assertTrue("sessionid=<redacted>" in redacted!!)
        assertTrue("partner=76561198000000001" in redacted)
        assertFalse(jwt in redacted)
    }

    @Test
    fun settoken_form_body_nonce_and_auth_are_redacted() {
        // The settoken re-mint POST body carries the two refresh secrets — both must be scrubbed.
        val redacted = NetworkRedaction.redactBody("steamID=76561198000000001&nonce=$jwt&auth=$jwt&sessionid=abc")
        assertTrue("nonce=<redacted>" in redacted!!)
        assertTrue("auth=<redacted>" in redacted)
        assertTrue("sessionid=<redacted>" in redacted)
        assertTrue("steamID=76561198000000001" in redacted) // steamID is not a secret
        assertFalse(jwt in redacted)
    }

    @Test
    fun json_token_field_is_redacted() {
        val redacted = NetworkRedaction.redactBody("""{"trade_offer_access_token":"$jwt","partner":"x"}""")
        assertFalse(jwt in redacted!!)
        assertTrue("\"partner\":\"x\"" in redacted)
    }

    @Test
    fun json_access_token_in_response_body_is_redacted_others_preserved() {
        val redacted = NetworkRedaction.redactBody("""{"access_token":"$jwt","ok":true,"strError":"nope"}""")
        assertFalse(jwt in redacted!!)
        assertTrue("\"access_token\":\"<redacted>\"" in redacted)
        assertTrue("\"ok\":true" in redacted) // non-secret fields survive so the error stays readable
        assertTrue("\"strError\":\"nope\"" in redacted)
    }

    @Test
    fun percent_encoded_token_in_trade_offer_create_params_is_redacted() {
        // Steam's create body carries the buyer token percent-encoded inside trade_offer_create_params
        // (URLSearchParams.toString()): %22trade_offer_access_token%22%3A%22<token>%22. The plain
        // form/JSON regexes miss it; the percent-encoded pattern must catch it.
        val body =
            "sessionid=abc&trade_offer_create_params=%7B%22trade_offer_access_token%22%3A%22$jwt%22%7D&serverid=1"
        val redacted = NetworkRedaction.redactBody(body)!!
        assertFalse(jwt in redacted)
        assertTrue("%22trade_offer_access_token%22%3A%22<redacted>%22" in redacted)
        assertTrue("sessionid=<redacted>" in redacted, "plain form secrets still redacted alongside")
    }

    @Test
    fun steam_html_token_attribute_is_redacted() {
        val redacted = NetworkRedaction.redactBody("""<div data-loyalty_webapi_token="&quot;$jwt&quot;"></div>""")
        assertFalse(jwt in redacted!!)
        assertTrue("data-loyalty_webapi_token=<redacted>" in redacted)
    }

    @Test
    fun long_body_is_truncated() {
        val body = "x".repeat(5000)
        val redacted = NetworkRedaction.redactBody(body, maxLen = 100)
        assertTrue(redacted!!.length < 200)
        assertTrue(redacted.endsWith("…[truncated]"))
    }

    // ---- redactBody: shape-keyed JWT backstop -------------------------------------------------------
    // A token is regularly echoed with no recognisable key in front of it. Each body below is one the
    // name-keyed patterns cannot match, in a shape the Steam / DMarket axes really produce.

    @Test
    fun bearer_prefixed_jwt_with_no_key_name_is_redacted() {
        val redacted = NetworkRedaction.redactBody("""{"detail":"token rejected: Bearer $realJwt"}""")!!
        assertFalse(realJwt in redacted)
        assertTrue("token rejected: Bearer <redacted>" in redacted, "surrounding prose must survive")
    }

    @Test
    fun jwt_inside_escaped_json_is_redacted() {
        // Steam's `strError` value is escaped JSON, so the plain `"nonce":"…"` pattern never matches it.
        val redacted = NetworkRedaction.redactBody("""{"strError":"{\"nonce\":\"$realJwt\"}"}""")!!
        assertFalse(realJwt in redacted)
        assertTrue("strError" in redacted)
    }

    @Test
    fun single_quoted_jwt_is_redacted() {
        val redacted = NetworkRedaction.redactBody("""{'access_token': '$realJwt'}""")!!
        assertFalse(realJwt in redacted)
    }

    @Test
    fun jwt_in_a_url_path_is_redacted_by_shape() {
        // redactUrl covers the query only; the shape rule is what catches a path-borne token.
        val redacted = NetworkRedaction.redactBody("GET https://api.steampowered.com/v1/token/$realJwt/offers")!!
        assertFalse(realJwt in redacted)
        assertTrue("api.steampowered.com" in redacted)
    }

    @Test
    fun prior_and_trade_token_names_are_redacted() {
        val redacted = NetworkRedaction.redactBody("""{"tradeToken":"$jwt","prior":"$jwt","dealId":"d-1"}""")!!
        assertFalse(jwt in redacted)
        assertTrue("\"dealId\":\"d-1\"" in redacted)
    }

    @Test
    fun a_jwt_cannot_survive_truncation_because_scrubbing_runs_first() {
        // maxLen lands inside the token; scrubbing must already have replaced it.
        val redacted = NetworkRedaction.redactBody("prefix ".repeat(20) + realJwt, maxLen = 150)!!
        assertFalse("eyJ" in redacted)
    }

    @Test
    fun an_ordinary_body_is_not_mangled_by_the_shape_rule() {
        val body = """{"ok":true,"ttlSeconds":60,"note":"eyJ is not a token here"}"""
        assertEquals(body, NetworkRedaction.redactBody(body))
    }

    // ---- plusSecretParam ---------------------------------------------------------------------------

    @Test
    fun plus_secret_param_adds_a_renamed_param_and_keeps_the_defaults() {
        val set = NetworkRedaction.plusSecretParam("steam_tok")
        assertTrue("steam_tok" in set)
        assertTrue(NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES.all { it in set })
        assertEquals("https://h/p?steam_tok=<redacted>", NetworkRedaction.redactUrl("https://h/p?steam_tok=$jwt", set))
    }

    @Test
    fun plus_secret_param_ignores_a_blank_name() {
        assertEquals(NetworkRedaction.DEFAULT_SECRET_PARAM_NAMES, NetworkRedaction.plusSecretParam("  "))
    }
}
