@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.tracker.debug

import com.dmarket.p2p.tracker.adapter.host.NoOpNetworkObserver
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver
import com.dmarket.p2p.tracker.adapter.platformScheduler
import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageCredentialVault
import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageDeviceIdStore
import com.dmarket.p2p.tracker.client.createMarketplaceHttpClient
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.marketplace.CredentialMarketplaceAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.marketplace.createBrowserMarketplaceCredentials
import com.dmarket.p2p.tracker.client.steam.FetchSteamOfferCreator
import com.dmarket.p2p.tracker.client.steam.FetchSteamWebSessionGateway
import com.dmarket.p2p.tracker.client.steam.KtorSteamInventoryReader
import com.dmarket.p2p.tracker.client.steam.KtorSteamReadClient
import com.dmarket.p2p.tracker.client.steam.KtorSteamSessionScraper
import com.dmarket.p2p.tracker.config.MarketplaceScrapeConfig
import com.dmarket.p2p.tracker.config.SteamEndpointsConfig
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.steam.DefaultSteamSessionRefresher
import com.dmarket.p2p.tracker.credential.steam.SteamCredentialProvider
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.TickOutcome
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamSessionCookie
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.net.redactedSummary
import com.dmarket.p2p.tracker.notary.ProvenReadBinding
import com.dmarket.p2p.tracker.notary.ProvenReadKind
import com.dmarket.p2p.tracker.notary.ProvenSentBudget
import com.dmarket.p2p.tracker.notary.SteamProofReadMapper
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamInventoryReader
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway
import com.dmarket.p2p.tracker.runtime.TradeTrackerCore
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.promise
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport
import kotlin.js.Promise

/**
 * **Dev-only** JS surface for the Chrome debug console (`tools/debug-extension/`). Two surfaces:
 *  - the always-on **self-driving** session ([startDebugSession] → [DebugSessionHandle], with
 *    [createTradeInSession] / [getSessionLog]) — the primary E2E entry the service worker boots on;
 *  - one-shot **diagnostic** probes on [DebugSession] (token scrape, session refresh, vault, raw Steam
 *    reads, heartbeat, each of the four C1 report requests — [DebugSession.reportTradeStatus],
 *    [DebugSession.reportDirective], [DebugSession.reportInventory], [DebugSession.submitProof] — and
 *    [runDealCycle]) — manual checks the production [com.dmarket.p2p.tracker.runtime.JsApi]
 *    hides behind the audit boundary.
 *
 * Lives ONLY in the unpublished `:debug-harness` module; it does **not** widen `:core`'s audited
 * `@JsExport` surface. Every method returns a JSON **string**.
 *
 * **Secrets are redacted by default.** The diagnostic probes print a token's length, its decoded claims
 * and a [SecretRedaction.fingerprint] — never the token itself — unless the session was created through
 * [createDebugSessionRevealingSecrets], which nothing automated calls. See `SecretRedaction`.
 */
@JsExport
fun debugHarnessVersion(): String = TradeTrackerCore.VERSION

/** The default entry: diagnostics come back with secrets redacted. */
@JsExport
@Suppress("NON_EXPORTABLE_TYPE")
fun createDebugSession(baseUrl: String): DebugSession = DebugSession(baseUrl, revealSecrets = false)

/**
 * The opt-in entry: diagnostics print raw tokens and cookie values. (The one-shot session keeps no
 * session log — for verbatim request/response capture use `startDebugSession(..., revealSecrets = true)`,
 * which is a different entry point and a different object.)
 *
 * For hands-on local diagnosis of a credential problem the claims cannot explain — nothing automated
 * should call this, and its output must not be pasted anywhere. A separate factory rather than a
 * boolean flag on purpose: it cannot be reached by a stray truthy argument from JS, and it is
 * greppable.
 */
@JsExport
@Suppress("NON_EXPORTABLE_TYPE")
fun createDebugSessionRevealingSecrets(baseUrl: String): DebugSession = DebugSession(baseUrl, revealSecrets = true)

@JsExport
class DebugSession internal constructor(private val baseUrl: String, private val revealSecrets: Boolean) {

