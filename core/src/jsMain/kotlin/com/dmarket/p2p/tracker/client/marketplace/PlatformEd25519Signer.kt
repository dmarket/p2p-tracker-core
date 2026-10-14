package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.Promise

/**
 * WebCrypto `Ed25519` through `globalThis.crypto.subtle`: one code path for an extension service worker and for
 * Node, with no `node:crypto`. The seed is imported non-extractable.
 */
actual suspend fun platformEd25519Signer(key: TradingApiKey): Ed25519Signer {
    val subtle = js("globalThis.crypto && globalThis.crypto.subtle").unsafeCast<SubtleCrypto?>()
        ?: throw UnsupportedOperationException("WebCrypto is not available")
    val privateKey = subtle.importKey("pkcs8", Ed25519Der.pkcs8(key), ED25519, false, arrayOf("sign")).await()
    val publicKey = try {
        subtle.importKey("spki", Ed25519Der.spki(key), ED25519, false, arrayOf("verify")).await()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        throw IllegalArgumentException("the public key is not an Ed25519 key")
    }
    val signer = Ed25519Signer { message -> subtle.sign(ED25519, privateKey, message).await().toByteArray() }
    return signer.ownedBy { message, signature -> subtle.verify(ED25519, publicKey, signature, message).await() }
}

/** The part of WebCrypto's `SubtleCrypto` the signer uses. A `ByteArray` is an `Int8Array`, a valid `BufferSource`. */
private external interface SubtleCrypto {
    fun importKey(
        format: String,
        keyData: ByteArray,
        algorithm: dynamic,
        extractable: Boolean,
        keyUsages: Array<String>,
    ): Promise<CryptoKey>

    fun sign(algorithm: dynamic, key: CryptoKey, data: ByteArray): Promise<ArrayBufferRef>

    fun verify(algorithm: dynamic, key: CryptoKey, signature: ByteArray, data: ByteArray): Promise<Boolean>
}

private external interface CryptoKey

private external interface ArrayBufferRef

private fun ArrayBufferRef.toByteArray(): ByteArray {
    val buffer = this
    return js("new Int8Array(buffer)").unsafeCast<ByteArray>()
}

private val ED25519: dynamic = js("({name: 'Ed25519'})")
