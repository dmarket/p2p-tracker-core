package com.dmarket.p2p.tracker.port.notary

/**
 * Supplies the credential the prover offers to the notary in `Sec-WebSocket-Protocol` as
 * `bearer.<token>`.
 *
 * Per the notary's client contract this is the **end user's own DMarket access token** — the same
 * one the marketplace calls use. The notary treats it as opaque (it never parses it) and exchanges it
 * with DMarket's auth service, which answers with the account it belongs to; the notary then staples
 * that account into the attestation as the `dmarket.account.v1` extension. That is what makes the
 * account binding unforgeable by the prover, so sourcing this token from anywhere other than the real
 * DMarket session would produce attestations bound to the wrong account.
 *
 * It is **not** the Steam JWT ([com.dmarket.p2p.tracker.model.steam.SteamCredential]) and does not
 * touch the audit boundary — the notary never sees the Steam credential either way. It is a separate
 * seam only so a host that owns its own token lifecycle can supply it by delegation.
 *
 * Treat it as a live account credential: a leak is worth more than a notary-scoped token would be.
 */
fun interface NotaryTokenProvider {
    /**
     * The current bearer credential (RFC-6455 token chars only — no spaces, no commas; a base64url
     * JWT satisfies both).
     *
     * Throws when there is no usable DMarket session. Failing here is deliberate and cheap: it happens
     * before the WebSocket is opened, whereas a browser cannot read the notary's `401` and would only
     * see an indistinguishable connection error.
     */
    suspend fun notaryToken(): String
}