    private val clock: Clock = SystemClock()
    private val vault: CredentialVault = WebExtStorageCredentialVault()

    private val steamEndpoints: SteamEndpointsConfig = SteamEndpointsConfig()
    private val steamHttp =
        createSteamHttpClient(secretParamNames = NetworkRedaction.plusSecretParam(SteamEndpointsConfig().paramAccessToken))

    private val scraper: SteamSessionScraper = KtorSteamSessionScraper(httpClient = steamHttp)
    private val gateway: SteamWebSessionGateway = FetchSteamWebSessionGateway(httpClient = steamHttp)

    private val steamReader: SteamReadClient = KtorSteamReadClient(httpClient = steamHttp, endpoints = steamEndpoints)
    private val inventoryReader: SteamInventoryReader = KtorSteamInventoryReader(
        httpClient = steamHttp,
        pageCount = steamEndpoints.inventoryPageCount,
        maxPages = steamEndpoints.inventoryMaxPages,
    )
    private val scrapeConfig = MarketplaceScrapeConfig()

    private val marketplaceProvider: MarketplaceCredentialProvider = createBrowserMarketplaceCredentials(
        baseUrl = baseUrl,
        config = TrackerConfig.defaults().copy(marketplaceScrape = scrapeConfig),
        clock = clock,
        networkObserver = NoOpNetworkObserver,
    )

    private val marketplace: MarketplaceClient = KtorMarketplaceClient(
        httpClient = createMarketplaceHttpClient(),
        baseUrl = baseUrl,
        authenticator = CredentialMarketplaceAuthenticator(marketplaceProvider),
    )

    /** The platform-free C1 report probes (commonMain), driven here through the live client. */
    private val probes = C1ReportProbes(marketplace)

    private val notary: NotaryProver = NoOpNotaryProver

    private val refresher: SteamSessionRefresher = DefaultSteamSessionRefresher(
        gateway = gateway,
        clock = clock,
        loginBaseUrl = steamEndpoints.loginBaseUrl,
        communityBaseUrl = steamEndpoints.communityBaseUrl,
        storeBaseUrl = steamEndpoints.storeBaseUrl,
    )

    private val provider = SteamCredentialProvider(vault = vault, scraper = scraper, clock = clock, sessionRefresher = refresher)

    /** The live deal loop, wired from the same components so vault state stays consistent across ops. */
    @OptIn(DelicateCoroutinesApi::class)
    private val loop: TradeTrackerLoop by lazy {
        TradeTrackerCore().createLoop(
            config = LoopConfig(TradeTrackerCore.VERSION, RuntimeSurface.WebChrome, TrackerMode.Background),
            marketplace = marketplace,
            steamReader = steamReader,
            scraper = scraper,
            scheduler = platformScheduler(GlobalScope, TrackerMode.Background),
            deviceId = WebExtStorageDeviceIdStore(),
            vault = vault,
            clock = clock,
            notary = notary,
            offerCreator = FetchSteamOfferCreator(httpClient = steamHttp),
            inventoryReader = inventoryReader,
            sessionRefresher = refresher,
            marketplaceCredentials = marketplaceProvider,
        )
    }

    /** Echoes which DMarket + Steam targets this session is wired for. */
    fun describe(): Promise<String> = result {
        buildJsonObject {
            put("ok", true)
            put("baseUrl", baseUrl)
            put("steamApiBaseUrl", steamEndpoints.steamApiBaseUrl)
            put("harnessVersion", TradeTrackerCore.VERSION)
        }
    }

    // ---- Token / vault / session (live browser actuals) --------------------------------------------

    fun scrapeCredential(): Promise<String> = result {
        val cred = scraper.scrape()
        buildJsonObject {
            put("ok", true)
            put("loggedIn", cred != null)
            if (cred != null) putCredential(cred)
        }
    }

    fun refreshSession(): Promise<String> = result {
        val before = gateway.readCookie("steamcommunity.com", "steamLoginSecure")
        val outcome = refresher.refreshSession()
        val after = gateway.readCookie("steamcommunity.com", "steamLoginSecure")
        buildJsonObject {
            put("ok", true)
            put("outcome", outcome.name)
            put("changed", before?.value != after?.value)
        }
    }

