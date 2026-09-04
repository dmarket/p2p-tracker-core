package com.dmarket.p2p.tracker.credential.marketplace

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

    @Test
    fun the_force_flag_reaches_the_host() = runTest {
        // The host's own API takes exactly this flag (Android: `getToken(forceRefresh)`), so the 401 path must
        // arrive there as a forced refresh and the ordinary path must not.
        val forces = mutableListOf<Boolean>()
        val p = HostTokenMarketplaceCredentialProvider { force ->
            forces += force
            fakeMarketplaceCredential()
        }

        p.current()
        p.forceRefresh()

        assertContentEquals(listOf(false, true), forces)
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
