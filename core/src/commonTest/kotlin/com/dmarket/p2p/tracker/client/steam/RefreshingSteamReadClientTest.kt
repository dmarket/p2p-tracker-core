package com.dmarket.p2p.tracker.client.steam

import com.dmarket.p2p.tracker.client.HttpStatusException
import com.dmarket.p2p.tracker.client.createHttpClient
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.support.FakeClock
import com.dmarket.p2p.tracker.support.FakeCredentialVault
import com.dmarket.p2p.tracker.support.FakeSteamSessionScraper
import com.dmarket.p2p.tracker.support.fakeSteamCredential
import com.dmarket.p2p.tracker.support.offerStates
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies that [RefreshingSteamReadClient] handles Steam 401 responses by forcing a credential
 * refresh via [SteamCredentialProvider.forceRefresh] and retrying the request once.
 */
class RefreshingSteamReadClientTest {

    private val clock = FakeClock()

    private fun buildRefreshing(
        responses: List<Pair<HttpStatusCode, String>>,
        freshToken: String = "fresh-token",
    ): Pair<RefreshingSteamReadClient, FakeSteamSessionScraper> {
        var callIndex = 0
        val engine = MockEngine {
            val (status, body) = responses[callIndex.coerceAtMost(responses.lastIndex)]
            callIndex++
            respond(content = body, status = status, headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val vault = FakeCredentialVault(steamCredential = null)
        val scraper = FakeSteamSessionScraper(result = fakeSteamCredential(token = freshToken))
        val provider = SteamCredentialProvider(vault = vault, scraper = scraper, clock = clock)
        val delegate = KtorSteamReadClient(httpClient = createHttpClient(engine))
        return RefreshingSteamReadClient(delegate = delegate, provider = provider) to scraper
    }

    @Test
    fun offer_statuses_retries_once_after_401() = runTest {
        val (client, scraper) = buildRefreshing(
            listOf(HttpStatusCode.Unauthorized to "", HttpStatusCode.OK to """{"response": {}}"""),
        )

        val result = client.offerStates(fakeSteamCredential(token = "old"), setOf(OfferId("1")))

        assertTrue(result.isEmpty())
        assertEquals(1, scraper.scrapeCalls)
    }

    @Test
    fun recent_transfers_retries_once_after_401() = runTest {
        val (client, scraper) = buildRefreshing(
            listOf(HttpStatusCode.Unauthorized to "", HttpStatusCode.OK to """{"response": {}}"""),
        )

        val result = client.recentTransfers(fakeSteamCredential(token = "old"), maxTrades = 50)

        assertTrue(result.isEmpty())
        assertEquals(1, scraper.scrapeCalls)
    }

    @Test
    fun second_401_on_retry_propagates_without_another_refresh() = runTest {
        val (client, scraper) = buildRefreshing(
            listOf(HttpStatusCode.Unauthorized to "", HttpStatusCode.Unauthorized to ""),
        )

        assertFailsWith<HttpStatusException> { client.offerStates(fakeSteamCredential(token = "old"), setOf(OfferId("1"))) }
        assertEquals(1, scraper.scrapeCalls)
    }

    @Test
    fun retries_once_after_403() = runTest {
        // Steam's IEconService returns 403 ("verify your key=") for a rejected/rotated access_token —
        // it must trigger the same refresh + retry as a 401.
        val (client, scraper) = buildRefreshing(
            listOf(HttpStatusCode.Forbidden to "forbidden", HttpStatusCode.OK to """{"response": {}}"""),
        )

        val result = client.recentTransfers(fakeSteamCredential(token = "old"), maxTrades = 50)

        assertTrue(result.isEmpty())
        assertEquals(1, scraper.scrapeCalls)
    }

    @Test
    fun second_403_on_retry_propagates_without_another_refresh() = runTest {
        val (client, scraper) = buildRefreshing(
            listOf(HttpStatusCode.Forbidden to "", HttpStatusCode.Forbidden to ""),
        )

        assertFailsWith<HttpStatusException> { client.offerStates(fakeSteamCredential(token = "old"), setOf(OfferId("1"))) }
        assertEquals(1, scraper.scrapeCalls)
    }

    @Test
    fun non_auth_errors_propagate_without_refreshing() = runTest {
        val (client, scraper) = buildRefreshing(listOf(HttpStatusCode.NotFound to "not found"))

        assertFailsWith<HttpStatusException> { client.offerStates(fakeSteamCredential(token = "old"), setOf(OfferId("1"))) }
        assertEquals(0, scraper.scrapeCalls)
    }

    @Test
    fun success_does_not_refresh() = runTest {
        val (client, scraper) = buildRefreshing(listOf(HttpStatusCode.OK to """{"response": {}}"""))

        assertTrue(client.offerStates(fakeSteamCredential(), setOf(OfferId("1"))).isEmpty())
        assertEquals(0, scraper.scrapeCalls)
    }
}
