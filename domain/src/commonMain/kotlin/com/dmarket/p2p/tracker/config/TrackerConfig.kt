@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.tracker.config

import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.net.SteamHosts
import com.dmarket.p2p.tracker.notary.OFFER_ID
import com.dmarket.p2p.tracker.notary.ProvenReadKind
import com.dmarket.p2p.tracker.notary.ProvenReadRegistry
import com.dmarket.p2p.tracker.notary.TOKEN_PLACEHOLDER
import com.dmarket.p2p.tracker.notary.TRADE_ID
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * One typed, host-suppliable bundle of every operationally-tunable / third-party-dependent value the
 * library would otherwise hardcode (cadence, backoff, credential freshness, the session-refresh gate,
 * the HTTP timeout, notary concurrency, Steam base URLs / read endpoints, scraping regexes, cookie
 * names, and the CS2 inventory context id). It is passed to the library's init function
 * (`startTracker` / `createBrowserLoop`); the host typically builds it by overriding a few fields of
 * [defaults] with values it fetched from a remote source, but [defaults] alone reproduces the
 * in-code baseline exactly so omitting the config changes nothing.
 *
 * Durations are stored as `Int` milliseconds — every value here is ≤ 1 hour, well inside `Int` range
 * — so the shape stays primitive (JS-friendly across the export boundary); internal consumers read
 * the `Duration` accessors on each sub-config.
 *
 * **Deliberately NOT here (kept hardcoded):** the Steam create/cancel **write** URL path suffixes
 * (`…/tradeoffer/new/send`, `…/tradeoffer/{id}/cancel`) — enforcement-by-encapsulation is a
 * non-negotiable audit rule (a remote-tunable write path could reach a `confirm`/`mobileconf`
 * endpoint) — and the locked DMarket per-type endpoint paths under `/exchange/v1/p2p/ext/`.
 */
@JsExport
data class TrackerConfig(
    val cadence: CadenceConfig = CadenceConfig(),
    val credentials: CredentialConfig = CredentialConfig(),
    val http: HttpConfig = HttpConfig(),
    val marketplaceRetry: MarketplaceRetryConfig = MarketplaceRetryConfig(),
    val marketplaceScrape: MarketplaceScrapeConfig = MarketplaceScrapeConfig(),
    val notary: NotaryConfig = NotaryConfig(),
    val steamEndpoints: SteamEndpointsConfig = SteamEndpointsConfig(),
    val steamProfile: SteamProfileConfig = SteamProfileConfig(),
    val steamScrape: SteamScrapeConfig = SteamScrapeConfig(),
    val game: GameConfig = GameConfig(),
    val writeClaims: WriteClaimConfig = WriteClaimConfig(),
    /**
     * Appended at the END of this parameter list on purpose: this is an `@JsExport` data class, so a
     * new field inserted mid-list would silently re-map a JS host's positional construction.
     */
    val steamWrites: SteamWriteConfig = SteamWriteConfig(),
) {
    companion object {

        const val DEFAULT_DMARKET_BASE_URL: String = "https://api.dmarket.com"

        /** The baseline that reproduces every previously-hardcoded value verbatim. */
        fun defaults(): TrackerConfig = TrackerConfig()
    }
}

/**
 * Client-owned cadence. Each poll class has an FE-chosen target interval, clamped up to
 * the per-platform floor the OS can actually honour. Floors are split out per surface/mode because
 * what the OS will honour while suspended differs by platform.
 */
@JsExport
data class CadenceConfig(
    val activeOfferIntervalMs: Int = 180_000,
    val revertWatchIntervalMs: Int = 3_600_000,
    val maxActionDelayMs: Int = 3_600_000,
    val webPollFloorMs: Int = 60_000,
    val iosForegroundPollFloorMs: Int = 30_000,
    val iosBackgroundPollFloorMs: Int = 900_000,
    val androidForegroundPollFloorMs: Int = 30_000,
    val androidBackgroundPollFloorMs: Int = 900_000,
    val webHeartbeatFloorMs: Int = 60_000,
    val iosForegroundHeartbeatFloorMs: Int = 90_000,
    val iosBackgroundHeartbeatFloorMs: Int = 900_000,
    val androidForegroundHeartbeatFloorMs: Int = 90_000,
    val androidBackgroundHeartbeatFloorMs: Int = 900_000,
    /**
     * Target poll interval while a watched deal sits in a transient state (Steam offer state 9,
     * `CreatedNeedsConfirmation`) — a seller confirmation is expected imminently. Clamped up to the
     * platform floor like any target, so on web it lands at the 60s `chrome.alarms` floor (a 3× cut
     * from the 180s active-offer cadence); on mobile foreground it reaches the 30s floor.
     */
    val expeditedOfferIntervalMs: Int = 15_000,
    /**
     * How long an expedited window lasts once armed (at offer creation, then re-armed on every tick a
     * state-9 deal is still observed). Bounds runaway fast-polling if the seller never confirms; the
     * backend's stale-offer `cancel_offer` lease is the eventual backstop.
     */
    val expeditedWindowMs: Int = 300_000,
    /**
     * The heartbeat interval used when the backend response carries no `ttl_seconds` (the backend ttl
     * otherwise dictates the cadence and always wins). `0` = fall back to the platform heartbeat floor.
     * Like a ttl, the value is clamped to `[heartbeatFloor, maxActionDelayMs]`.
     */
    val fallbackHeartbeatIntervalMs: Int = 0,
) {
    @JsExport.Ignore val activeOfferInterval: Duration get() = activeOfferIntervalMs.milliseconds

    @JsExport.Ignore val expeditedOfferInterval: Duration get() = expeditedOfferIntervalMs.milliseconds

    @JsExport.Ignore val expeditedWindow: Duration get() = expeditedWindowMs.milliseconds

    @JsExport.Ignore val revertWatchInterval: Duration get() = revertWatchIntervalMs.milliseconds

    @JsExport.Ignore val maxActionDelay: Duration get() = maxActionDelayMs.milliseconds

    @JsExport.Ignore val fallbackHeartbeatInterval: Duration get() = fallbackHeartbeatIntervalMs.milliseconds

    /** Lowest poll interval the platform can sustain in this mode. */
    @JsExport.Ignore
    fun pollFloor(surface: RuntimeSurface, mode: TrackerMode): Duration = when (surface) {
        RuntimeSurface.WebChrome, RuntimeSurface.WebFirefox -> webPollFloorMs
        RuntimeSurface.IosNative -> if (mode == TrackerMode.Foreground) iosForegroundPollFloorMs else iosBackgroundPollFloorMs
        RuntimeSurface.AndroidNative ->
            if (mode == TrackerMode.Foreground) androidForegroundPollFloorMs else androidBackgroundPollFloorMs
    }.milliseconds

    /** Lowest heartbeat interval the platform can sustain in this mode. */
    @JsExport.Ignore
    fun heartbeatFloor(surface: RuntimeSurface, mode: TrackerMode): Duration = when (surface) {
        RuntimeSurface.WebChrome, RuntimeSurface.WebFirefox -> webHeartbeatFloorMs
        RuntimeSurface.IosNative ->
            if (mode == TrackerMode.Foreground) iosForegroundHeartbeatFloorMs else iosBackgroundHeartbeatFloorMs
        RuntimeSurface.AndroidNative ->
            if (mode == TrackerMode.Foreground) androidForegroundHeartbeatFloorMs else androidBackgroundHeartbeatFloorMs
    }.milliseconds
}

