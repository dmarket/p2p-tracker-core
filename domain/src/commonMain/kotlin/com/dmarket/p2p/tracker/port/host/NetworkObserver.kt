package com.dmarket.p2p.tracker.port.host

import com.dmarket.p2p.tracker.model.NetworkExchange

/**
 * A passive sink for observed HTTP exchanges. Installed (when non-no-op) on the shared Ktor client
 * factory so both the Steam reader and the DMarket marketplace client report through it; the default
 * [com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver] makes observability zero-overhead in
 * production.
 *
 * **Contract:** an observer must be passive — it must never feed data back into a request, and it must
 * not throw in a way that aborts the live call (the plugin wraps invocations defensively). By default
 * the [NetworkExchange] it receives is already redacted (see [redactSecrets]), so the implementation
 * may persist/forward it freely without crossing the audit boundary.
 */
fun interface NetworkObserver {
    suspend fun onExchange(exchange: NetworkExchange)

    /**
     * Whether the capture points must redact secrets before building the [NetworkExchange]. **Defaults
     * to `true`** — every production observer keeps the audit boundary intact, so no un-redacted
     * credential ever reaches a persisted/forwarded record. The **only** intended override is the
     * dev-only `:debug-harness` observer, whose session log runs in a local Chrome debug extension with
     * no external sink; it opts out to show raw request/response bodies verbatim for diagnosis. Do not
     * override this to `false` in any published/production wiring.
     */
    val redactSecrets: Boolean get() = true
}
