package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer

/** The DER envelopes the platforms import an Ed25519 key from (RFC 8410), around the key's hex. */
internal object Ed25519Der {
    /** PKCS#8 header of a private key, before its 32-byte seed. */
    private const val PKCS8_PREFIX = "302e020100300506032b657004220420"

    /** SubjectPublicKeyInfo header of a public key, before its 32 bytes. */
    private const val SPKI_PREFIX = "302a300506032b6570032100"

    fun pkcs8(key: TradingApiKey): ByteArray = (PKCS8_PREFIX + key.secretSeed).hexToByteArray()

    fun spki(key: TradingApiKey): ByteArray = (SPKI_PREFIX + key.publicKey).hexToByteArray()
}

/**
 * This signer, once a probe it signs verifies with [verify] under the public half of its key; throws
 * [IllegalArgumentException] otherwise.
 */
internal suspend fun Ed25519Signer.ownedBy(verify: suspend (message: ByteArray, signature: ByteArray) -> Boolean): Ed25519Signer {
    val probe = PAIR_PROBE.encodeToByteArray()
    require(verify(probe, sign(probe))) { "the secret key does not belong to the public key" }
    return this
}

private const val PAIR_PROBE = "trading api key pair check"