/** Credential-freshness skews and the session-refresh self-gate headrooms. */
@JsExport
data class CredentialConfig(
    val steamSkewMs: Int = 60_000,
    /**
     * How much life a DMarket access token must have left to be **sent at all**. This is the "is it still
     * usable right now" floor, NOT the refresh trigger — see [marketplaceSessionGateHeadroomMs].
     */
    val marketplaceSkewMs: Int = 60_000,
    val sessionGateHeadroomMs: Int = 3_600_000,
    /**
     * Refresh the DMarket access token once it has less than this much life left — the **refresh trigger**.
     *
     * Read from the token's own `exp` claim (see
     * [com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenJwt]), never from the cookie carrying it.
     *
     * Default lowered from 1h to 10min to match the DMarket Android client's own threshold, and to keep the
     * window in which we and the site's SPA might both decide to rotate as small as is useful. It must stay
     * strictly greater than [marketplaceSkewMs], or the trigger never fires before the token is already
     * unusable — the exact shape of the bug this pair of fields replaced, where the 1h headroom was dead
     * code behind a 60s freshness check.
     */
    val marketplaceSessionGateHeadroomMs: Int = 600_000,
    /**
     * A DMarket refresh token with less than this much life left is treated as spent: no request is made and
     * interactive login is reported. Mirrors the Android client's 1-minute refresh-token floor.
     */
    val marketplaceRefreshMinLifeMs: Int = 60_000,
    /**
     * Minimum spacing between two of **our own** refresh attempts.
     *
     * Every successful refresh rotates a shared credential, so this is the throttle that keeps a storm of
     * service-worker spawns (or a 401 retry envelope) from becoming a storm of rotations. Persisted, so it
     * survives a worker restart.
     */
    val marketplaceRefreshMinIntervalMs: Int = 60_000,
) {
    init {
        // Hard floors, enforced here rather than in any one host's config overlay: these three values bound
        // how often this client may rotate a credential it shares with the user's browser session, so a
        // remote-config publish (or a native host building the config by hand) must not be able to widen
        // them. The overlay in each host narrows further; this is the line it cannot cross.
        require(marketplaceRefreshMinIntervalMs >= 30_000) {
            "marketplaceRefreshMinIntervalMs must be >= 30000 (rotation rate limit)"
        }
        require(marketplaceSessionGateHeadroomMs in 60_000..1_800_000) {
            "marketplaceSessionGateHeadroomMs must be within 60000..1800000"
        }
        require(marketplaceSessionGateHeadroomMs > marketplaceSkewMs) {
            "marketplaceSessionGateHeadroomMs must exceed marketplaceSkewMs, else the refresh never triggers"
        }
        require(marketplaceRefreshMinLifeMs >= 0) { "marketplaceRefreshMinLifeMs must be >= 0" }
    }

    @JsExport.Ignore val steamSkew: Duration get() = steamSkewMs.milliseconds

    @JsExport.Ignore val marketplaceSkew: Duration get() = marketplaceSkewMs.milliseconds

    @JsExport.Ignore val sessionGateHeadroom: Duration get() = sessionGateHeadroomMs.milliseconds

    @JsExport.Ignore val marketplaceSessionGateHeadroom: Duration get() = marketplaceSessionGateHeadroomMs.milliseconds

    @JsExport.Ignore val marketplaceRefreshMinLife: Duration get() = marketplaceRefreshMinLifeMs.milliseconds

    @JsExport.Ignore val marketplaceRefreshMinInterval: Duration get() = marketplaceRefreshMinIntervalMs.milliseconds
}

/** HTTP client tuning. */
@JsExport
data class HttpConfig(val requestTimeoutMs: Int = 30_000)

/**
 * Marketplace 401 retry envelope. On an HTTP 401 the [com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient]
 * re-establishes auth and retries the request up to [maxRetries] times, spacing attempts with
 * full-jitter exponential backoff ([com.dmarket.p2p.tracker.policy.ExponentialBackoff]) so a
 * persistent/transient gateway 401 no longer fires back-to-back.
 */
@JsExport
data class MarketplaceRetryConfig(
    /**
     * Retry attempts **after** the initial request (so at most `1 + maxRetries` sends). Note this
     * counts retries, unlike [SteamProfileConfig.maxRetries] which counts *total* attempts.
     */
    val maxRetries: Int = 3,
    /** Base backoff before the first retry; doubled each attempt, capped at [retryMaxDelayMs], plus jitter. */
    val retryBaseDelayMs: Int = 500,
    /** Ceiling for the exponential backoff delay. */
    val retryMaxDelayMs: Int = 8_000,
) {
    init {
        require(maxRetries >= 1) { "maxRetries must be >= 1, was $maxRetries" }
    }
}

/**
 * TLSN notary prover tuning. The no-op prover reads only [maxConcurrency]; the real WASM prover
 * (web) / native provers (mobile) consume the rest.
 *
 * **Selection gate: a proving context, and nothing else.** On web that is an offscreen proof delegate.
 * [notaryUrl] is no longer part of the gate — it is required, and defaults to the deployed production
 * notary ([PRODUCTION_NOTARY_URL]) — so a host that can run a prover runs the real one. Withholding the
 * context is what selects the safe no-op (client-reported) prover: that is still the whole story on a
 * runtime that cannot host the prover (Firefox today) and on any host that passes no prover at all,
 * mobile included, since [com.dmarket.p2p.tracker.runtime.TradeTrackerCore.createLoop] defaults `notary`
 * to [com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver].
 *
 * One condition, down from three, and each removal answers a lived failure rather than a preference —
 * because all three states collapsed into the *same* silent no-op, so a deal the backend had marked
 * `proof_required` failed with an empty `proofPayload` and nothing named why. The `enabled` flag went
 * first: it was redundant with the backend's own per-deal `proof_required` (the backend decides whether a
 * deal *needs* a proof; this config decides only whether this client *can produce* one). The URL followed,
 * because "configured" is not a decision a client should be able to get wrong by omission — in the
 * reference extension it was reachable only through a remote-config publish, so a release shipped ahead of
 * that publish proved nothing, indefinitely and invisibly. Arming the prover is now a property of the
 * build that ships a proving context.
 *
 * **Proof-read shapes** ([offerRead] / [historyRead]) describe the **`IEconService` JSON read** the prover
 * proves — the very call whose integer the client reports. An earlier revision pointed at
 * `steamcommunity.com` HTML pages that nothing in the client parses, so a proof attested a document with no
 * defined relationship to the reported number; that is fixed. Steam owns these shapes, but they are **not**
 * host-suppliable (see [ProvenRead]): a wrong value here fails every proof for that axis rather than
 * degrading.
 *
 * **The disclosure model changed with the target, and the two are a package.** These reads authenticate with
 * the Steam JWT as `?access_token=`, and the prover's request-target disclosure is all-or-nothing (no
 * per-parameter elision), so the request line **must not** be revealed — hence
 * `ProvenReadSpec.revealRequestTarget = false` for both axes, and hence the trade binding moves to the
 * *response*. The token is withheld from the presentation by the same flag and never leaves the device (the
 * notary sees only MPC ciphertext), so the audit boundary holds — but the JWT does now have to reach the
 * proving context, which is a deliberate widening of that seam, not a side effect.
 *
 * **The response is disclosed WHOLE** (`ResponseBodyReveal.All`), which is why each read's
 * [ProvenRead.revealJsonPaths] is inert — kept, unread, as the target of the narrowing. Not a preference: the
 * verifier parses the revealed bytes as an HTTP response, and a selective reveal with the headers withheld
 * cannot contain the `\r\n\r\n` it splits on, so dev2 rejected every proof with *"the revealed response has
 * no header/body separator"*. `ResponseBodyReveal.All` carries the byte-level account and the `TODO`
 * in `SteamProofReadMapper.readSpec` records what narrowing back needs.
 *
 * Path syntax for those lists, meanwhile: the prover's (`spansy`) — dot-separated segments, array
 * indices as literal integers, **no wildcards or filters**. That is why each axis proves a *singular*
 * endpoint whose response shape is fixed; a 50-row `GetTradeHistory` array has no knowable index. A path
 * that does not resolve fails the proof loudly (`JSON path not found in response body: …`), which is the
 * intended failure: a rejected proof, never a proof of the wrong thing.
 */