    fun readVaultCredential(): Promise<String> = result {
        val cred = vault.readSteamCredential()
        buildJsonObject {
            put("ok", true)
            put("present", cred != null)
            if (cred != null) putCredential(cred)
        }
    }

    fun acquireCredential(): Promise<String> = result {
        val cred = provider.current()
        buildJsonObject {
            put("ok", cred != null)
            if (cred != null) putCredential(cred)
        }
    }

    fun clearVaultCredential(): Promise<String> = result {
        vault.clearSteamCredential()
        buildJsonObject { put("ok", true) }
    }

    fun scrapeMarketplaceToken(): Promise<String> = result {
        val cred = marketplaceProvider.current()
        buildJsonObject {
            put("ok", cred != null)
            put("present", cred != null)
            put("tokenLength", cred?.token?.length)
        }
    }

    /**
     * Forces one DMarket token refresh through the real provider and reports what moved in the cookie jar.
     *
     * `forceRefresh()` is the same entry point the 401 path uses, so this probe exercises the shipped
     * mechanism rather than a parallel one. `refreshed=false` with `loggedOut=true` means the server refused
     * the refresh token (interactive login); `refreshed=false` with `loggedOut=false` means a transient
     * failure, a rate limit, or the refusal latch — all of which deliberately spend no request.
     */
    fun refreshMarketplaceToken(): Promise<String> = result {
        val before = gateway.readCookie("dmarket.com", "dm-trade-token")
        val credential = marketplaceProvider.forceRefresh()
        val after = gateway.readCookie("dmarket.com", "dm-trade-token")
        buildJsonObject {
            put("ok", true)
            put("refreshed", credential != null)
            put("loggedOut", marketplaceProvider.lastRefreshFailedLoggedOut)
            put("beforeLength", before?.value?.length)
            put("afterLength", after?.value?.length)
            put("changed", before?.value != after?.value)
        }
    }

    fun inspectSessionCookie(): Promise<String> = result {
        val cookie = gateway.readCookie("steamcommunity.com", "steamLoginSecure")
        val parsed = cookie?.value?.let { SteamSessionCookie.parse(it) }
        buildJsonObject {
            put("ok", true)
            put("present", cookie != null)
            put("parsedSteamId", parsed?.steamId?.value)
            put("parsedExpiresAtIso", parsed?.expiresAt?.toString())
            put("fresh", parsed?.isFresh(clock.now()))
        }
    }

    fun inspectMarketplaceCookie(): Promise<String> = result {
        val cookie = gateway.readCookie("dmarket.com", "dm-trade-token")
        buildJsonObject {
            put("ok", true)
            put("present", cookie != null)
            // `value` appears only in a reveal-secrets session; `valueFingerprint` is enough to tell
            // "the cookie changed" / "both places see the same one", which is what this probe is for.
            putSecret("value", cookie?.value, revealSecrets)
        }
    }

    // ---- Steam reads (live) ------------------------------------------------------------------------

    fun offerStatuses(steamOfferId: String): Promise<String> = result {
        val cred = provider.current()
        buildJsonObject {
            put("ok", true)
            put("loggedIn", cred != null)
            if (cred == null) return@buildJsonObject
            val snapshots = steamReader.offerSnapshots(cred, setOf(OfferId(steamOfferId)))
            put("count", snapshots.size)
            putJsonArray("offers") {
                for ((id, snapshot) in snapshots) {
                    addJsonObject {
                        put("offerId", id.value)
                        put("offerState", snapshot.state)
                        // Steam's own transfer id, set on acceptance — the key the history axis correlates on,
                        // so seeing it here is how a "why is the history axis silent?" question gets answered.
                        put("tradeId", snapshot.tradeId?.value)
                    }
                }
            }
        }
    }

