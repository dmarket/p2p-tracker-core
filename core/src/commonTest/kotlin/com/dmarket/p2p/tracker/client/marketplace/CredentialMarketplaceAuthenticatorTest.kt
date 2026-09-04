package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.support.FakeMarketplaceCredentialProvider
import com.dmarket.p2p.tracker.support.fakeMarketplaceCredential
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CredentialMarketplaceAuthenticatorTest {

    private fun authenticator(providerResult: MarketplaceCredential?) =
        CredentialMarketplaceAuthenticator(FakeMarketplaceCredentialProvider(result = providerResult))

    @Test
    fun token_delegates_to_provider_current() = runTest {
        val auth = authenticator(fakeMarketplaceCredential("bearer-xyz"))
        assertEquals("bearer-xyz", auth.tokenOrNull())
    }

    @Test
    fun token_is_null_when_logged_out() = runTest {
        assertNull(authenticator(providerResult = null).tokenOrNull())
    }

    @Test
    fun refresh_returns_true_when_a_session_is_available() = runTest {
        assertTrue(authenticator(fakeMarketplaceCredential()).refreshOnUnauthorized())
    }

    @Test
    fun refresh_returns_false_when_logged_out() = runTest {
        assertFalse(authenticator(providerResult = null).refreshOnUnauthorized())
    }
}