@JsExport
data class NotaryConfig(
    val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
    /**
     * Notary WebSocket endpoint (`wss://…`) the prover attests through — **required**, defaulting to the
     * deployed production notary ([PRODUCTION_NOTARY_URL]).
     *
     * It was nullable and doubled as the selection gate until this default landed; the class KDoc has why
     * it no longer does. What changes in practice: this value is remote-config settable, so publishing it
     * REDIRECTS the prover at a different notary — which is what a test substrate needs — and can no
     * longer switch the prover off, a published `null` not being expressible against a non-null parameter.
     *
     * Production readiness is still gated on BQ-9 (TLSN security review) + DMA-135 (Steam cipher gate), and
     * the gate has changed MEDIUM, not owner: it used to be an operator publishing this value, and it is now
     * the release that ships a proving context. Be clear about what that costs — **there is deliberately no
     * remote brake left.** Nothing in remote config can stop proofs fleet-wide: this field can only be
     * redirected, `enabledReads` is `@JsExport.Ignore`d (and would fail every marked deal rather than degrade
     * to client-reported), and [NotaryBreakerConfig] is reactive, arming only after real failures. Stopping
     * proofs means shipping a build that withholds the delegate — days, not minutes.
     *
     * Deliberately not validated in `init`, matching [proxyBaseUrl]: a bad value fails the handshake
     * loudly (`notary handshake failed for …`), while a `require` here would throw inside `copy()` and
     * stop the tracker from starting at all.
     */
    val notaryUrl: String = PRODUCTION_NOTARY_URL,
    /**
     * `p2p-wss-proxy` base URL — the target byte pipe, because a browser extension cannot open raw TCP.
     * Inert unless the selection gate above opens (a proving context): nothing connects here in a build
     * that supplies none. The default is the deployed proxy, so this needs setting only to point at a
     * different one — and it must stay a WebSocket URL, which is what the target is dialled as
     * (`connectProxy(proxyBaseUrl, serverName, 443)`).
     */
    val proxyBaseUrl: String = "wss://p2p-wss-proxy.dmarket.com",
    /** Notary handshake subprotocol marker. */
    val subprotocol: String = "tlsn.notary.v2",
    // No signature-algorithm knob: the prover requests SECP256K1ETH from `IssuanceConfig`'s own defaults
    // and this library never passes one, so a field here could only ever disagree with reality.
    /**
     * MPC **send**-transcript cap in bytes — and the one notary knob that costs real bandwidth.
     *
     * `client-core`'s `IssuanceConfig` calls this the "MPC pre-processing bound": the prover garbles circuits
     * for this many plaintext bytes whether or not the request uses them. Its receive-side counterpart is not
     * symmetric — `max_recv_data` is only a ceiling checked at runtime (`mpc-tls`'s record layer allocates
     * from `max_recv_data_online`, whose default is 32 bytes), so it is *this* value that scales the upload.
     * One live session was measured at **63 MB sent** to the notary.
     *
     * **4096 → 2048 → 1024, each step on measurement.** The proven request is `196 + len(token)` bytes: the
     * fixed 196 covers the request line and the four headers `client_core::issue` injects, on the larger
     * (history) axis. The only variable is the token.
     *
     * This is now a **ceiling** rather than the value handed to the prover:
     * [com.dmarket.p2p.tracker.notary.ProvenSentBudget] re-derives that 196 per read (it is this axis's path
     * plus 103 B of framing, and other reads have other paths) and sizes down to what the request actually
     * needs, clamped here. See [sentBudgetMarginPercent] for the headroom and the one-value rollback.
     *
     * 2048 was set while the token length was still unobserved, and the previous note said 1024 could be tried
     * "once that number is in a log". It is: a live dev proof on 2026-08-25 traced a **521-char** token, i.e. a
     * **717 B** request, and closed its notary socket having sent **42,195,046 B** — 42 MB of pre-processing for
     * 717 B of plaintext. 1024 halves that upload and still admits a token of 828 chars, ~1.6× the observed one.
     *
     * **Exceeding this fails EVERY proof**, so that 1.6× is the whole safety argument and the reason not to go
     * lower: Steam's `access_token` is a JWT whose length moves with its claims, and nothing here is notified
     * when it grows. It is remote-config settable in both directions — if proofs start failing across the board
     * shortly after a Steam-side change, publish 2048 and compare the traced token length against 828.
     */
    val maxSentData: Int = 1_024,
    /**
     * MPC recv-transcript **ceiling** in bytes — a runtime bound, NOT a driver of MPC work.
     *
     * Unlike [maxSentData] and [maxRecvDataOnline], this value buys no preprocessing and costs no upload:
     * `mpc-tls`'s `record_layer.rs::alloc` allocates `encrypt` from `sent_len` and `decrypt` from
     * `recv_len_online`, and the `recv_len` this maps to reaches only a bookkeeping counter
     * (`self.max_recv += recv_len`). Measured upload confirms it — 28.6/38.3/48.3 MB tracked
     * [maxRecvDataOnline] alone at 32/1024/2048 while this stayed at 16384 throughout.
     *
     * So lowering it saves nothing and only risks breaking a response that outgrows the new ceiling. Keep it
     * generously above the largest response the proven reads can produce; the sizing decision that matters is
     * [maxRecvDataOnline].
     */
    val maxRecvData: Int = 16_384,
    /**
     * Rayon worker count for the wasm prover.
     *
     * A low value does **not** avoid cross-origin isolation: the shipped module is compiled with shared
     * memory and its `initialize` unconditionally starts the worker spawner and builds a rayon pool, so
     * `SharedArrayBuffer` — and therefore COOP `same-origin` + COEP `require-corp` — is required at any
     * thread count. A genuinely single-threaded prover is a different upstream build, not this setting.
     *
     * Only the **first** `initialize` per wasm instance takes effect (the module memoizes one-time
     * setup), so changing this after the first proof has no effect until the context is recycled.
     *
     * **1 → 4 was an experiment against two hypotheses, and BOTH are now falsified — keep the 4, but do not
     * reach for this knob to fix a wedge.** It was raised on 2026-08-25 after a proof stopped dead mid-issuance
     * on a pool of exactly one worker, guessing at either a lost wakeup between the driver and its single rayon
     * thread (which pool slack would remove) or stale state in a reused wasm instance (which host-side
     * recycling would). Neither survived 2026-08-26: the wedge reproduces on a pool of 4 and on a
     * freshly-created worker, ~20 attempts, and it reproduces **100%** of the time once
     * [maxRecvDataOnline] is at or above the response's online requirement.
     *
     * What it actually is: an **unconditional self-wake** in the prover future
     * (`crates/tlsn/src/prover.rs`, `cx.waker().wake_by_ref()` on a non-empty buffer that made no progress,
     * with siblings in `wants_write_tls` and `mpc.rs`). That is a *spin*, not a block — and it spins on the
     * driver thread, which is also the thread owning both WebSockets, so nothing dispatches their events while
     * it runs. That is exactly the observed signature: the worker's log stops dead on `ws#2 first recv`, with
     * not even the next 5 s progress tick.
     *
     * **Which is why more threads cannot help.** Rayon slack does nothing when the starving thread is the one
     * that must service the sockets. Other TLSN clients avoid the hang not through their worker pool but by
     * never entering the online-decrypt branch at all (`defer_decryption_from_start: true`),
     * and that flag is not exposed on our vendored artifact's `IssuanceConfig`. See [maxRecvDataOnline].
     *
     * The upload, not the CPU, still dominates wall-clock (38 MB against a 718 B request), so a healthy proof
     * is not expected to get much faster from this either.
     *
     * Remote-config settable, and upstream's own default is `min(hardwareConcurrency, 8)` — so 8 is the top of
     * the useful range if 4 proves insufficient.
     */
    val threadCount: Int = 4,
    /**
     * Request header whose value is redacted from the proof.
     *
     * Universal, not per-read: the prover reveals every request header **in full** unless its name is listed,
     * and its own `HttpRevealPolicy::default` redacts `authorization`/`cookie`/`user-agent`. Supplying a full
     * policy REPLACES that default, so a policy built from this config must not be weaker than it (see
     * `SteamProofReadMapper`, which adds `authorization` alongside this value). The `IEconService` reads send
     * no cookie today — they authenticate by query parameter — so this is purely the guard for a read that
     * later does.
     */
    val provenCookieHeader: String = "cookie",
    /**
     * The offer-axis proven read: `GetTradeOffer` by `{offerId}`, disclosing the offer object.
     *
     * **`get_descriptions=0` is load-bearing, not an optimisation.** Item descriptions cost ~2.3 KB *per
     * item* (measured over 46 real responses: 508-547 B for the offer object alone, 2,821 B with the
     * descriptions of a single item). They scale with the trade, and at roughly **seven items** they cross
     * [maxRecvData] — at which point every proof for that deal fails, and only for multi-item trades, which
     * is the least obvious failure shape available.
     *
     * **The reveal path is inert** while the response is disclosed whole (`ResponseBodyReveal.All`, which
     * carries why). It names the offer **object** rather than its two bound fields because under the prover
     * this was written against a leaf path disclosed the value span without its key, so the verifier received
     * bare byte runs naming no field. Measured on a real body: `response.offer` → 403 B with keys present,
     * against 10 B and 1 B for the leaves inside it. The vendored prover now reveals key paths for leaves too,
     * so the narrowing to leaves is available the day a path reveal can be used at all — and note the object
     * also discloses `accountid_other`, `items_to_give` (with `assetid`, `est_usd`) and the timestamps.
     */
    @JsExport.Ignore
    val offerRead: ProvenRead = ProvenRead(
        serverName = STEAM_WEB_API_HOST,
        pathTemplate = "/IEconService/GetTradeOffer/v1/?tradeofferid=$OFFER_ID&get_descriptions=0&access_token=$TOKEN_PLACEHOLDER",
        revealJsonPaths = listOf("response.offer"),
    ),
    /**
     * The history-axis proven read: `GetTradeStatus` by `{tradeId}`, **not** `GetTradeHistory`.
     *
     * `GetTradeHistory` returns up to 50 rows (51 observed) and the reveal-path syntax has no filters, so the
     * row index is not knowable before the read; `GetTradeStatus` answers for one trade, making
     * `response.trades.0` addressable.
     *
     * ⚠️ **This read has never been executed.** It appears nowhere else in the client — the polling path
     * watches history through [SteamEndpointsConfig.getTradeHistoryPath] — so the method name, the `tradeid`
     * parameter and the response shape are all inferred from a `GetTradeHistory` row rather than observed.
     * Two things to confirm on the first live rollback: that the endpoint accepts a user `access_token` (every
     * other read here does) rather than a publisher key, and that the requested trade is at index 0 — Steam
     * may answer with the rollback partner alongside it, and the order is undocumented. A wrong index is a
     * verifier-side id mismatch, not a proof of the wrong trade, because the revealed row carries its own
     * `tradeid`; a differently shaped response fails the proof outright with `JSON path not found`.
     */
    @JsExport.Ignore
    val historyRead: ProvenRead = ProvenRead(
        serverName = STEAM_WEB_API_HOST,
        pathTemplate = "/IEconService/GetTradeStatus/v1/?tradeid=$TRADE_ID&get_descriptions=0&access_token=$TOKEN_PLACEHOLDER",
        revealJsonPaths = listOf("response.trades.0"),
    ),
    /**
     * PEM roots the prover verifies the **target's** certificate chain against. `null` (default) keeps the
     * prover's bundled Mozilla web-PKI set, which is what production runs on — the wasm treats an absent
     * `rootStore` as exactly that, so the default path is unchanged from every deployment before this field.
     *
     * **A test-fixture affordance, not a deployment knob.** It exists because a harness substrate presents a
     * leaf for `api.steampowered.com` signed by a fixture CA: correct for the notary and the validator, which
     * both pin that root, and unprovable for the prover, which had no way to be told about it — so a proof
     * from the real client against a substrate died `UnknownIssuer`.
     *
     * **Never make this remotely settable.** It is a trust anchor: a published value plus control of the byte
     * pipe would let the prover accept a forged `api.steampowered.com` and attest it. The verifier checks the
     * chain against its own roots, so such a proof is rejected downstream rather than believed — but that is
     * defence in depth, not a reason to hand the anchor to a config channel. A host wanting a fixture CA
     * should supply it at build time.
     *
     * Appended, never inserted — see the note on [acceptedProofTtlMs].
     */
    val rootStorePem: String? = null,
    /**
     * How long a proof the backend has **already verified** keeps satisfying that transition, before the
     * client spends a fresh MPC session on the same one. `0` disables the reuse entirely (prove on every
     * cycle — the behaviour before this field).
     *
     * **The cost this bounds is not theoretical.** A `proof_required` deal whose report the backend keeps
     * refusing with `P2P_PROOF_REQUIRED` is re-planned on every cycle, because the dedup baseline is
     * persisted only for ACCEPTED reports — so the transition stays live and its
     * [com.dmarket.p2p.tracker.engine.ProofIntent] comes back every wake. Observed on dev 2026-08-25 at the
     * payout place (history `3 Complete`, inside Steam's
     * 7-day trade-protection window): `/notary` answered `verified: true`, `/trade-events` answered
     * `P2P_PROOF_REQUIRED` 600 ms later, and the loop rebuilt the identical proof every ~60 s — **17.5 s
     * and 63 MB uploaded to the notary per attempt**, ≈3.8 GB/hour for one deal, with a week of protection
     * window still to run. The loop's refused-proof latch cannot bound that: it keys on a `verified = false`
     * verdict, and here the proof is *accepted*.
     *
     * **Why a TTL and not a permanent latch.** The refusal above cannot be a staleness problem — the proof
     * was 600 ms old — so re-proving does not fix *that* regime and the reuse is pure saving there. But the
     * verifier does bound replay (`provenance.max_attestation_age`), so a regime where the backend becomes
     * ready to accept the report only after the held proof has aged out is reachable: the deal would then
     * never settle behind a permanent latch, with the seller's funds locked. The TTL keeps automatic
     * recovery in both worlds and turns 60 sessions an hour into one.
     *
     * The reuse is **narrow by construction**: it is keyed on the whole
     * [com.dmarket.p2p.tracker.engine.ProofIntent] — deal, axis *and* the exact Steam code — so a later
     * decisive code never inherits an earlier one's verdict, and it is dropped the moment the report is
     * accepted (nothing left to corroborate) or a fresh proof for the same transition is refused (the
     * backend's latest word is "no").
     *
     * **`Int` milliseconds, like every other duration in this file, and not `Long` by choice:** a `Long` in an
     * `@JsExport` class fails the *production* JS compile outright (`Long can't be exported without using of
     * the bigint type`), and that task is not part of `./gradlew check` — so a `Long` here compiles and tests
     * green locally and breaks CI. `Int` ms caps at ~24 days, which is far past any useful reuse window.
     *
     * **LAST in this parameter list, and new fields go after it, never before.** These are `@JsExport` data
     * classes, so the generated constructor is positional and `@JsExport.Ignore` does **not** remove a
     * parameter from it — [offerRead] and [historyRead] occupy slots ahead of this one. A field inserted
     * mid-list silently re-maps a JS host's positional construction: types still match, nothing throws, and
     * the value lands in a neighbouring field.
     */
    val acceptedProofTtlMs: Int = 3_600_000,
    /**
     * Back-pressure for repeated proof-**generation** failures. Its own group rather than four more fields
     * here, for the reason [SteamWriteConfig] is its own class: the policy that reads it
     * ([com.dmarket.p2p.tracker.policy.NotaryProofThrottle]) has no business seeing a notary URL, a trust
     * anchor or a thread count to read four integers — and one positional `@JsExport` slot is thinner than
     * four.
     *
     * Appended last, per the rule on [acceptedProofTtlMs].
     */
    @JsExport.Ignore
    val breaker: NotaryBreakerConfig = NotaryBreakerConfig(),
    /**
     * Response bytes preprocessed for **online** decryption. Everything past it is decrypted *deferred*.
     *
     * **This knob was walked through four values on 2026-08-26, each against a measurement — the history is
     * the sizing guide:**
     *
     * | value | upload/attempt | outcome |
     * |---|---|---|
     * | 32 (pre-423 effective) | 28.6 MB | hard `record layer error: attempted to decrypt more data in the online phase` — build 423 REQUIRES the response head online, 32 cannot hold one record |
     * | 1024 | 38.3 MB | covers the measured proven read; the wedge seen at it is budget-independent |
     * | 2048 (artifact default) | 48.3 MB | same coverage, 2× margin, +10 MB/attempt |
     * | 4096+ | ~68 MB+ | buys nothing: coverage was already proven at 2048 |
     *
     * The requirement is **measured twice over, not estimated**: the record layer's own error at 32 named
     * **802 B** as what the offer response needs online, and the watch pass (which fetches the same endpoint,
     * so its body is in every session log) shows **549 B** of body with `get_descriptions=0` plus Steam's
     * status line and headers. 1024 reaches the reported requirement with a ~25% margin at the cheapest rung;
     * each ~10 MB of upload per KB of budget is the measured MPC preprocessing cost, which is why this stays
     * below the artifact's own default.
     *
     * **The boundary to watch is item count, not tuning:** the offer object grows per asset, so a multi-item
     * deal can push the response past this budget — and on build 423 an exceeded budget is that hard record
     * layer error, for those deals only. Single-item deals are the P2P v1 shape; revisit alongside
     * [maxRecvData]'s own multi-item note if that changes. Remote-settable in both directions.
     *
     * Bounded above by [maxRecvData]: bytes cannot be preprocessed online that the record layer will refuse to
     * receive at all (upstream enforces the same invariant in `mpc-tls`'s config).
     */
    val maxRecvDataOnline: Int = 1_024,
    /**
     * The proven-read registry: every definition beyond the two named fields above, plus which of them the
     * operator has enabled. See [ProvenReadRegistry], including why the two collections live in a class of
     * their own rather than as two parameters here.
     *
     * Appended last, per the rule on [acceptedProofTtlMs]. `@JsExport.Ignore`d for the reason [ProvenRead]
     * itself is: a wrong value does not degrade gracefully — it fails every proof for that read, or proves the
     * wrong document.
     */
    @JsExport.Ignore
    val reads: ProvenReadRegistry = ProvenReadRegistry(),
    /**
     * Required before any `steamcommunity.com` read or write may be proven, and it gates a **measurement**
     * rather than a preference.
     *
     * Neither response-disclosure mode can withhold response headers while staying verifiable:
     * [com.dmarket.p2p.tracker.notary.ResponseBodyReveal.All] reveals one span covering the whole response,
     * headers included, and [com.dmarket.p2p.tracker.notary.ResponseBodyReveal.JsonPaths] needs
     * `revealResponseHeaders = true` to produce the `\r\n\r\n` the verifier splits on. On
     * `api.steampowered.com` that is known-safe because the header set was measured — `server`,
     * `content-type`, `expires`, `date`, `content-length`, and **no `set-cookie`**. The community host has not
     * been measured, and it is the host most likely to answer with `set-cookie`.
     *
     * So the client-side plumbing for community reads is complete and tested, and this flag is the step that
     * records "we looked". Same discipline as `ProvenReadSpec.revealRequestTarget` having no default: a
     * disclosure decision something has to force someone to make consciously.
     *
     * `@JsExport.Ignore`d to match [reads]: a community kind can only be enabled through that field, which JS
     * codegen drops, so a JS host could never make this flag matter. Exporting it would put a control on the
     * audited surface that does nothing there.
     *
     * Appended last, per the rule on [acceptedProofTtlMs].
     */
    @JsExport.Ignore
    val acknowledgeCommunityResponseDisclosure: Boolean = false,
    /**
     * Headroom added to a budget requirement a refused proof reported, in percent — the other half of the
     * sizing decision [maxRecvDataOnline] starts.
     *
     * Lives here rather than as a constant beside its caller so both halves move together: an operator who
     * raises the floor can also widen the headroom without a release, and a measurement run can set `0` to
     * learn the exact stated requirement. 25% against the measured ~10 MB of upload per KB of budget is
     * roughly 2 MB of insurance on an 800 B requirement — cheap next to a second refused MPC session. Why a
     * margin is needed at all is [com.dmarket.p2p.tracker.notary.OnlineBudgetLesson]'s to explain.
     *
     * Appended last, per the rule on [acceptedProofTtlMs].
     */
    val onlineBudgetMarginPercent: Int = 25,
    /**
     * Response **records** preprocessed for online decryption — the record-count sibling of
     * [maxRecvDataOnline], which bounds the same phase in bytes. `null` sends no key at all.
     *
     * **`null` is the deliberate default, and it is not the same kind of default as [maxRecvDataOnline]'s.**
     * That one mirrors a number the artifact documents (2 KiB), so writing it explicitly only makes the
     * effective value visible. This one has **no documented number**: the vendored `IssuanceConfig` says
     * "omitted caps keep the `client_core` contract defaults" and names no figure for the record budgets. A
     * guessed value could therefore be *lower* than the default it replaces — and an online budget that
     * cannot hold the response head is the hard `record layer error: attempted to decrypt more data in the
     * online phase` that [maxRecvDataOnline]'s own table records at 32. So an unset value keeps this path
     * byte-for-byte what every deployment before the field existed had, and setting it is an explicit act.
     *
     * **What it is for, and what is not yet known.** It arrived with the vendored prover at `GIT_SHA
     * 83d77b4`, whose branch is named for the client-side deadlock — the spin documented on [threadCount],
     * where the driver thread starves both WebSockets inside the online-decrypt branch. The byte budget and
     * the record count bound that same branch along different axes: a response split across more records
     * than allowed exceeds the cap even when the byte budget covers every one of them. What upstream does
     * **not** state is the default, nor whether raising this changes the spin — so this knob is exposed to
     * be *measurable* by remote config, not because a value for it is known. Treat a change here the way
     * [maxRecvDataOnline]'s table was built: one value, one live proof, one upload measurement.
     *
     * Appended last, per the rule on [acceptedProofTtlMs].
     */
    val maxRecvRecordsOnline: Int? = null,
    /**
     * Headroom over the *computed* send requirement, in percent — the knob that decides how much of
     * [maxSentData]'s static over-estimate [com.dmarket.p2p.tracker.notary.ProvenSentBudget] is allowed to give
     * back. `0` sizes to the measured requirement exactly; a value large enough to exceed [maxSentData] simply
     * leaves it in force, since the computed budget is clamped there.
     *
     * **15, and the number is the whole argument.** A proven history-axis request is `196 + len(token)` bytes,
     * so the observed 522-char token needs 718 and this admits 826 — still 1.15× the request, but against a
     * requirement that is now *measured per proof* rather than guessed once. That is what makes the smaller
     * factor safe: [maxSentData]'s own 1.43× exists to cover a JWT whose growth "nothing here is notified
     * when", and the IO edge holding the token is notified. What the margin still has to cover is the framing
     * constant — not the variable.
     *
     * **The rollback is one value: publish ≥ 44.** At that point the computed budget clamps at [maxSentData]
     * for every read and the sizing stops mattering, so widening this beats un-shipping it if proofs start
     * failing across the board after a Steam-side change. 44 rather than the 43 the history axis alone would
     * need, because the figure has to hold for the *shortest*-pathed read — the offer axis is 5 B shorter and
     * so keeps sizing one percentage point longer, and a rollback that silently applies to one axis only is
     * worse than none.
     *
     * Appended last, per the rule on [acceptedProofTtlMs].
     */
    val sentBudgetMarginPercent: Int = 15,
) {
    init {
        require(acceptedProofTtlMs >= 0) { "acceptedProofTtlMs must be >= 0, was $acceptedProofTtlMs" }
        require(sentBudgetMarginPercent >= 0) {
            "sentBudgetMarginPercent must be >= 0, was $sentBudgetMarginPercent"
        }
        require(maxRecvDataOnline >= 1) { "maxRecvDataOnline must be >= 1, was $maxRecvDataOnline" }
        // Only a floor, and no upper bound against `maxRecvDataOnline`: a record is at least one byte, so
        // "records <= bytes" looks like an invariant — but it is OUR inference, and upstream states neither it
        // nor the default this value replaces. A guard that rejects a config the prover would have accepted is
        // worse than no guard: it fails the measurement run that would have settled the question.
        require(maxRecvRecordsOnline == null || maxRecvRecordsOnline >= 1) {
            "maxRecvRecordsOnline must be >= 1 when set, was $maxRecvRecordsOnline"
        }
        require(onlineBudgetMarginPercent >= 0) {
            "onlineBudgetMarginPercent must be >= 0, was $onlineBudgetMarginPercent"
        }
        // The online budget is a slice of the receive ceiling, so a value above it is a config that cannot mean
        // what it says: bytes cannot be preprocessed online that the record layer will refuse to receive.
        require(maxRecvDataOnline <= maxRecvData) {
            "maxRecvDataOnline ($maxRecvDataOnline) must be <= maxRecvData ($maxRecvData)"
        }
        // No per-kind validation here on purpose, and both omissions are deliberate:
        //  - "does this enabled kind have a definition" cannot fail — see [provenRead]'s totality;
        //  - "does this write acknowledge its body disclosure" is enforced by `ProvenRead.init`, which refuses
        //    to CONSTRUCT such a read. Re-asserting it would be a guard that can never fire, and the cost is
        //    not the lines: an auditor tracing the write gate would have to prove three checks equivalent
        //    instead of reading the one that is load-bearing.
    }

    /** Which reads may be spent on. Delegated so callers read one name rather than reaching through [reads]. */
    @JsExport.Ignore
    val enabledReads: Set<ProvenReadKind> get() = reads.enabled

    /**
     * The [ProvenRead] for [kind]. **Total** — every kind resolves, so there is no null case and no
     * "is this kind defined" check anywhere in the codebase.
     *
     * That totality is structural rather than asserted: the two named fields cover their own kinds, and
     * `ProvenReadCatalog.of` is an exhaustive `when` over the enum, so adding a [ProvenReadKind] without
     * defining its read is a **compile** error in the catalog rather than a runtime one here. The `error` below
     * is a totality witness the compiler needs, not a guard against a reachable state.
     *
     * The named fields win for their kinds, so a host that overrides [offerRead] or [historyRead] — both
     * positional `@JsExport` constructor slots that predate the catalog — keeps its override.
     */
    @JsExport.Ignore
    fun provenRead(kind: ProvenReadKind): ProvenRead = when (kind) {
        ProvenReadKind.TRADE_OFFER -> offerRead
        ProvenReadKind.TRADE_STATUS -> historyRead
        else -> reads.definition(kind) ?: error("$kind has no definition, which ProvenReadCatalog.of makes unreachable")
    }

    companion object {
        /**
         * Reference cap on proofs in flight per device.
         *
         * Public so a caller needing only this number does not have to build a whole [NotaryConfig] to read
         * it — `NoOpNotaryProver` did exactly that, which meant every service-worker spawn constructed and
         * discarded a config graph (two `ProvenRead`s and a registry) to keep one `Int`.
         */
        const val DEFAULT_MAX_CONCURRENCY: Int = 2

        /**
         * The deployed production notary, and [notaryUrl]'s default.
         *
         * Public for the reason [DEFAULT_MAX_CONCURRENCY] is: a host that has to *report* which notary its
         * loop will attest through — a debug console, a build check — should read it from here rather than
         * keep a second copy of the string that can drift out of agreement with the one actually used.
         */
        const val PRODUCTION_NOTARY_URL: String = "wss://api.dmarket.com/provenance/v1/"

        /** Where every proven read lives today. Named once so the two defaults cannot drift apart. */
        private const val STEAM_WEB_API_HOST = "api.steampowered.com"
    }
}

