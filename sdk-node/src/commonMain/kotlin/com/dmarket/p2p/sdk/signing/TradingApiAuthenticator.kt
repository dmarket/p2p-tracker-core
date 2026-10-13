package com.dmarket.p2p.sdk.signing

import com.dmarket.p2p.sdk.secrets.DmarketKey
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.MarketplaceRequest
import com.dmarket.p2p.tracker.port.host.Clock

/** Ed25519 over one key: signs [message] and returns the 64-byte signature. */
fun interface Ed25519Signer {
    fun sign(message: ByteArray): ByteArray
}

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

    fun unsigned(method: String, pathAndQuery: String, body: String, timestamp: String): String = "$method$pathAndQuery$body$timestamp"

    fun headers(key: DmarketKey, signer: Ed25519Signer, request: MarketplaceRequest, epochSeconds: Long): Map<String, String> {
        val timestamp = epochSeconds.toString()
        val signature = signer.sign(unsigned(request.method, request.pathAndQuery, request.body, timestamp).encodeToByteArray())
        return mapOf(
            API_KEY to key.publicKey,
            SIGN_DATE to timestamp,
            REQUEST_SIGN to "dmar ed25519 ${signature.toHex()}",
        )
    }

    private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
}

/**
 * Authenticates every DMarket call with the pushed Trading API key instead of a session token. With no key held
 * it attaches nothing; the SDK does not heartbeat in that state anyway.
 *
 * A `401` is final: there is nothing to refresh, so it surfaces as the loop's missing-connection signal and the
 * SDK reports `DMARKET_AUTH_FAILED` until the bot pushes a working key.
 *
 * @param signerFor builds the signer for a key; called once per key, then reused.
 */
class TradingApiAuthenticator(
    private val currentKey: () -> DmarketKey?,
    private val signerFor: (DmarketKey) -> Ed25519Signer,
    private val clock: Clock,
) : MarketplaceAuthenticator {
    private var cached: Pair<DmarketKey, Ed25519Signer>? = null

    override suspend fun tokenOrNull(): String? = null

    override suspend fun headersFor(request: MarketplaceRequest): Map<String, String> {
        val key = currentKey() ?: return emptyMap()
        val signer = cached?.takeIf { it.first == key }?.second ?: signerFor(key).also { cached = key to it }
        return TradingApiSignature.headers(key, signer, request, clock.now().epochSeconds)
    }

    override suspend fun refreshOnUnauthorized(): Boolean = false
}
