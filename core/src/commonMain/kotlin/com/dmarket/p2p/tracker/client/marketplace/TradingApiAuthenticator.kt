package com.dmarket.p2p.tracker.client.marketplace

import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * DMarket's Trading API request signature, the scheme every API-key call uses: Ed25519 over
 * `METHOD + path-with-query + body + timestamp`, sent as `X-Api-Key`, `X-Sign-Date` (epoch seconds) and
 * `X-Request-Sign: dmar ed25519 <hex>`. The gateway rejects a date more than two minutes off, so a signature is
 * made per request and never reused.
 */
object TradingApiSignature {
    const val API_KEY = "X-Api-Key"
    const val SIGN_DATE = "X-Sign-Date"
    const val REQUEST_SIGN = "X-Request-Sign"

    private const val SCHEME = "dmar ed25519 "

    /** The exact string that is signed. */
    fun unsigned(method: String, pathAndQuery: String, body: String, timestamp: String): String = "$method$pathAndQuery$body$timestamp"

    /** The three headers for [request], signed with [signer] at [epochSeconds]. The secret is never among them. */
    suspend fun headers(key: TradingApiKey, signer: Ed25519Signer, request: MarketplaceRequest, epochSeconds: Long): Map<String, String> {
        val timestamp = epochSeconds.toString()
        val signature = signer.sign(unsigned(request.method, request.pathAndQuery, request.body, timestamp).encodeToByteArray())
        return mapOf(
            API_KEY to key.publicKey,
            SIGN_DATE to timestamp,
            REQUEST_SIGN to SCHEME + signature.toHexString(),
        )
    }
}

/**
 * Authenticates every DMarket call with a Trading API key instead of a session token: each request is signed
 * ([TradingApiSignature]) with the key [currentKey] holds at that moment. With no key held it attaches nothing.
 *
 * A `401` is final: there is nothing to refresh, so it surfaces as the loop's missing-connection signal until the
 * host supplies a working key.
 *
 * @param signerFor builds the signer for a key; called once per key, then reused until the key changes. The
 *   platform's own by default, which refuses a seed that does not belong to the public key.
 */
class TradingApiAuthenticator(
    private val currentKey: () -> TradingApiKey?,
    private val clock: Clock,
    private val signerFor: suspend (TradingApiKey) -> Ed25519Signer = ::platformEd25519Signer,
) : MarketplaceAuthenticator {
    private val mutex = Mutex()
    private var cached: Pair<TradingApiKey, Ed25519Signer>? = null

    override suspend fun tokenOrNull(): String? = null

    override suspend fun headersFor(request: MarketplaceRequest): Map<String, String> {
        val key = currentKey() ?: return emptyMap()
        return TradingApiSignature.headers(key, signerOf(key), request, clock.now().epochSeconds)
    }

    override suspend fun refreshOnUnauthorized(): Boolean = false

    private suspend fun signerOf(key: TradingApiKey): Ed25519Signer = mutex.withLock {
        cached?.takeIf { it.first == key }?.second ?: signerFor(key).also { cached = key to it }
    }
}