    fun recentTransfers(maxTrades: Int): Promise<String> = result {
        val cred = provider.current()
        buildJsonObject {
            put("ok", true)
            put("loggedIn", cred != null)
            if (cred == null) return@buildJsonObject
            val transfers = steamReader.recentTransfers(cred, maxTrades)
            put("count", transfers.size)
            putJsonArray("transfers") {
                for (t in transfers) {
                    addJsonObject {
                        put("partnerSteamId", t.partnerSteamId?.value)
                        put("tradeStatus", t.status)
                        put("assetCount", t.assetIds.size)
                        // The fields reversal attribution depends on. Both are unverified against a live
                        // payload: a null modifiedAt on a real status-12 row is the evidence that Steam does
                        // not send `time_mod` there, which is what the exact-equality rule needs.
                        put("tradeId", t.tradeId?.value)
                        put("modifiedAt", t.modifiedAt?.toString())
                        put("rollbackTradeId", t.rollbackTradeId?.value)
                    }
                }
            }
        }
    }

    /**
     * Scan the seller's own inventory and report whether the read was a **complete** enumeration.
     *
     * The one thing `MockEngine` tests cannot cover: whether a real community `/inventory` body decodes at
     * all, what type Steam actually sends for `success`, and whether paging kicks in. `complete=false` on a
     * real account is the signal to inspect — it means the tracker would report `scan_complete=false` and
     * the backend would skip the stale-diff cancel.
     */
    fun ownInventory(): Promise<String> = result {
        val cred = provider.current()
        buildJsonObject {
            put("ok", true)
            put("loggedIn", cred != null)
            if (cred == null) return@buildJsonObject
            val scan = inventoryReader.scanOwnInventory(cred)
            put("complete", scan.complete)
            put("assetCount", scan.assetIds.size)
            put("pageCount", steamEndpoints.inventoryPageCount)
            put("maxPages", steamEndpoints.inventoryMaxPages)
            // A sample only — a full inventory dump would be thousands of ids in the console.
            putJsonArray("sampleAssetIds") { for (id in scan.assetIds.take(10)) add(id.value) }
        }
    }

    /**
     * Build the proven-read **spec** for any Steam endpoint, without proving anything.
     *
     * This is how the eight endpoints beyond the two trade axes get exercised. It is deliberately spec-only:
     * everything that decides whether a proof can work at all — the filled path, the headers, the body, the
     * disclosure policy, the caps — is pure and inspectable here, while actually running MPC needs a notary
     * URL, a cross-origin-isolated document and ~10 MB of WASM.
     *
     * It also answers the two questions a live rollout starts with. Does the URL this would prove match the URL
     * the polling path reads? And for a community kind, what does the response disclosure cost — the reason
     * `acknowledgeCommunityResponseDisclosure` exists.
     *
     * Credentials are never filled: the returned path and header values still carry their `{token}` / `{cookie}`
     * / `{sessionId}` slots, which is exactly what makes this safe to print to a console.
     */
    fun provenReadSpec(kind: String): Promise<String> = result {
        val parsed = ProvenReadKind.entries.firstOrNull { it.name == kind }
        val notaryConfig = TrackerConfig.defaults().notary.copy(acknowledgeCommunityResponseDisclosure = true)
        buildJsonObject {
            if (parsed == null) {
                put("ok", false)
                put("error", "unknown kind '$kind'")
                putJsonArray("knownKinds") { for (entry in ProvenReadKind.entries) add(entry.name) }
                return@buildJsonObject
            }
            val cred = provider.current()
            val read = notaryConfig.provenRead(parsed)
            val spec = SteamProofReadMapper(notaryConfig).readSpec(
                kind = parsed,
                binding = ProvenReadBinding(
                    dealId = DealId("debug-deal"),
                    steamOfferId = OfferId("debug-offer"),
                    assetId = AssetId("debug-asset"),
                    tradeId = TradeId("debug-trade"),
                    partnerSteamId = SteamId("76561198000000002"),
                    tradeToken = "debug-token",
                    assetsToGive = listOf(AssetId("debug-asset")),
                ),
                subjectSteamId = cred?.subjectSteamId ?: SteamId("76561198000000001"),
                adapter = Cs2GameAdapter(),
            )
            put("ok", true)
            put("kind", parsed.name)
            put("dealScoped", parsed.dealScoped)
            // The fact that decides whether enabling this is a config change or a routing change: a write is
            // PERFORMED by the prover, so it replaces the client's own write rather than witnessing it.
            put("isWrite", read.isWrite)
            put("enabled", parsed in TrackerConfig.defaults().notary.enabledReads)
            put("serverName", spec.serverName)
            put("method", spec.method)
            put("path", spec.path)
            put("revealRequestTarget", spec.revealRequestTarget)
            put("revealResponseHeaders", spec.revealResponseHeaders)
            put("responseBodyReveal", spec.responseBodyReveal.toString())
            put("bodyBytes", spec.body?.length ?: 0)
            // The EFFECTIVE send budget, sized the way `WasmNotaryProver` sizes it — not the configured
            // ceiling. Reporting the ceiling here would put the console one number behind the prover the day
            // `ProvenSentBudget` gives some of it back, which is the class of disagreement this whole
            // describe-the-issuance command exists to rule out. The token length travels with it, because a
            // budget is meaningless without saying what it was sized against — and without it the reader
            // cannot tell a live measurement from the fallback.
            val tokenLength = cred?.token?.length ?: ProvenSentBudget.OBSERVED_TOKEN_LENGTH
            put("maxSentData", ProvenSentBudget.sentBudget(spec, notaryConfig, tokenLength))
            put("maxSentDataTokenLength", tokenLength)
            put("maxSentDataTokenIsLive", cred != null)
            put("maxRecvData", spec.maxRecvDataOverride ?: notaryConfig.maxRecvData)
            putJsonArray("sendHeaders") { for (header in spec.sendHeaders) add(header.name) }
            putJsonArray("redactedHeaders") { for (name in spec.redactRequestHeaderValues) add(name) }
        }
    }

