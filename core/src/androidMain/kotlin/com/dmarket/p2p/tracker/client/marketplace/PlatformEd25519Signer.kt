package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer

/**
 * Not available on Android. `java.security` has Ed25519 only from API 33 (minSdk is 24), and the Android
 * host authenticates to DMarket with its own Bearer token (`CredentialMarketplaceAuthenticator`), so it
 * never signs Trading API requests.
 */
actual suspend fun platformEd25519Signer(key: TradingApiKey): Ed25519Signer = throw UnsupportedOperationException(
    "Ed25519 Trading API signing is not supported on Android; authenticate with the host's DMarket token " +
        "(CredentialMarketplaceAuthenticator).",
)