/**
 * When repeated proof-**generation** failures park the prover, and for how long — the notary counterpart of
 * [SteamWriteConfig]'s cooldown block, with the same field names so the two ladders read alike.
 *
 * Every attempt this bounds is a full MPC session: circuits garbled for [NotaryConfig.maxSentData] plaintext
 * bytes and uploaded, measured at ~30 MB. See [com.dmarket.p2p.tracker.policy.NotaryProofThrottle] for what
 * the numbers mean and why the failure is treated as prover-wide.
 */
@JsExport
data class NotaryBreakerConfig(
    /**
     * Consecutive generation failures that park the prover. `1` parks on the first failure; a value above the
     * number of tracked deals effectively disables the breaker.
     *
     * **`3`, raised from `2` on measurement.** One failure was never evidence — a lost socket or a single
     * notary refusal is worth another attempt. What the dev session on 2026-08-26 added is that the *observed*
     * failure is transient and an **immediate** retry is what clears it: three attempts inside 90s, one wedged
     * on a target response that arrived truncated (1356 B of ~5450 B, no upstream close), and the retry issued
     * 0s later produced a proof in 10.8s. Parking is the wrong response to that shape, so the threshold has to
     * sit above the observed run length of the flake, not at it.
     *
     * At the ~1/3 failure rate seen there, `2` would have parked roughly 11% of pairs and delayed a working
     * deal by 30-60s; `3` cuts that to ~4% while still bounding a genuinely broken prover to three attempts per
     * cooldown instead of one per wake. Remote-settable, and the direction that hurts is UP: a value at or above
     * the tracked-deal count never parks at all, which is the ~30 MB-per-wake drain this exists to stop.
     */
    val breakerThreshold: Int = 3,
    /**
     * Base of the exponential cooldown: attempt *n* draws from `[c/2, c]` where `c = min(base·2^(n-1), max)`.
     *
     * **Equal jitter, so there is no separate minimum knob.** A guaranteed floor of `base/2` falls out of the
     * arithmetic, where full jitter would have needed one supplied — and a supplied floor takes a share of the
     * probability mass with it: at `min = base/2`, half of every first-rung draw lands on exactly the floor,
     * which is the lockstep the jitter exists to break. The notary has no minimum wait of its own to honour
     * (unlike [SteamWriteConfig], where the floor says "do not touch Steam again for N minutes"), so nothing
     * is lost by deriving it.
     *
     * 60s is one heartbeat cadence — long enough that the next wake does not immediately re-spend ~30 MB on a
     * prover that just failed twice, short enough that a transient outage costs one cycle rather than a deal's
     * deadline. First rung therefore parks for 30-60s.
     */
    val cooldownBaseMs: Int = 60_000,
    /** Longest cooldown the escalation can reach, so a permanently broken prover still retries eventually. */
    val cooldownMaxMs: Int = 1_800_000,
    /**
     * A failure faster than this never engaged the prover, so it does not count toward [breakerThreshold].
     *
     * **What this excludes, and why it must be excluded.** The breaker exists to bound a *cost*: one attempt
     * is an MPC session — ~30 MB uploaded and tens of seconds of held cycle. A failure that returns in
     * milliseconds spent neither. Observed on dev 2026-08-26: immediately after an extension reload the core
     * still held a proving context whose offscreen realm was gone, and two proofs failed in **7 ms and 15 ms**
     * with `prover worker errored: undefined`. That armed the cooldown and suppressed the other nine deals of
     * that cycle — a 40 s park bought by two failures that cost nothing and proved nothing about the prover.
     *
     * Not parking on them is safe in the direction that matters: if every attempt really does fail instantly,
     * retrying every cycle costs milliseconds, which is precisely the bill the breaker is not for. The wedge
     * it *is* for takes 30-75 s and 47 MB, so the two are three orders of magnitude apart and 1 s separates
     * them with enormous margin.
     */
    val countedFailureMinMs: Int = 1_000,
) {
    init {
        require(breakerThreshold >= 1) { "breakerThreshold must be >= 1, was $breakerThreshold" }
        require(countedFailureMinMs >= 0) { "countedFailureMinMs must be >= 0, was $countedFailureMinMs" }
        require(cooldownBaseMs >= 1) { "cooldownBaseMs must be >= 1, was $cooldownBaseMs" }
        // Against the base, which under equal jitter is the only relationship that can be wrong: a maximum
        // below the base means rung one is already capped, so the ladder has nowhere to climb. (There is no
        // separate minimum to compare against any more — it is derived as half the capped draw.)
        require(cooldownMaxMs >= cooldownBaseMs) {
            "cooldownMaxMs ($cooldownMaxMs) must be >= cooldownBaseMs ($cooldownBaseMs)"
        }
    }

    @JsExport.Ignore val cooldownBase: Duration get() = cooldownBaseMs.milliseconds

    @JsExport.Ignore val cooldownMax: Duration get() = cooldownMaxMs.milliseconds
}