    // ---- C1 deal-loop commands (live) --------------------------------------------------------------

    /** Send a single heartbeat and return the `active_tracking[]` + `directives[]` counts. */
    fun heartbeat(foreground: Boolean = true): Promise<String> = result {
        val cred = provider.current() ?: error("not logged in (no Steam credential)")
        val deviceId = WebExtStorageDeviceIdStore()
        val request = HeartbeatRequest(
            clientVersion = TradeTrackerCore.VERSION,
            platform = RuntimeSurface.WebChrome.platformWireName,
            foreground = foreground,
            steamId = cred.subjectSteamId,
            deviceId = deviceId.current(),
        )
        val response = marketplace.heartbeat(request)
        buildJsonObject {
            put("ok", true)
            put("activeTracking", response.activeTracking.size)
            put("directives", response.directives.size)
            put("ttlSeconds", response.ttlSeconds)
            putJsonArray("tracking") {
                for (t in response.activeTracking) {
                    addJsonObject {
                        put("dealId", t.dealId.value)
                        put("steamOfferId", t.steamOfferId?.value)
                        put("proofRequired", t.proofRequired)
                        put("role", t.role.wireName)
                        put("watch", t.watch.map { it.name }.toString())
                        // DMA-280's freshness mark, and the trade it names. Shown because a demand the client
                        // is not answering is diagnosed from its INPUTS — "did the mark arrive at all, and for
                        // which trade" — and the verdict alone cannot answer either.
                        put("steamTradeId", t.steamTradeId?.value)
                        put("proveAfter", t.proveAfter?.toString())
                    }
                }
            }
            putJsonArray("directiveActions") {
                for (d in response.directives) {
                    addJsonObject {
                        put("directiveId", d.directiveId.value)
                        put("action", d.action.name)
                        put("dealId", d.dealId?.value)
                    }
                }
            }
        }
    }

    // ---- C1 report requests, one-shot (live) -------------------------------------------------------
    //
    // The JS-callable face of [C1ReportProbes] (commonMain): the four write/report endpoints the loop
    // normally drives on its own cadence, each issued as one request through THIS client so a
    // backend↔client wire mismatch surfaces as an empty value in the returned JSON — the real
    // deserializer is what read the response. The mapping, the strictness and the JSON shape all live
    // in the common class, which is unit-tested; these wrappers only adapt to @JsExport-able types
    // (Array instead of List) and bridge to a Promise.
    //
    // Rejected input (an unknown action/status/source, a malformed id, a non-ISO time) throws in the
    // common class and arrives here as the in-band `{ok:false, error}` every caller already handles.

