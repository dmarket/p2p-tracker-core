package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamNotification
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class KtorSteamNotificationReaderTest {

    private val credential = fakeSteamCredential()
    private val modifiedAt = Instant.fromEpochSeconds(1_781_697_600)
    private val actorAccountId = 39_780_002L
    private val counterparty = SteamId((actorAccountId + SteamNotification.ACCOUNT_ID_OFFSET).toString())

    private fun reader(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
        record: MutableList<String>? = null,
    ): KtorSteamNotificationReader {
        val engine = MockEngine { request ->
            record?.add(request.url.toString())
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        return KtorSteamNotificationReader(httpClient = createHttpClient(engine))
    }

    private fun body(type: Int = 29, actor: Long = 39_780_002L, timestamp: Long = 1_781_697_600) =
        """{"response":{"notifications":[{"notification_type":$type,"actor":"$actor","timestamp":$timestamp}]}}"""

    @Test
    fun resolves_the_reversal_actor() = runTest {
        val resolved = reader(body()).reversalInitiator(credential, counterparty, modifiedAt)
        assertEquals(counterparty, resolved)
    }

    @Test
    fun sends_both_mandatory_flags_and_the_token() = runTest {
        // Omitting either flag makes Steam return an empty list, indistinguishable from "no notification".
        val urls = mutableListOf<String>()
        reader(body(), record = urls).reversalInitiator(credential, counterparty, modifiedAt)
        val url = urls.single()
        assertTrue(url.contains("include_read=true"), "include_read is mandatory: $url")
        assertTrue(url.contains("include_hidden=true"), "include_hidden is mandatory: $url")
        assertTrue(url.contains("ISteamNotificationService/GetSteamNotifications"), "unexpected path: $url")
    }

    @Test
    fun does_not_touch_the_stream_when_the_correlation_inputs_are_unknown() = runTest {
        // The notification payload is broad, so it must not be fetched at all when it could not be used.
        val urls = mutableListOf<String>()
        val r = reader(body(), record = urls)
        assertNull(r.reversalInitiator(credential, counterparty = null, modifiedAt = modifiedAt))
        assertNull(r.reversalInitiator(credential, counterparty = counterparty, modifiedAt = null))
        assertTrue(urls.isEmpty(), "no request should have been issued: $urls")
    }

    @Test
    fun a_failed_read_resolves_to_nothing_instead_of_throwing() = runTest {
        // Steam signs out whoever performed a rollback, so a failure here is the expected branch — it must
        // not propagate into the watch cycle.
        val resolved = reader("nope", status = HttpStatusCode.Unauthorized).reversalInitiator(credential, counterparty, modifiedAt)
        assertNull(resolved)
    }

    @Test
    fun an_undecodable_body_resolves_to_nothing() = runTest {
        assertNull(reader("<html>not json</html>").reversalInitiator(credential, counterparty, modifiedAt))
    }

    @Test
    fun entries_missing_a_required_field_are_dropped_not_loosely_matched() = runTest {
        val partial = """{"response":{"notifications":[{"notification_type":29,"timestamp":1781697600}]}}"""
        assertNull(reader(partial).reversalInitiator(credential, counterparty, modifiedAt))
    }

    @Test
    fun a_non_matching_notification_resolves_to_nothing() = runTest {
        assertNull(reader(body(type = 4)).reversalInitiator(credential, counterparty, modifiedAt))
        assertNull(reader(body(timestamp = 1_781_697_601)).reversalInitiator(credential, counterparty, modifiedAt))
    }
}