/**
 * Steam read/session endpoints and the `IEconService` query-parameter names. Third-party-dependent:
 * Steam owns these shapes, so centralising them lets a host hot-patch a moved endpoint without a
 * client release.
 *
 * **Only paths and parameter names are tunable — the hosts are compiled in.** The four base URLs are
 * validated against [SteamHosts] on construction (and so on every `copy()`), because the Steam JWT
 * rides on [steamApiBaseUrl] as a query parameter and the session-transfer secrets are POSTed to the
 * other three: without this, one wrong string in a host- or remotely-supplied config would be enough
 * to send device-only credentials off Steam. A rejected base throws rather than falling back to the
 * default — a silent substitution would be a quiet security downgrade.
 */
@JsExport
data class SteamEndpointsConfig(
    val steamApiBaseUrl: String = "https://api.steampowered.com",
    /**
     * Single-offer read by `tradeofferid` — used when exactly one offer is tracked, and as the
     * per-offer fallback for any id missing from the [getTradeOffersPath] list.
     */
    val getTradeOfferPath: String = "/IEconService/GetTradeOffer/v1/",
    /**
     * Account-wide offer list, **both directions** ([paramGetSentOffers] + [paramGetReceivedOffers]) — used
     * when more than [bulkOfferThreshold] offers are tracked; any tracked id it omits is then read
     * individually via [getTradeOfferPath].
     */
    val getTradeOffersPath: String = "/IEconService/GetTradeOffers/v1/",
    val getTradeHistoryPath: String = "/IEconService/GetTradeHistory/v1/",
    /** Public-profile summaries (nickname + avatars); up to 100 comma-separated [paramSteamIds] per call. */
    val getPlayerSummariesPath: String = "/ISteamUser/GetPlayerSummaries/v2/",
    /** Steam account level for a single [paramSteamId]; empty response for a private profile. */
    val getSteamLevelPath: String = "/IPlayerService/GetSteamLevel/v1/",
    val loginBaseUrl: String = "https://login.steampowered.com",
    val communityBaseUrl: String = "https://steamcommunity.com",
    val storeBaseUrl: String = "https://store.steampowered.com",
    val historyMaxTrades: Int = 50,
    /**
     * Switch point between the two offer-read strategies. When **more than** this many offers are
     * tracked, one account-wide `GetTradeOffers` list call (plus a per-offer `GetTradeOffer` fallback
     * for any id the list omits) beats issuing N per-offer calls and risking Steam rate limits. The
     * default of `1` means: exactly one tracked offer → single `GetTradeOffer`; two or more → the
     * batch list + fallback. A host may raise it to prefer per-offer reads for small counts.
     */
    val bulkOfferThreshold: Int = 1,
    val paramAccessToken: String = "access_token",
    val paramTradeOfferId: String = "tradeofferid",
    val paramGetSentOffers: String = "get_sent_offers",
    val paramActiveOnly: String = "active_only",
    val paramGetDescriptions: String = "get_descriptions",
    val paramMaxTrades: String = "max_trades",
    /** Comma-separated steamID64 list for `GetPlayerSummaries` (≤100 ids). */
    val paramSteamIds: String = "steamids",
    /** Single steamID64 for `GetSteamLevel`. */
    val paramSteamId: String = "steamid",
    /**
     * Items requested per own-inventory page (community `/inventory` `count`). Steam truncates above its
     * own cap and signals it with `more_items`, which the reader follows — this only decides how many
     * requests a full scan costs.
     *
     * Appended at the END of this parameter list on purpose: this is an `@JsExport` data class, so a
     * mid-list insertion would break positional construction for both TS and Kotlin consumers.
     */
    val inventoryPageCount: Int = 2000,
    /**
     * Hard ceiling on requests per own-inventory scan. Exhausting it reports `scan_complete=false` rather
     * than a silently truncated snapshot, so the backend skips the stale-diff cancel. Kept small
     * deliberately: each page is a sequential request inside an MV3 worker that can be torn down, and
     * `/inventory/` is Steam's most throttled surface.
     */
    val inventoryMaxPages: Int = 5,
    /**
     * Notification stream, read **only** to name who reversed a trade on a history status-12 report (see
     * [com.dmarket.p2p.tracker.port.steam.SteamNotificationReader]). Never polled on a normal tick.
     */
    val getSteamNotificationsPath: String = "/ISteamNotificationService/GetSteamNotifications/v1/",
    /** Mandatory for the notification read — reading a notification sets `read`, and omitting this empties the response. */
    val paramIncludeRead: String = "include_read",
    /** Mandatory for the notification read — deleting a notification sets `hidden`; see [paramIncludeRead]. */
    val paramIncludeHidden: String = "include_hidden",
    val paramLanguage: String = "language",
    /**
     * Asks the bulk list for **received** offers as well as sent ones ([paramGetSentOffers]). A tracked
     * deal can be one this account is *buying* — the backend serves the deal-watch list to both sides of a
     * trade — and a purchase's offer is a received one, absent from a sent-only list. Without it every
     * buyer-side offer falls through to a per-offer [getTradeOfferPath] read, which is the N-extra-calls
     * case the bulk list exists to avoid.
     *
     * **Declared last, away from its sibling, on purpose.** This class is `@JsExport`ed, and the generated
     * `copy()` a JS host calls is **positional** — inserting a parameter mid-list silently shifts every
     * argument after it onto the wrong field. New parameters are therefore appended, never inserted.
     */
    val paramGetReceivedOffers: String = "get_received_offers",
    // NB: `GetTradeStatus` — the history axis's proven notary read — is deliberately NOT a field here. It is
    // never called on the polling path (`TrackedDeal.watches` maps a `GetTradeStatus` watch onto
    // [getTradeHistoryPath] like any other history watch), and the read it serves is spelled out once, in
    // `NotaryConfig.historyReadPathTemplate`. A field here would be a second spelling with no reader.
    //
    // Consequence worth knowing: on the history axis the POLLED read and the PROVEN read are different
    // endpoints — the reported code comes from a `GetTradeHistory` row, the proven one from `GetTradeStatus`.
    // They share a row shape, but they are two reads at two moments, so a code that advances in between is
    // reported as one value and proven as another.
) {
    init {
        SteamHosts.requireAllowed(steamApiBaseUrl, SteamHosts.API, "steamApiBaseUrl")
        SteamHosts.requireAllowed(loginBaseUrl, SteamHosts.WEB, "loginBaseUrl")
        SteamHosts.requireAllowed(communityBaseUrl, SteamHosts.WEB, "communityBaseUrl")
        SteamHosts.requireAllowed(storeBaseUrl, SteamHosts.WEB, "storeBaseUrl")
        // The bases alone are not enough: every read below is `steamApiBaseUrl + <path>`, and a path can
        // move the effective host (`"@evil.example.com/"` turns the checked base into userinfo). These
        // six are the paths that carry the Steam JWT as a query parameter, so each is checked as composed.
        listOf(
            "getTradeOfferPath" to getTradeOfferPath,
            "getTradeOffersPath" to getTradeOffersPath,
            "getTradeHistoryPath" to getTradeHistoryPath,
            "getPlayerSummariesPath" to getPlayerSummariesPath,
            "getSteamLevelPath" to getSteamLevelPath,
            "getSteamNotificationsPath" to getSteamNotificationsPath,
        ).forEach { (field, path) ->
            SteamHosts.requirePathKeepsHost(steamApiBaseUrl, path, SteamHosts.API, field)
        }
    }
}