    /** One-shot `POST /trade-events` (C1 ReportTradeStatus). */
    fun reportTradeStatus(dealId: String, source: String, steamStatusCode: Int, clientTimeIso: String): Promise<String> =
        result { probes.reportTradeStatus(dealId, source, steamStatusCode, clientTimeIso) }

    /** One-shot `POST /trade-events` carrying one report per entry of [dealIds] — the batched wire shape. */
    fun reportTradeStatusBatch(dealIds: Array<String>, source: String, steamStatusCode: Int, clientTimeIso: String): Promise<String> =
        result { probes.reportTradeStatusBatch(dealIds.toList(), source, steamStatusCode, clientTimeIso) }

    /** One-shot `POST /trade-actions` (C1 ReportDirective). [error] is the detail sent on `status=failed`. */
    fun reportDirective(
        directiveId: String,
        dealId: String?,
        action: String,
        status: String,
        steamOfferId: String?,
        error: String? = null,
    ): Promise<String> = result { probes.reportDirective(directiveId, dealId, action, status, steamOfferId, error) }

    /** One-shot `POST /inventory` (C1 ReportInventory). */
    fun reportInventory(
        directiveId: String,
        steamId: String,
        deviceId: String,
        scanComplete: Boolean,
        presentAssetIds: Array<String>,
        contextId: Int,
    ): Promise<String> = result {
        probes.reportInventory(directiveId, steamId, deviceId, scanComplete, presentAssetIds.toList(), contextId)
    }

    /** One-shot `POST /notary` (C1 SubmitProof; deferred stub). */
    fun submitProof(dealId: String, proofPayload: String): Promise<String> = result { probes.submitProof(dealId, proofPayload) }

    fun getDeal(dealId: String): Promise<String> = result {
        val deal = marketplace.getDeal(DealId(dealId))
        buildJsonObject {
            put("ok", true)
            putDeal(deal)
        }
    }

    fun acceptDeal(dealId: String): Promise<String> = result {
        val r = marketplace.acceptDeal(DealId(dealId))
        buildJsonObject {
            put("ok", true)
            put("state", r.state.name)
            put("applied", r.applied)
            put("reasonCode", r.reasonCode)
        }
    }

    /** One full LIVE cycle against the real backend + Steam (heartbeat → directives → watch+report). */
    fun runDealCycle(): Promise<String> = result {
        val outcome = loop.runOnce()
        buildJsonObject {
            put("ok", true)
            put("needsReLogin", loop.needsReLogin)
            put("needsMarketplaceReLogin", loop.needsMarketplaceReLogin)
            put("marketplaceConnectionMissing", loop.marketplaceConnectionMissing)
            put("steamSessionMissing", loop.steamSessionMissing)
            put("blockingReason", loop.blockingState.name)
            putTickOutcome(outcome)
            put("nextWakeMs", loop.nextWakeDelay().inWholeMilliseconds)
        }
    }

    // ---- JSON helpers ------------------------------------------------------------------------------

    /** Delegates to the shared, unit-tested projection — redacted unless this session reveals secrets. */
    private fun JsonObjectBuilder.putCredential(cred: SteamCredential) = putSteamCredential(cred, clock.now(), revealSecrets)

    private fun JsonObjectBuilder.putDeal(deal: Deal) {
        put("dealId", deal.dealId.value)
        put("state", deal.state.name)
        put("steamOfferId", deal.steamOfferId?.value)
        put("trustedAcceptUri", deal.trustedAcceptUri)
        put("sellerAccountId", deal.sellerAccountId.value)
        put("buyerAccountId", deal.buyerAccountId.value)
    }

    private fun JsonObjectBuilder.putTickOutcome(outcome: TickOutcome) {
        put("directivesExecuted", outcome.directivesExecuted)
        put("reportsSent", outcome.reportsSent)
        put("proofsSubmitted", outcome.proofsSubmitted)
        put("watching", outcome.watching)
    }

