package com.dmarket.p2p.tracker.net

import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SteamProxyTest {
    private val url = "http://trader:s3cret-pass@203.0.113.7:3128"

    @Test
    fun a_proxy_url_reads_into_host_port_and_credentials() {
        val cases = listOf(
            Triple(url, "203.0.113.7" to 3128, "trader:s3cret-pass"),
            Triple("HTTP://Trader:pa%40ss%3Aword@proxy.example.net:8080/", "proxy.example.net" to 8080, "Trader:pa@ss:word"),
            Triple("http://user:@[2001:db8::1]:443", "[2001:db8::1]" to 443, "user:"),
            Triple("http://user@proxy-1.example.net:1", "proxy-1.example.net" to 1, "user:"),
            Triple("http://10.0.0.1:65535", "10.0.0.1" to 65_535, null),
        )
        for ((text, address, credentials) in cases) {
            val proxy = SteamProxy.parse(text)
            assertEquals(address, proxy.host to proxy.port, text)
            assertEquals(credentials?.let { "Basic " + Base64.encode(it.encodeToByteArray()) }, proxy.authorization(), text)
            assertEquals(text, proxy.url())
        }
    }

    @Test
    fun a_refusal_names_the_rule_and_never_the_value() {
        val refused = mapOf(
            "https://trader:s3cret-pass@203.0.113.7:3128" to "http:// URL",
            "socks5://trader:s3cret-pass@203.0.113.7:1080" to "SOCKS is not supported",
            "trader:s3cret-pass@203.0.113.7:3128" to "http:// URL",
            "http://trader:s3cret-pass@203.0.113.7" to "host and a port",
            "http://trader:s3cret-pass@203.0.113.7:0" to "port must be",
            "http://trader:s3cret-pass@203.0.113.7:70000" to "port must be",
            "http://trader:s3cret-pass@203.0.113.7:3128/path" to "no path",
            "http://trader:s3cret-pass@203.0.113.7:3128?x=1" to "no path",
            "http://trader:s3cret pass@203.0.113.7:3128" to "spaces",
            "http://trader:s3cret-pass@bad_host!:3128" to "host name or an IP",
            "http://:s3cret-pass@203.0.113.7:3128" to "user must not be empty",
            "http://tra%3Ader:s3cret-pass@203.0.113.7:3128" to "must not contain a colon",
            "http://trader:s3cret%4@203.0.113.7:3128" to "percent escape",
            "http://trader:s3cret%+1@203.0.113.7:3128" to "percent escape",
            "http://trader:s3cret%-1@203.0.113.7:3128" to "percent escape",
            "http://trader:s3cret%FF@203.0.113.7:3128" to "percent escape",
            "http://trader:s3cret%C3@203.0.113.7:3128" to "percent escape",
            "http://trader:s3cret-pass@203.0.113.7:3128\r\nX: y" to "control characters",
        )
        for ((text, rule) in refused) {
            val message = assertFailsWith<IllegalArgumentException>(text) { SteamProxy.parse(text) }.message.orEmpty()
            assertTrue(rule in message, "$text: $message")
            for (part in listOf("s3cret", "trader", "203.0.113.7", "3128", "bad_host")) assertFalse(part in message, "$text: $message")
        }
    }

    @Test
    fun nothing_prints_it_and_redact_takes_every_part_of_it_out() {
        val proxy = SteamProxy.parse("http://trader:s3cret%2Fpass@203.0.113.7:3128")
        assertEquals("SteamProxy(<redacted>)", proxy.toString())
        val text = "dial http://trader:s3cret%2Fpass@203.0.113.7:3128 failed: connect ECONNREFUSED 203.0.113.7:3128 " +
            "as trader with s3cret/pass (s3cret%2Fpass) at 203.0.113.7"
        val redacted = proxy.redact(text)
        for (part in listOf("trader", "s3cret", "203.0.113.7", "%2F")) assertFalse(part in redacted, redacted)
        assertTrue("ECONNREFUSED" in redacted && "<proxy>" in redacted, redacted)
        val basic = proxy.authorization().orEmpty().removePrefix("Basic ")
        assertFalse(basic in proxy.redact("Proxy-Authorization: Basic $basic"), "the header's credential too")
    }

    @Test
    fun a_character_outside_the_bmp_beside_a_percent_escape_reads_as_itself() {
        val emoji = "\uD83D\uDE00"
        val proxy = SteamProxy.parse("http://us${emoji}er:%40$emoji%3A$emoji@203.0.113.7:3128")
        val expected = "Basic " + Base64.encode("us${emoji}er:@$emoji:$emoji".encodeToByteArray())
        assertEquals(expected, proxy.authorization())
    }

    @Test
    fun a_bare_user_or_password_is_redacted_as_a_token_and_never_inside_a_host_name() {
        val kept = "connect steamcommunity.com:443 then store.steampowered.com:443 and api.steampowered.com"
        val tokens = listOf("as steam.", "user=steam", "\"steam\"", "(steam)", "steam:pw", "steam@x", "pw:steam", "[steam]")
        val cases = mapOf(
            "http://steam:store@203.0.113.7:3128" to listOf("steam", "store"),
            "http://store:steam@203.0.113.7:3128" to listOf("steam", "store"),
            "http://steam@203.0.113.7:3128" to listOf("steam"),
        )
        for ((url, secrets) in cases) {
            val proxy = SteamProxy.parse(url)
            assertEquals(kept, proxy.redact(kept), url)
            for (text in tokens + tokens.map { it.replace("steam", "store") }) {
                val redacted = proxy.redact(text)
                for (secret in secrets) assertFalse(secret in redacted, "$url: $text -> $redacted")
            }
        }
        val proxy = SteamProxy.parse("http://steam:st%40re@203.0.113.7:3128")
        val text = "log in steamcommunity.com as steam with st@re (st%40re), header steam:st@re@ and steam:st%40re@x"
        val redacted = proxy.redact(text)
        assertTrue("steamcommunity.com" in redacted, redacted)
        assertFalse("st@re" in redacted || "st%40re" in redacted || " steam " in redacted || "steam:" in redacted, redacted)
    }

    @Test
    fun a_bare_credential_beside_a_percent_escape_is_still_redacted() {
        val proxy = SteamProxy.parse("http://steam:secret@203.0.113.7:3128")
        val redacted = proxy.redact("target=http%3A%2F%2Fsteam%3Asecret%40203.0.113.7%3A3128 via %2Fsteamcommunity.com")
        assertFalse("steam%3A" in redacted || "secret" in redacted, redacted)
        assertTrue("%2Fsteamcommunity.com" in redacted, redacted)
        assertEquals("crab@example.net", SteamProxy.parse("http://ab:cd@203.0.113.7:3128").redact("crab@example.net"))
    }

    @Test
    fun a_bare_host_is_redacted_as_a_token_and_never_inside_a_longer_name() {
        val proxy = SteamProxy.parse("http://u:p@proxy:3128")
        for (text in listOf("connect to proxy:3128", "http://proxy/", "host=proxy", "via proxy.")) {
            assertFalse("proxy" in proxy.redact(text).replace("<proxy>", ""), "$text -> ${proxy.redact(text)}")
        }
        val kept = "connect steamcommunity.com:443"
        assertEquals(kept, SteamProxy.parse("http://u:p@steam:3128").redact(kept))
        val ip = SteamProxy.parse("http://u:p@10.0.0.1:3128")
        assertEquals("from 110.0.0.12 via <proxy>", ip.redact("from 110.0.0.12 via 10.0.0.1"))
    }

    @Test
    fun two_proxies_are_equal_by_value() {
        assertEquals(SteamProxy.parse(url), SteamProxy.parse(url))
        assertEquals(SteamProxy.parse(url).hashCode(), SteamProxy.parse(url).hashCode())
        assertNotEquals(SteamProxy.parse(url), SteamProxy.parse("http://trader:other-pass@203.0.113.7:3128"))
        assertNotEquals(SteamProxy.parse(url), SteamProxy.parse("http://trader:s3cret-pass@203.0.113.8:3128"))
        assertNull(SteamProxy.parse("http://203.0.113.7:3128").authorization())
    }

    @Test
    fun a_url_user_info_is_redacted_from_any_body() {
        val body = "proxy http://trader:s3cret-pass@203.0.113.7:3128 and https://user@example.net/x and a@b.c"
        val redacted = NetworkRedaction.redactBody(body).orEmpty()
        assertFalse("s3cret" in redacted || "trader" in redacted || "user@" in redacted, redacted)
        assertTrue("http://<redacted>@203.0.113.7:3128" in redacted && "a@b.c" in redacted, redacted)
    }

    @Test
    fun a_raw_at_sign_in_the_password_is_redacted_with_the_rest_of_the_user_info() {
        val body = "proxy http://trader:p@ss@203.0.113.7:3128 then https://example.net/a@b?c=d@e"
        val redacted = NetworkRedaction.redactBody(body).orEmpty()
        assertFalse("trader" in redacted || "ss@" in redacted, redacted)
        assertTrue("http://<redacted>@203.0.113.7:3128" in redacted, redacted)
        assertTrue("https://example.net/a@b?c=d@e" in redacted, "an `@` in a path or query is not user info: $redacted")
    }
}