/**
 * Tuning for the Steam user-profile service ([com.dmarket.p2p.tracker.port.steam.SteamProfileReader]):
 * the in-memory cache TTL, the parallel-level-fetch concurrency cap, the summaries batch size (Steam's
 * hard limit is 100 ids/request), the per-request timeout, and the 429 retry/backoff envelope.
 *
 * Durations are stored as `Int` milliseconds for a JS-friendly export shape (the [CadenceConfig]
 * convention); internal consumers read the `Duration` accessors.
 */
@JsExport
data class SteamProfileConfig(
    /** How long a fetched [com.dmarket.p2p.tracker.model.steam.SteamProfile] stays cached (default 5 min). */
    val cacheTtlMs: Int = 300_000,
    /** Max in-flight `GetSteamLevel` requests during a batch (one call per id, so cap the fan-out). */
    val maxConcurrency: Int = 5,
    /** Ids per `GetPlayerSummaries` call; Steam rejects more than 100. */
    val batchSize: Int = 100,
    /** Per-request timeout for the profile client (default 10s). */
    val requestTimeoutMs: Int = 10_000,
    /** Total attempts for a rate-limited (429) call before giving up. */
    val maxRetries: Int = 3,
    /** Base backoff before the first retry; doubled each attempt, capped at [retryMaxDelayMs], plus jitter. */
    val retryBaseDelayMs: Int = 500,
    /** Ceiling for the exponential backoff delay. */
    val retryMaxDelayMs: Int = 8_000,
) {
    init {
        require(batchSize in 1..100) { "batchSize must be in 1..100, was $batchSize" }
        require(maxConcurrency >= 1) { "maxConcurrency must be >= 1, was $maxConcurrency" }
        require(maxRetries >= 1) { "maxRetries must be >= 1, was $maxRetries" }
    }

    @JsExport.Ignore val cacheTtl: Duration get() = cacheTtlMs.milliseconds

    @JsExport.Ignore val retryBaseDelay: Duration get() = retryBaseDelayMs.milliseconds

    @JsExport.Ignore val retryMaxDelay: Duration get() = retryMaxDelayMs.milliseconds
}