    // @JsExport cannot export suspend functions; bridge to a JS Promise like the production JsApi.
    @OptIn(DelicateCoroutinesApi::class)
    private fun result(block: suspend () -> JsonObject): Promise<String> = GlobalScope.promise {
        try {
            block().toString()
        } catch (t: Throwable) {
            buildJsonObject {
                put("ok", false)
                put("error", t.redactedSummary())
            }.toString()
        }
    }
}

// ================================================================================================
// Self-driving debug session — the primary E2E surface the rewritten extension boots on SW start.
// ================================================================================================

/**
 * Starts a self-driving debug tracker for the integration test (real DMarket backend + real FE) and
 * returns an opaque [DebugSessionHandle]. Zero-config entry the service worker calls at boot.
 *
 * Steam is **live**: reads/writes/inventory hit real Steam, so a `create` POSTs a real offer (stops at
 * `NeedsConfirmation`). Steam **identity** is scraped from the LIVE logged-in `steamcommunity.com`
 * session (the real backend's `steam_id == account.linked_steam_id` check needs a real id); the Steam
 * credential never crosses this boundary.
 *
 * @param logCallback invoked with one session-log entry JSON string per network exchange / lifecycle
 *   event (the SW stamps `seq`/`ts` and persists). May be omitted (entries still buffer in memory).
 * @param marketplaceFeUrl FE origin the DMarket `dm-trade-token` cookie is read + refreshed from
 *   (e.g. the dev FE). Blank keeps the default `https://dmarket.com/`. Distinct from [baseUrl] (API).
 * @param revealSecrets opt in to capturing request/response bodies, URLs and headers **verbatim** in
 *   the session log. Default `false` keeps the audited redaction, which is what the log is persisted
 *   with; turn it on only for hands-on local diagnosis.
 */
@JsExport
@Suppress("NON_EXPORTABLE_TYPE")
fun startDebugSession(
    baseUrl: String = TrackerConfig.DEFAULT_DMARKET_BASE_URL,
    logCallback: ((String) -> Unit)? = null,
    marketplaceFeUrl: String = "",
    revealSecrets: Boolean = false,
): DebugSessionHandle {
    val log = SessionLogBuffer(maxEntries = 500)
    val sink = LogSink(log, logCallback)
    val driver = startDebugTracker(
        baseUrl = baseUrl,
        networkObserver = JsNetworkObserver(sink, reveal = revealSecrets),
        eventObserver = JsEventObserver(sink),
        marketplaceFeUrl = marketplaceFeUrl,
    )
    return DebugSessionHandle(driver, log)
}

/** Opaque handle to a running debug session. Never exposes the Steam credential. */
@JsExport
class DebugSessionHandle internal constructor(private val driver: DebugDriver, private val log: SessionLogBuffer) {
    internal fun stopInternal() = driver.stop()

    internal fun logSnapshot(): String = log.snapshotJson()

    internal fun clearLog() = log.clear()

    /** Run one heartbeat cycle now (used by the fallback path when the FE has no directive_id). */
    internal fun nudge() = driver.nudge()

