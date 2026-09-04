package com.dmarket.p2p.tracker.port.notary

/**
 * The Steam **web session** credentials a cookie-authenticated proven request needs, resolved at the IO edge.
 *
 * ## Why this is a separate port rather than an argument
 *
 * Every other credential on the proving path is passed *into* it: the tracker loop owns refresh, and a second
 * refresh authority racing it over a rotating token is the failure this codebase avoids everywhere. The Steam
 * web session is the exception, and deliberately so — it is not a value this library refreshes or stores. It
 * lives in the platform's cookie jar (`chrome.cookies` on web, the WebView jar on mobile), where the browser
 * keeps it current and `DefaultSteamSessionRefresher` renews it in place.
 *
 * Resolving it here, in whatever context actually runs the prover, buys two things that passing it could not:
 *
 *  - **it never crosses a message boundary.** The web prover runs in an offscreen document and the loop
 *    delegates to it through the host, whose payload is documented credential-free. A cookie folded into that
 *    JSON — or added as a fourth delegate argument — would be a session secret travelling through host code
 *    for no reason, and the offscreen document can read the jar itself.
 *  - **the delegate signature does not change.** No existing host has to be updated to keep working.
 *
 * Implementations return `null` rather than throwing when there is no session; the caller turns that into a
 * failure *before* loading ~10 MB of WASM, so a logged-out user costs nothing.
 *
 * **Web requirements** for the context that implements this: the `"cookies"` permission and `host_permissions`
 * for `https://steamcommunity.com`. See `vendor/tlsn/INTEGRATION.md`.
 */
interface SteamProofCookieSource {

    /**
     * The live Steam web session, or `null` when there is none (not logged in, or the platform cannot read the
     * jar).
     *
     * **One method returning both values, rather than one per value.** The two are read from the same jar in
     * the same moment and a write needs both, so separate accessors meant `sessionid` was fetched twice per
     * proven write — three extension IPC round-trips for two values, plus a window in which the two reads
     * could disagree about the session they describe.
     */
    suspend fun currentSession(): SteamProofSession?
}

/**
 * The Steam web session as a proven request needs it: the `cookie` header to send, and the `sessionid` a
 * community write echoes in its form body.
 *
 * @property cookieHeader the full request-header value (`steamLoginSecure=…; sessionid=…`). Whole rather than
 *   parsed, because that is exactly what the request sends and because the header's *name* is what
 *   [com.dmarket.p2p.tracker.notary.ProvenReadSpec.redactRequestHeaderValues] withholds — keeping the value
 *   intact keeps the redaction total.
 * @property sessionId the anti-CSRF token, or `null` when the jar has none. ⚠️ Unlike [cookieHeader], this
 *   value is **disclosed** by the resulting proof: it occupies a request body, and the vendored prover has no
 *   request-body reveal control. Bounded — it is useless without the cookie, which is redacted — but real,
 *   which is why a write needs `ProvenRead.acknowledgeRequestBodyDisclosure`.
 */
data class SteamProofSession(val cookieHeader: String, val sessionId: String?) {
    init {
        require(cookieHeader.isNotBlank()) { "a session with a blank cookie header is not a session" }
    }
}

/**
 * The default: no Steam web session available, so every cookie-authenticated read fails fast.
 *
 * The right default for a platform that has not wired the jar, and for the token-authed reads it changes
 * nothing — they never ask. Fail-closed rather than fail-open: a prover that silently issued a
 * cookie-authenticated request with no cookie would get an unauthenticated Steam response and attest *that*.
 */
object NoSteamProofCookieSource : SteamProofCookieSource {
    override suspend fun currentSession(): SteamProofSession? = null
}