/**
 * Regex patterns and cookie/attribute names the credential scrapers depend on. The token/steamID
 * regexes are the most fragile third-party surface (they break whenever Steam reshapes its Community
 * HTML), so being able to override them remotely is the highest-value tuning here.
 */
@JsExport
data class SteamScrapeConfig(
    val tokenRegex: String = """data-loyalty_webapi_token="&quot;([a-zA-Z0-9_.\-]+)&quot;""",
    val steamIdRegex: String = """g_steamID\s*=\s*"(\d+)"""",
    val steamSessionCookieName: String = "steamLoginSecure",
    val steamSessionIdCookieName: String = "sessionid",
)

/**
 * Marketplace (DMarket) session-credential locators the token store and the refresh call depend on.
 *
 * Fields are **append-only**: each host applies remote-config overrides through the generated positional
 * `copy()`, so inserting or reordering a field silently shifts every override that follows it.
 */
@JsExport
data class MarketplaceScrapeConfig(
    /** Cookie holding the DMarket bearer (access) token. */
    val cookieName: String = "dm-trade-token",
    /**
     * The FE origin the session cookies live on — the `url` every cookie read/write is scoped to. **Not** the
     * refresh endpoint; see [tokenRefreshPath] / [tokenRefreshUrl]. Owned by the host's environment (and its
     * debug endpoint switcher), never by remote config.
     */
    val refreshUrl: String = "https://dmarket.com/",
    /** Cookie holding the durable DMarket refresh token. */
    val refreshCookieName: String = "dm-trade-refresh-token",
    /**
     * Path of the token-refresh endpoint, appended to the marketplace API base URL.
     *
     * Remote-overridable (a path is the safe class of remote hotfix; a host is not), but implementations
     * must validate it: a value like `//evil.example/x` concatenated onto a base yields a protocol-relative
     * URL pointing off-host, and this request body carries the refresh token.
     */
    val tokenRefreshPath: String = "/marketplace-api/v1/refresh-token",
    /**
     * Absolute override for the refresh endpoint, for an environment where it is not served from the API
     * base (a dev deployment that proxies it through the site origin, for instance).
     *
     * `null` selects `apiBaseUrl + tokenRefreshPath`. Callers **must** reject a value whose origin is
     * neither the API base nor [refreshUrl]: this is the one request that carries the ~30-day refresh
     * credential, so it may only ever be sent to an already-trusted origin. Host-environment owned, never
     * remote-config owned.
     */
    val tokenRefreshUrl: String? = null,
    /**
     * Skip the token refresh while another writer of the shared cookie jar is plausibly active — i.e. while
     * a dmarket.com tab is open, whose SPA refreshes the session itself.
     *
     * **Off by default, and it does NOT open a tab** — it only asks whether one is already there. The API refresh
     * is the mechanism (a business requirement: never open a tab to refresh), and making the mechanism
     * conditional on the user's browsing would reintroduce the dependency this design removed. It also defers
     * only *proactive* refreshes; a forced one always proceeds, since the site's own refresh is driven by its
     * response interceptor and an open-but-idle tab never refreshes at all.
     *
     * The backend is confirmed to void the predecessor refresh token on rotation, so refreshing behind a live tab
     * CAN end that tab's session — but only a tab that has itself refreshed at least once, because only then does
     * it hold a copy in memory instead of re-reading the cookie. The real fixes are a frontend cookie re-read and
     * a backend grace window, both requested. Turn this on as containment if site-logout reports appear first.
     */
    val deferRefreshWhileSiteTabOpen: Boolean = false,
)

