package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.client.marketplace.platformEd25519Signer
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** How a held key relates to the one held before it. */
enum class KeyChange {
    /** No key was held. */
    FIRST,

    /**
     * The same key again, perhaps in another case: the binding stays. Requests carry the public key as it was
     * pushed last.
     */
    SAME,

    /** A different key for the same DMarket account: its heartbeat named the Steam account the held key is bound to. */
    ROTATED,

    /**
     * A different key replaced the held one before any Steam account was bound to compare it with. The DMarket
     * account it signs for may be another one.
     */
    REPLACED,
}

/** What a pushed Trading API key did. No case carries the secret. */
sealed interface TradingKeyPushResult {
    /** The key is held now; [change] says how it relates to the one held before. */
    data class Held(val publicKey: String, val change: KeyChange) : TradingKeyPushResult

    /**
     * The pair is not a key this library can sign with, or DMarket refused it; [reason] never quotes it. The held
     * key stays.
     */
    data class Invalid(val reason: String) : TradingKeyPushResult

    /**
     * The key signs for another DMarket account: its heartbeat names [keyLinkedSteamId] (`null`: none), not
     * [linkedSteamId], the Steam account the held key ([heldPublicKey]) is bound to. The held key and the binding
     * stay; switching accounts takes a restart.
     */
    data class AccountChanged(
        val publicKey: String,
        val heldPublicKey: String,
        val linkedSteamId: SteamId,
        val keyLinkedSteamId: SteamId?,
    ) : TradingKeyPushResult

    /** The key could not be checked: its heartbeat did not come back ([reason]). The held key stays; push it again. */
    data class Unverified(val publicKey: String, val reason: String) : TradingKeyPushResult
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

    /**
     * Holds the pair once it can sign. A key other than the one held goes to [vouch] first, which answers for it:
     * the candidate is held only when the answer is [TradingKeyPushResult.Held]. Pushes go one at a time, so a
     * check always compares against the key that is held when it finishes.
     */
    suspend fun push(
        publicKey: String,
        secretKey: String,
        vouch: suspend (candidate: TradingApiKey, held: TradingApiKey) -> TradingKeyPushResult = { candidate, _ ->
            TradingKeyPushResult.Held(candidate.publicKey, KeyChange.REPLACED)
        },
    ): TradingKeyPushResult {
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
            when (val previous = held) {
                null -> TradingKeyPushResult.Held(key.publicKey, KeyChange.FIRST).also { held = key }
                key -> TradingKeyPushResult.Held(key.publicKey, KeyChange.SAME).also { held = key }
                else -> vouch(key, previous).also { if (it is TradingKeyPushResult.Held) held = key }
            }
        }
    }
}
