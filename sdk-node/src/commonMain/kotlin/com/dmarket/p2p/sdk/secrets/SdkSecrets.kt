package com.dmarket.p2p.sdk.secrets

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a key push did. */
enum class KeyPush {
    /** No key was held. */
    FIRST,

    /** The same key again: nothing changes. */
    SAME,

    /** A different key replaced the held one. The account it signs for may be another one. */
    REPLACED,
}

/** What a Steam token push did. */
sealed interface TokenPush {
    data class Held(val credential: SteamCredential) : TokenPush

    /** The token names [tokenSteamId], not the account DMarket links ([linkedSteamId]); the held token stays. */
    data class WrongAccount(val tokenSteamId: SteamId, val linkedSteamId: SteamId?) : TokenPush
}

/**
 * The two secrets the bot pushes, held in memory only: lost on restart, never written to disk, never returned.
 *
 * **The Steam account is DMarket's to name, not the bot's.** A token is held when its `sub` is the account the
 * last heartbeat named as linked. Before any heartbeat has named one, the first token is held on trust — there
 * is nothing to check it against, and the loop's own guard blocks every Steam action the moment the heartbeat
 * disagrees — but a token for a *different* account than the one already held is refused. Either way a
 * refused token never evicts the held one.
 */
class SdkSecrets {
    private val mutex = Mutex()
    private var key: TradingApiKey? = null
    private var steam: SteamCredential? = null

    val dmarketKey: TradingApiKey? get() = key
    val steamCredential: SteamCredential? get() = steam

    suspend fun putDmarketKey(next: TradingApiKey): KeyPush = mutex.withLock {
        val previous = key
        key = next
        when {
            previous == null -> KeyPush.FIRST
            previous == next -> KeyPush.SAME
            else -> KeyPush.REPLACED
        }
    }

    /** Holds [credential] when it belongs to [linkedSteamId] — see the class note for an account not yet named. */
    suspend fun putSteamCredential(credential: SteamCredential, linkedSteamId: SteamId?): TokenPush = mutex.withLock {
        val subject = credential.subjectSteamId
        val expected = linkedSteamId ?: steam?.subjectSteamId
        if (expected != null && expected != subject) return@withLock TokenPush.WrongAccount(subject, linkedSteamId)
        steam = credential
        TokenPush.Held(credential)
    }
}
