package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.port.TransientSessionException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Covers the two HTTP primitives of [FetchSteamWebSessionGateway] (the `ajaxrefresh` GET and the
 * `settoken` POST) over a Ktor [MockEngine]. The `chrome.cookies` halves (readCookie /
 * writeSessionCookie) need the extension cookie API and are exercised via the live smoke test.
 */
class FetchSteamWebSessionGatewayTest {

    private fun gateway(engine: MockEngine): FetchSteamWebSessionGateway =
        FetchSteamWebSessionGateway(httpClient = createHttpClient(engine))

    @Test
    fun get_with_session_returns_body_on_2xx() = runTest {
        val engine = MockEngine { respond(content = "the-nonce-body") }
        val body = gateway(engine).getWithSession("https://login.steampowered.com/jwt/ajaxrefresh")
        assertEquals("the-nonce-body", body)
    }

    @Test
    fun get_with_session_throws_transient_on_non_ok() = runTest {
        val engine = MockEngine { respond(content = "boom", status = HttpStatusCode.InternalServerError) }
        assertFailsWith<TransientSessionException> {
            gateway(engine).getWithSession("https://login.steampowered.com/jwt/ajaxrefresh")
        }
    }

    @Test
    fun get_with_session_throws_transient_on_transport_error() = runTest {
        val engine = MockEngine { throw RuntimeException("offline") }
        assertFailsWith<TransientSessionException> {
            gateway(engine).getWithSession("https://login.steampowered.com/jwt/ajaxrefresh")
        }
    }

    @Test
    fun post_form_sends_the_form_and_returns_the_body() = runTest {
        var sentBody: String? = null
        val engine = MockEngine { request ->
            sentBody = (request.body as FormDataContent).bytes().decodeToString()
            respond(content = """{"result":1,"token":"fresh"}""", status = HttpStatusCode.OK)
        }

        val response = gateway(engine).postFormWithSession(
            "https://steamcommunity.com/login/settoken",
            mapOf("steamID" to "76561198000000001", "nonce" to "n", "auth" to "a", "sessionid" to "s"),
        )

        val body = sentBody ?: error("no request body captured")
        assertTrue(body.contains("steamID=76561198000000001"), "missing steamID in form: $body")
        assertTrue(body.contains("nonce=n"), "missing nonce in form: $body")
        // The reply is load-bearing: it says whether Steam accepted the transfer that sets the cookie.
        assertEquals("""{"result":1,"token":"fresh"}""", response)
    }

    @Test
    fun post_form_throws_transient_on_non_ok() = runTest {
        // Port contract, shared with getWithSession: a non-OK status is a blip to retry, NEVER a
        // logged-out session. Swallowing it (the old behaviour) made a Steam 403/429 indistinguishable
        // from "no session", which one layer up is a user-facing "sign into Steam" prompt.
        val engine = MockEngine { respond(content = "", status = HttpStatusCode.Forbidden) }

        assertFailsWith<TransientSessionException> {
            gateway(engine).postFormWithSession("https://steamcommunity.com/login/settoken", mapOf("nonce" to "n"))
        }
    }
}
