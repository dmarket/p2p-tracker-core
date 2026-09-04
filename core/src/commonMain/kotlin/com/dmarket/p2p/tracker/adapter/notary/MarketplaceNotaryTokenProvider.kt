package com.dmarket.p2p.tracker.adapter.notary

import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.notary.NotaryTokenProvider

/**
 * The [NotaryTokenProvider] the notary contract actually calls for: the live DMarket access token,
 * taken from the same [MarketplaceCredentialProvider] that authenticates every marketplace call.
 *
 * Sharing that authority is the point. The notary exchanges this token with DMarket auth and binds the
 * resulting account into the attestation, so it must be the *real* session — and it must be as fresh
 * as the one the backend sees, which means going through the provider that owns refresh rather than
 * caching a copy here.
 */
class MarketplaceNotaryTokenProvider(private val credentials: MarketplaceCredentialProvider) : NotaryTokenProvider {

    override suspend fun notaryToken(): String = credentials.current()?.token
        // `current()` returning null is the provider's "there is no usable session" verdict, not a
        // transient blip. Opening the socket anyway would spend an MPC handshake to earn a 401 the
        // browser cannot even read — so stop here, where the reason is still legible.
        ?: error("no DMarket session — cannot authenticate to the notary")
}