/** Per-game third-party constants for CS2. */
@JsExport
data class GameConfig(val cs2InventoryContextId: Int = 2)

/**
 * The deal-keyed write-claim guard that keeps a non-idempotent Steam write (`create_offer` /
 * `cancel_offer`) from being performed twice for one deal, however many times the host or the backend
 * asks. See `com.dmarket.p2p.tracker.engine.DealWriteGuard`.
 */
@JsExport
data class WriteClaimConfig(
    /**
     * How long a claim may stand before it is treated as abandoned and the write is allowed again — the
     * anti-wedge backstop for a claim whose releasing signal never arrived (a crash between the Steam
     * write and its bookkeeping, or a deal the backend stopped reporting).
     *
     * Must stay comfortably **above** the backend's directive-lease TTL (~2-5 min) so a claim can never
     * expire while the backend still considers the write outstanding; the default leaves a wide margin.
     */
    val claimTtlMs: Int = 900_000,
) {
    @JsExport.Ignore val claimTtl: Duration get() = claimTtlMs.milliseconds
}

/**
 * How hard this device may push Steam's `create_offer` write surface, and how long it stays off that
 * surface after Steam refuses. Steam caps the **outstanding trade offers per partner** (5 at the time
 * of writing) and answers an over-cap create with `HTTP 500 {"strError":"You have sent too many trade
 * offers, or have too many outstanding trade offers with <partner>. …"}`; keep pushing past that and
 * Steam's edge starts refusing the POST outright, which risks a temporary trade block on the account.
 *
 * The backend legitimately leases far more `create_offer` directives at once than Steam will accept
 * (dozens, for a single partner), so these limits are the client's own back-pressure: it executes what
 * it can, defers the rest, and lets the backend re-lease them on a later heartbeat.
 *
 * See `com.dmarket.p2p.tracker.policy.CreateChainPlanner` (which creates run, grouped per partner) and
 * `com.dmarket.p2p.tracker.policy.SteamWriteThrottle` (how long a refusal parks the surface). The
 * **cancel** surface is deliberately not throttled: a cancel *frees* a partner's quota and is the way
 * out of the block.
 */
@JsExport
data class SteamWriteConfig(
    /**
     * Creates attempted per partner per cycle. Defaults to Steam's own per-partner outstanding-offer
     * limit: past it every further create for that partner is refused anyway.
     */
    val maxCreatesPerPartnerPerCycle: Int = 5,
    /**
     * Ceiling on creates attempted per cycle across all partners, spent round-robin over the per-partner
     * chains so one partner with dozens of leased creates cannot starve the others.
     */
    val maxCreatesPerCycle: Int = 20,
    /** How many per-partner chains may run at once. */
    val maxConcurrentChains: Int = 4,
    /**
     * Consecutive rate-limited/transport create failures (across partners) that park the **whole** create
     * surface, not just the partners that failed — the backstop for Steam refusing POSTs wholesale rather
     * than per partner.
     */
    val globalBreakerThreshold: Int = 3,
    /** Shortest cooldown a refusal can produce; the floor under the jittered backoff below. */
    val cooldownMinMs: Int = 120_000,
    /** Base of the exponential cooldown: attempt *n* draws from `[0, base·2^(n-1)]`, floored by the minimum. */
    val cooldownBaseMs: Int = 120_000,
    /** Longest cooldown the escalation can reach. */
    val cooldownMaxMs: Int = 1_800_000,
    /**
     * Case-insensitive substrings that identify a Steam **rate-limit** refusal in a failed create's error
     * text. Third-party wording, so it is host-suppliable for the same reason the scraping regexes are:
     * Steam can reword it at any time and a missed marker silently disables the throttle.
     *
     * This is the set that decides *whether* a refusal is a rate limit at all. Which one it is — a
     * counterparty cap, the account-wide cap, or request throttling — is then read off
     * [counterpartyLimitMarker] and [requestRateLimitMarkers].
     */
    val rateLimitMarkers: List<String> = listOf(
        "too many trade offers",
        "too many outstanding trade offers",
        "sent too many",
        "http 429",
    ),
    /**
     * Case-insensitive substrings that identify a **transport** failure (the request never reached Steam,
     * or its answer never came back). These do not park a single partner — nothing says the partner is
     * over quota — but they do count towards [globalBreakerThreshold], because a create surface refusing
     * connections outright is exactly how Steam's edge answers a client that pushed too hard.
     */
    val transportFailureMarkers: List<String> = listOf(
        "failed to fetch",
        "networkerror",
        "timeout",
        "timed out",
        "connection",
    ),
    /**
     * Whether a create that was **deferred** (never sent to Steam) is answered on `/trade-actions`.
     *
     * Off by default, pending agreement with the backend: nothing was written, so there is no outcome to
     * report, and the directive lease is simply left to expire and be re-leased. Reporting `failed` for
     * every deferred create instead would mean one POST per deferred directive per heartbeat — dozens,
     * for exactly the workload that makes deferring necessary.
     */
    val reportThrottledWrites: Boolean = false,
    /**
     * The one phrase that separates Steam's **two** offer limits, which are otherwise worded the same: the
     * per-counterparty refusal names the other party ("…too many outstanding trade offers *with* `<persona>`…"),
     * the account-wide one does not. Steam says nothing else that distinguishes them.
     *
     * Load-bearing beyond the message shown: a counterparty cap parks that partner, an account-wide one parks
     * the whole create surface (see `com.dmarket.p2p.tracker.policy.SteamCreateFailureCause`). Blank disables
     * the distinction, and every rate-limited refusal then reads as the account-wide cap — the safe direction
     * to fail, since parking too much only slows this device down.
     *
     * Appended, never inserted: hosts apply remote-config overrides through the generated positional `copy()`.
     */
    val counterpartyLimitMarker: String = "outstanding trade offers with",
    /**
     * Refines [rateLimitMarkers]: the subset of them that means Steam throttled the **request rate** rather
     * than reporting a cap on open offers. Only consulted for a text [rateLimitMarkers] already matched, so a
     * marker listed here and nowhere else has no effect.
     *
     * Split out because the two remedies differ and only one of them is honest: an offer cap is cleared by
     * cancelling offers, a `429` only by waiting. Both park the surface identically, so this changes what the
     * user is told, not what the throttle does.
     */
    val requestRateLimitMarkers: List<String> = listOf("http 429"),
) {
    init {
        require(maxCreatesPerPartnerPerCycle >= 1) {
            "maxCreatesPerPartnerPerCycle must be >= 1, was $maxCreatesPerPartnerPerCycle"
        }
        require(maxCreatesPerCycle >= 1) { "maxCreatesPerCycle must be >= 1, was $maxCreatesPerCycle" }
        require(maxConcurrentChains >= 1) { "maxConcurrentChains must be >= 1, was $maxConcurrentChains" }
        require(globalBreakerThreshold >= 1) { "globalBreakerThreshold must be >= 1, was $globalBreakerThreshold" }
        require(cooldownMinMs >= 0) { "cooldownMinMs must be >= 0, was $cooldownMinMs" }
        require(cooldownBaseMs >= 1) { "cooldownBaseMs must be >= 1, was $cooldownBaseMs" }
        require(cooldownMaxMs >= cooldownMinMs) {
            "cooldownMaxMs ($cooldownMaxMs) must be >= cooldownMinMs ($cooldownMinMs)"
        }
    }

    @JsExport.Ignore val cooldownMin: Duration get() = cooldownMinMs.milliseconds

    @JsExport.Ignore val cooldownBase: Duration get() = cooldownBaseMs.milliseconds

    @JsExport.Ignore val cooldownMax: Duration get() = cooldownMaxMs.milliseconds
}
