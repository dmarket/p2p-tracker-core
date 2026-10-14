package com.dmarket.p2p.sdk.secrets

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
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

/**
 * The DMarket key the bot pushes, held in memory only: lost on restart, never written to disk, never returned.
 * The Steam token is the library's to hold (`PushedSteamSession`).
 */
class SdkSecrets {
    private val mutex = Mutex()
    private var key: TradingApiKey? = null

    val dmarketKey: TradingApiKey? get() = key

    suspend fun putDmarketKey(next: TradingApiKey): KeyPush = mutex.withLock {
        val previous = key
        key = next
        when {
            previous == null -> KeyPush.FIRST
            previous == next -> KeyPush.SAME
            else -> KeyPush.REPLACED
        }
    }
}
