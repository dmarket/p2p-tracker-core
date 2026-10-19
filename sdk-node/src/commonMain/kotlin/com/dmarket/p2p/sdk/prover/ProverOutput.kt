package com.dmarket.p2p.sdk.prover

import com.dmarket.p2p.tracker.client.marketplace.TradingApiSignature
import com.dmarket.p2p.tracker.net.NetworkRedaction

/**
 * A line of a prover process's output, or of a failure it reported, made safe for the SDK's log. The child held
 * the Steam token and one proof's signed upgrade headers, so its text is redacted for both whatever it says: the
 * signed headers' values, any JWT-shaped run, and every secret-named parameter.
 */
object ProverOutput {
    private val SECRET_PARAMS = NetworkRedaction.plusSecretParam("access_token")
    private const val MAX_LINE = 2_000

    fun redact(line: String): String = NetworkRedaction.redactBody(TradingApiSignature.redact(line), SECRET_PARAMS, MAX_LINE).orEmpty()
}
