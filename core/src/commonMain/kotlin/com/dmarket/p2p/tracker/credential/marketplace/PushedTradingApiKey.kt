package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.client.marketplace.platformEd25519Signer
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** How a held key relates to the one held before it. */
enum class KeyChange {
    /** No key was held. */
    FIRST,

    /** The same key again: nothing changes. */
    SAME,

    /** A different key replaced the held one. The DMarket account it signs for may be another one. */
    REPLACED,
}

/** What a pushed Trading API key did. No case carries the secret. */
sealed interface TradingKeyPushResult {
    /** The key is held now; [change] says how it relates to the one held before. */
    data class Held(val publicKey: String, val change: KeyChange) : TradingKeyPushResult

    /** The pair is not a key this library can sign with; [reason] never quotes it. The held key stays. */
    data class Invalid(val reason: String) : TradingKeyPushResult
}

/**
 * The DMarket Trading API key a host pushes in, held in memory only: lost on restart, never written anywhere,
 * never handed back. A pair is held only once [signerFor] has built a signer for it, so a seed of another key is
 * refused at the push rather than by the gateway on the first request.
 *
 * **Lib machinery:** [key] carries the secret seed. It feeds the request signer and nothing host-facing.
 */
internal class PushedTradingApiKey(private val signerFor: suspend (TradingApiKey) -> Ed25519Signer = ::platformEd25519Signer) {
    private val mutex = Mutex()

    // No @Volatile: JS is single-threaded; JVM callers share a single coroutine scope per loop.
    private var held: TradingApiKey? = null

    /** The held key; `null` before the first accepted push. */
    val key: TradingApiKey? get() = held

    suspend fun push(publicKey: String, secretKey: String): TradingKeyPushResult {
        val key = TradingApiKey.parse(publicKey, secretKey)
            ?: return TradingKeyPushResult.Invalid("expected a hex Ed25519 key pair: a 32-byte public key and its secret")
        try {
            signerFor(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return TradingKeyPushResult.Invalid("the secret key cannot sign")
        }
        return mutex.withLock {
            val previous = held
            held = key
            val change = when (previous) {
                null -> KeyChange.FIRST
                key -> KeyChange.SAME
                else -> KeyChange.REPLACED
            }
            TradingKeyPushResult.Held(key.publicKey, change)
        }
    }
}
