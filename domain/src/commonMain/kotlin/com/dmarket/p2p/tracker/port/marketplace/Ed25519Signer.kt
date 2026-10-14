package com.dmarket.p2p.tracker.port.marketplace

/**
 * Ed25519 over one key: signs [message] and returns the 64-byte signature. The key stays inside the
 * implementation.
 *
 * Suspending because the browser's WebCrypto signs asynchronously.
 */
fun interface Ed25519Signer {
    suspend fun sign(message: ByteArray): ByteArray
}