    /**
     * The FE "create trade" trigger: create the Steam offer against live Steam. Called by the SW when the
     * dmarket.com content script relays a validated `window.postMessage`. @JsExport can't export suspend
     * funs, so it bridges to a Promise.
     */
    @OptIn(DelicateCoroutinesApi::class)
    internal fun createTrade(
        directiveId: String,
        dealId: String,
        partnerSteamId: String,
        assetIds: Array<String>,
        tradeToken: String?,
        linkedSteamId: String? = null,
    ): Promise<String> = GlobalScope.promise {
        if (directiveId.isBlank()) {
            return@promise buildJsonObject {
                put("ok", false)
                put("error", "missing directive_id (FE must supply the backend's create_offer directive id)")
            }.toString()
        }
        if (dealId.isBlank()) {
            return@promise buildJsonObject {
                put("ok", false)
                put("error", "missing deal_id (the /trade-actions outcome report requires it — the backend rejects without it)")
            }.toString()
        }
        val draft = TradeDraft(
            partner = SteamId(partnerSteamId),
            assetsToGive = assetIds.filter { it.isNotBlank() }.map(::AssetId),
            tradeToken = tradeToken?.takeIf { it.isNotBlank() },
        )
        val result = driver.loop.createTrade(
            DirectiveId(directiveId),
            DealId(dealId),
            draft,
            linkedSteamId = linkedSteamId?.takeIf { it.isNotBlank() }?.let(::SteamId),
        )
        // Heartbeat now so the loop picks up active_tracking for the new offer and starts watching Steam
        // status (→ /trade-events), instead of waiting up to the next scheduled alarm.
        driver.nudge()
        buildJsonObject {
            put(
                "ok",
                result is CreateOfferResult.NeedsConfirmation ||
                    result is CreateOfferResult.Created ||
                    result is CreateOfferResult.AlreadyCreated,
            )
            when (result) {
                is CreateOfferResult.NeedsConfirmation -> {
                    put("status", "needs_confirmation")
                    put("steamOfferId", result.offerId.value)
                }
                is CreateOfferResult.Created -> {
                    put("status", "created")
                    put("steamOfferId", result.offerId.value)
                }
                is CreateOfferResult.Failed -> {
                    put("status", "failed")
                    put("error", result.error)
                    // Mirrors the shipped facade (`runtime.createTrade`): the coded cause is what a host
                    // branches on, `error` is Steam's free-form text kept for the console.
                    put("cause", result.cause.name)
                }
                // Blocked before any Steam write: the DMarket-linked Steam account ≠ the logged-in session.
                is CreateOfferResult.AccountMismatch -> {
                    put("status", "account_mismatch")
                    put("linkedSteamId", result.linkedSteamId.value)
                    put("tokenSteamId", result.tokenSteamId.value)
                }
                // Suppressed duplicate: this device already created the offer for the deal — the first
                // result is replayed instead of a second live Steam offer being written.
                is CreateOfferResult.AlreadyCreated -> {
                    put("status", "needs_confirmation")
                    put("steamOfferId", result.offerId.value)
                    put("duplicate", true)
                }
                // Suppressed duplicate that landed while the first create was still running.
                is CreateOfferResult.CreateInFlight -> {
                    put("status", "create_in_flight")
                    put("duplicate", true)
                }
                // Deferred before any Steam write: the create surface is backing off after a Steam refusal.
                is CreateOfferResult.Throttled -> {
                    put("status", "throttled")
                    put("scope", result.scope.name.lowercase())
                    put("retryAfterSeconds", result.retryAfterSeconds)
                }
            }
        }.toString()
    }
}

/** Stops the session (clears the alarm, detaches listeners, cancels the scope). */
@JsExport
fun stopDebugSession(handle: DebugSessionHandle) = handle.stopInternal()

/**
 * Runs one heartbeat cycle now. The fallback for a `CreateTrade` postMessage that carries **no**
 * `directive_id`: instead of the FE-fast-path create, let the regular heartbeat/directive flow run (the
 * backend leases the `create_offer` directive and the loop executes it on its own cadence).
 */
@JsExport
fun nudgeSession(handle: DebugSessionHandle) = handle.nudge()

/**
 * The FE-triggered "create trade" entry — the SW calls this when the dmarket.com content script relays a
 * validated `window.postMessage`. Creates the Steam offer (against live Steam) and returns the outcome JSON.
 */
@JsExport
@Suppress("NON_EXPORTABLE_TYPE")
fun createTradeInSession(
    handle: DebugSessionHandle,
    directiveId: String,
    dealId: String,
    partnerSteamId: String,
    assetIds: Array<String>,
    tradeToken: String? = null,
    linkedSteamId: String? = null,
): Promise<String> = handle.createTrade(directiveId, dealId, partnerSteamId, assetIds, tradeToken, linkedSteamId)

/** Returns the in-memory session-log mirror as a JSON array string. */
@JsExport
fun getSessionLog(handle: DebugSessionHandle): String = handle.logSnapshot()

/** Clears the in-memory session-log mirror. */
@JsExport
fun clearSessionLog(handle: DebugSessionHandle) = handle.clearLog()
