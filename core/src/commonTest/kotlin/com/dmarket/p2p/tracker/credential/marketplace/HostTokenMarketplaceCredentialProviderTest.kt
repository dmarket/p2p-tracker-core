package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.support.fakeMarketplaceCredential
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mobile-reuse seam: a host that already owns refresh plugs its own token layer in here, and this library
 * adds no second refresh authority.
 */
class HostTokenMarketplaceCredentialProviderTest {

    /** The app's token layer as both apps run it: a refused token is refreshed only while it is still current. */
    private class AppTokens(var current: String? = "t1") : MarketplaceTokenSupplier {
        var refreshes = 0

        override suspend fun supply(rejectedToken: String?): MarketplaceCredential? {
            val now = current ?: return null
            if (rejectedToken == now) {
                refreshes++
                current = "t${refreshes + 1}"
            }
            return current?.let(::fakeMarketplaceCredential)
        }
    }

    @Test
    fun current_asks_with_no_refused_token_and_the_401_path_passes_it_on() = runTest {
        val asked = mutableListOf<String?>()
        val p = HostTokenMarketplaceCredentialProvider { rejected ->
            asked += rejected
            fakeMarketplaceCredential()
        }

        p.current()
        p.forceRefresh("refused-jwt")

        assertContentEquals(listOf(null, "refused-jwt"), asked)
    }

    @Test
    fun a_refused_token_the_app_already_replaced_brings_its_new_token_without_a_refresh() = runTest {
        val app = AppTokens(current = "t2")
        val p = HostTokenMarketplaceCredentialProvider(supplier = app)

        assertEquals("t2", p.forceRefresh("t1")?.token)
        assertEquals(0, app.refreshes)
    }

    @Test
    fun a_refused_current_token_asks_the_app_for_exactly_one_refresh() = runTest {
        val app = AppTokens(current = "t1")
        val p = HostTokenMarketplaceCredentialProvider(supplier = app)

        assertEquals("t2", p.forceRefresh("t1")?.token)
        assertEquals(1, app.refreshes)
        assertEquals("t2", p.current()?.token)
        assertEquals(1, app.refreshes, "reading the token does not refresh it")
    }

    @Test
    fun an_app_with_no_session_reads_as_logged_out_on_the_401_path_too() = runTest {
        val p = HostTokenMarketplaceCredentialProvider(supplier = AppTokens(current = null))

        assertNull(p.forceRefresh("t1"))
        assertTrue(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun a_credential_clears_the_logged_out_flag() = runTest {
        val p = HostTokenMarketplaceCredentialProvider { fakeMarketplaceCredential("host-token") }
        assertEquals("host-token", p.current()?.token)
        assertFalse(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun a_null_from_the_host_means_interactive_login() = runTest {
        val p = HostTokenMarketplaceCredentialProvider { null }
        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun a_thrown_failure_means_interactive_login_by_default() = runTest {
        // Matches a host whose token API throws only when it holds no usable pair (Android's TokenException).
        val p = HostTokenMarketplaceCredentialProvider { error("no token data") }
        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun a_host_that_also_throws_on_io_can_opt_out_of_that_reading() = runTest {
        val p = HostTokenMarketplaceCredentialProvider(treatFailureAsLoggedOut = false) { error("socket closed") }
        assertNull(p.current())
        assertFalse(p.lastRefreshFailedLoggedOut, "a network blip must not show a sign-in prompt")
    }

    @Test
    fun cancellation_propagates_and_is_not_a_session_verdict() = runTest {
        val p = HostTokenMarketplaceCredentialProvider { throw CancellationException("scope torn down") }
        assertFailsWith<CancellationException> { p.current() }
        assertFalse(p.lastRefreshFailedLoggedOut)
    }

    @Test
    fun recovery_after_a_failure_clears_the_flag() = runTest {
        var available = false
        val p = HostTokenMarketplaceCredentialProvider { if (available) fakeMarketplaceCredential() else null }

        assertNull(p.current())
        assertTrue(p.lastRefreshFailedLoggedOut)

        available = true
        assertNotNull(p.current())
        assertFalse(p.lastRefreshFailedLoggedOut)
    }
}
