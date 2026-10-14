package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer

/**
 * The platform's Ed25519 [Ed25519Signer] for [key]. The seed goes into the platform's key object and stays there.
 *
 * Before it is returned the signer signs a probe that must verify under [key]'s public half, so a seed of another
 * key, or a public half that is not an Ed25519 key, is refused here with [IllegalArgumentException] rather than by
 * the gateway on the first request; its message never carries the key. Any other exception means the platform
 * cannot sign Ed25519 at all.
 *
 * - **JVM** → the JDK's `Ed25519` provider.
 * - **web** → WebCrypto `Ed25519`, in the browser and on Node alike. Chrome ships it from version 137; Node 24
 *   has it.
 *
 * TODO: Android and iOS actuals when those targets are enabled.
 */
expect suspend fun platformEd25519Signer(key: TradingApiKey): Ed25519Signer
