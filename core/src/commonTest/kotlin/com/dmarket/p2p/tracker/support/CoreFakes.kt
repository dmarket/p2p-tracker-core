package com.dmarket.p2p.tracker.support

import com.dmarket.p2p.tracker.client.marketplace.MarketplaceAuthenticator
import com.dmarket.p2p.tracker.model.AccountId
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.PushSignal
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.DealActionResult
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAck
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.InventoryAck
import com.dmarket.p2p.tracker.model.marketplace.InventoryReport
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceCredential
import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import com.dmarket.p2p.tracker.model.marketplace.Money
import com.dmarket.p2p.tracker.model.marketplace.P2PDealState
import com.dmarket.p2p.tracker.model.marketplace.ProofResult
import com.dmarket.p2p.tracker.model.marketplace.ProofSubmission
import com.dmarket.p2p.tracker.model.marketplace.StoredMarketplaceTokens
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusReport
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusResult
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import com.dmarket.p2p.tracker.model.steam.InventoryScan
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamOfferSnapshot
import com.dmarket.p2p.tracker.model.steam.SteamTransfer
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.notary.ProvenReadBinding
import com.dmarket.p2p.tracker.notary.ProvenReadKind
import com.dmarket.p2p.tracker.notary.defaultProvenReadKind
import com.dmarket.p2p.tracker.port.SessionRefreshOutcome
import com.dmarket.p2p.tracker.port.TransientSessionException
import com.dmarket.p2p.tracker.port.WebCookie
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.host.PushChannel
import com.dmarket.p2p.tracker.port.host.Scheduler
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenRefreshClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenStore
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamInventoryReader
import com.dmarket.p2p.tracker.port.steam.SteamNotificationReader
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import com.dmarket.p2p.tracker.port.steam.SteamReadClient
import com.dmarket.p2p.tracker.port.steam.SteamSessionRefresher
import com.dmarket.p2p.tracker.port.steam.SteamSessionScraper
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionGateway
import com.dmarket.p2p.tracker.port.steam.SteamWebSessionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.time.Duration
import kotlin.time.Instant

// ---- FakeMarketplaceClient ---------------------------------------------------------------------

/**
 * In-memory [MarketplaceClient] for the C1 heartbeat/directives contract.
 * [heartbeatResponse] is returned on each `heartbeat()` call; outcomes are recorded for assertions.
 */
class FakeMarketplaceClient(
    var heartbeatResponse: HeartbeatResponse = HeartbeatResponse(ttlSeconds = 60),
    private val dealForGet: Deal? = null,
    private val acceptApplied: Boolean = true,
    /**
     * Answers for successive `getDeal` calls, the last one repeating — for a test that needs the deal's join
     * key to *change* between reads (a re-keyed asset id). Empty falls back to [dealForGet].
     */
    private val dealsForGet: List<Deal> = emptyList(),
) : MarketplaceClient {
    val heartbeatsSent = mutableListOf<HeartbeatRequest>()
    val tradeStatusReports = mutableListOf<TradeStatusReport>()
    val proofsSubmitted = mutableListOf<ProofSubmission>()
    val directiveOutcomes = mutableListOf<DirectiveOutcome>()
    val inventoryReports = mutableListOf<InventoryReport>()
    val acceptedDeals = mutableListOf<DealId>()
    var getDealCalls = 0

    /**
     * How many `/trade-actions` **requests** were made, as opposed to how many outcomes they carried
     * ([directiveOutcomes]). The endpoint takes a batch, so the two differ — and the gap is the whole point of
     * batching, so a test can assert on it.
     */
    var directiveCalls = 0

    /** Failure knobs — defaults preserve the happy path. */
    var heartbeatThrows: Boolean = false

    /** When set, `heartbeat()` throws this exact throwable (e.g. `MarketplaceUnauthorizedException`). */
    var heartbeatThrowable: Throwable? = null
    var reportDirectiveThrows: Boolean = false
    var directiveAccepted: Boolean = true
    var directiveRejectReason: String? = null

    /**
     * Directives to answer a batch **without** a result for — the "backend acked some of what we sent" case,
     * which a per-outcome endpoint could not produce.
     */
    var directiveIdsWithoutAck: Set<DirectiveId> = emptySet()
    var tradeStatusAccepted: Boolean = true
    var proofVerified: Boolean = true

    /** The backend's rejection string, as `/notary` really returns alongside `verified: false`. */
    var proofReason: String? = null
    var submitProofThrows: Boolean = false
    var reportTradeStatusThrows: Boolean = false
    var getDealThrows: Boolean = false

    /**
     * Shapes the `results[]` a `/trade-events` batch answers with, so a test can reproduce what a real
     * backend can do and the client must survive: fewer results than reports, a different order, or one
     * result per deal for a batch carrying both axes of that deal. `null` = one accepted result per report.
     */
    var tradeStatusResults: ((List<TradeStatusReport>) -> List<TradeStatusResult>)? = null

    override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse {
        heartbeatThrowable?.let { throw it }
        if (heartbeatThrows) error("simulated /heartbeat failure")
        heartbeatsSent += request
        return heartbeatResponse
    }

    override suspend fun reportTradeStatus(reports: List<TradeStatusReport>): List<TradeStatusResult> {
        tradeStatusReports += reports
        if (reportTradeStatusThrows) error("simulated /trade-events failure")
        return tradeStatusResults?.invoke(reports)
            ?: reports.map { TradeStatusResult(dealId = it.dealId, accepted = tradeStatusAccepted, source = it.source) }
    }

    override suspend fun submitProof(proof: ProofSubmission): ProofResult {
        proofsSubmitted += proof
        if (submitProofThrows) error("simulated /notary failure")
        return ProofResult(dealId = proof.dealId, verified = proofVerified, reason = proofReason)
    }

    override suspend fun reportDirectives(outcomes: List<DirectiveOutcome>): List<DirectiveAck> {
        if (reportDirectiveThrows) error("simulated /trade-actions failure")
        if (outcomes.isEmpty()) return emptyList()
        directiveCalls++
        directiveOutcomes += outcomes
        return outcomes
            .filterNot { it.directiveId in directiveIdsWithoutAck }
            .map { DirectiveAck(directiveId = it.directiveId, accepted = directiveAccepted, reason = directiveRejectReason) }
    }

    override suspend fun reportInventory(report: InventoryReport): InventoryAck {
        inventoryReports += report
        return InventoryAck(cancelledOfferIds = emptyList(), accepted = true)
    }

    override suspend fun acceptDeal(id: DealId): DealActionResult {
        acceptedDeals += id
        return DealActionResult(state = P2PDealState.COMMITTED, applied = acceptApplied)
    }

    override suspend fun getDeal(id: DealId): Deal {
        getDealCalls++
        if (getDealThrows) error("simulated /p2p/deals/{id} failure")
        if (dealsForGet.isNotEmpty()) return dealsForGet.getOrElse(getDealCalls - 1) { dealsForGet.last() }
        return dealForGet ?: error("no deal configured for $id")
    }
}

// ---- FakeSteamReadClient -----------------------------------------------------------------------

class FakeSteamReadClient(initialOffers: Map<OfferId, Int> = emptyMap(), initialTransfers: List<SteamTransfer> = emptyList()) :
    SteamReadClient {
    var offers: Map<OfferId, Int> = initialOffers
    var transfers: List<SteamTransfer> = initialTransfers
    var offerStatusesCalls = 0
    var recentTransfersCalls = 0
    var offerStatusesThrows = false
    var recentTransfersThrows = false

    /** Overrides the thrown message, so a test can assert what the loop does with a secret-bearing one. */
    var offerStatusesThrowMessage: String? = null

    var lastOfferIdsQueried: Set<OfferId> = emptySet()

    /**
     * The `tradeid` Steam attaches to an accepted offer, per offer id. Set it to exercise the primary history
     * correlation; leaving it empty models an offer Steam has not (or no longer) paired with a transfer, which
     * is what sends the loop down the asset-ref fallback.
     */
    var offerTradeIds: Map<OfferId, TradeId> = emptyMap()

    override suspend fun offerSnapshots(credential: SteamCredential, offerIds: Set<OfferId>): Map<OfferId, SteamOfferSnapshot> {
        offerStatusesCalls++
        if (offerStatusesThrows) error(offerStatusesThrowMessage ?: "simulated offerSnapshots failure")
        lastOfferIdsQueried = offerIds
        val selected = if (offerIds.isEmpty()) offers else offers.filterKeys { it in offerIds }
        return selected.mapValues { (id, state) -> SteamOfferSnapshot(state = state, tradeId = offerTradeIds[id]) }
    }

    override suspend fun recentTransfers(credential: SteamCredential, maxTrades: Int): List<SteamTransfer> {
        recentTransfersCalls++
        if (recentTransfersThrows) error("simulated recentTransfers failure")
        return transfers
    }
}

// ---- FakeSteamNotificationReader ---------------------------------------------------------------

/** [initiator] is a `var` so a test can let attribution resolve on a LATER tick, as Steam's does. */
class FakeSteamNotificationReader(var initiator: SteamId? = null) : SteamNotificationReader {
    var calls = 0
    var lastCounterparty: SteamId? = null
    var lastModifiedAt: Instant? = null

    override suspend fun reversalInitiator(credential: SteamCredential, counterparty: SteamId?, modifiedAt: Instant?): SteamId? {
        calls++
        lastCounterparty = counterparty
        lastModifiedAt = modifiedAt
        // Mirror the port contract: an unknown counterparty or time is undecidable, so no real
        // implementation can resolve an actor from it. A fake that answered anyway would let a caller
        // depend on behaviour production cannot deliver.
        if (counterparty == null || modifiedAt == null) return null
        return initiator
    }
}

// ---- FakeSteamInventoryReader ------------------------------------------------------------------

class FakeSteamInventoryReader(
    private val assets: Set<AssetId> = emptySet(),
    private val complete: Boolean = true,
    private val throws: Boolean = false,
) : SteamInventoryReader {
    var calls = 0

    override suspend fun scanOwnInventory(credential: SteamCredential): InventoryScan {
        calls++
        if (throws) error("simulated inventory failure")
        return InventoryScan(assets, complete = complete)
    }
}

// ---- FakeNotaryProver --------------------------------------------------------------------------

class FakeNotaryProver(private val payload: String = "proof-bytes") : NotaryProver {
    override val maxConcurrency: Int = 2
    val proven = mutableListOf<Pair<DealId, TradeStatusSource>>()

    /**
     * Which endpoint each proof actually asked for, so a test can assert the axis→endpoint mapping rather than
     * trusting it. [proven] records the axis the loop asked about; this records the read that witnesses it, and
     * the two are deliberately different on the history axis (`GetTradeStatus`, not `GetTradeHistory`).
     */
    val provenKinds = mutableListOf<Pair<DealId, ProvenReadKind>>()

    /**
     * The online-decryption budget each proof was bound with, so a test can assert what a refusal taught
     * actually reaches the next attempt rather than trusting the plumbing. `null` = the configured default.
     */
    val provenOnlineBudgets = mutableListOf<Int?>()

    /**
     * The trade id each proof was bound to. Exists for DMA-280: a demanded re-attestation must bind the id the
     * BACKEND named on the watch entry, not one derived from a local correlation — which can match a different
     * trade of the same asset after a rollback. Without this a test can only assert that *a* proof happened.
     */
    val provenTradeIds = mutableListOf<TradeId?>()

    /**
     * Fail *generation* rather than delivery — the WASM prover's own failure mode (a refused notary
     * handshake, a torn-down offscreen document, an MPC abort), which the marketplace fake cannot stand in
     * for because it never gets called.
     */
    var proveThrows: Throwable? = null

    /**
     * Called on every attempt, before the verdict. Exists so a test can make proving cost *time* — an MPC
     * session is tens of seconds when it works and minutes when it wedges, and the loop's proving budget is
     * about exactly that. Typically `{ clock.advance(...) }`.
     */
    var onProve: (() -> Unit)? = null

    override suspend fun proveRead(binding: ProvenReadBinding, kind: ProvenReadKind, credential: SteamCredential): ProofSubmission {
        provenKinds += binding.dealId to kind
        onProve?.invoke()
        proveThrows?.let { throw it }
        return ProofSubmission(dealId = binding.dealId, proofPayload = payload)
    }

    /**
     * Overridden purely to keep recording the **axis**, which the port's default would not surface: it maps
     * straight to a kind, and a test asserting "the loop proved the history axis of this deal" should not have
     * to reverse that mapping.
     */
    override suspend fun proveTransition(
        binding: ProvenReadBinding,
        source: TradeStatusSource,
        credential: SteamCredential,
    ): ProofSubmission {
        proven += binding.dealId to source
        provenOnlineBudgets += binding.minOnlineBudget
        provenTradeIds += binding.tradeId
        return proveRead(binding, source.defaultProvenReadKind, credential)
    }
}

// ---- FakeDeviceIdStore -------------------------------------------------------------------------

class FakeDeviceIdStore(id: String = "test-device-1") : DeviceIdStore {
    private val deviceId = DeviceId(id)
    override suspend fun current(): DeviceId = deviceId
}

// ---- FakeCredentialVault -----------------------------------------------------------------------

class FakeCredentialVault(steamCredential: SteamCredential? = null) : CredentialVault {
    private var steam: SteamCredential? = steamCredential

    override suspend fun readSteamCredential(): SteamCredential? = steam
    override suspend fun writeSteamCredential(credential: SteamCredential) {
        steam = credential
    }
    override suspend fun clearSteamCredential() {
        steam = null
    }
}

/** Returns a [SteamCredential] with a far-future expiry for use in tests. */
fun fakeSteamCredential(token: String = "ya29.test-steam-token", steamId: String = "76561198000000001"): SteamCredential = SteamCredential(
    token = token,
    subjectSteamId = SteamId(steamId),
    expiresAt = Instant.fromEpochMilliseconds(1_781_697_600_000L), // FakeClock epoch + 24 h
)

/** Returns a [Deal] for [FakeMarketplaceClient.getDeal]; only [dealId]/[assetId] usually matter to tests. */
fun fakeDeal(dealId: String = "deal-1", assetId: String = "asset-1"): Deal = Deal(
    dealId = DealId(dealId),
    state = P2PDealState.AWAITING_TRADE,
    buyerAccountId = AccountId("buyer-acct-1"),
    sellerAccountId = AccountId("seller-acct-1"),
    offerId = OfferId("offer-1"),
    assetId = AssetId(assetId),
    price = Money("USD", 1000L),
    createdAt = Instant.fromEpochMilliseconds(1_781_611_200_000L),
    updatedAt = Instant.fromEpochMilliseconds(1_781_611_200_000L),
)

// ---- FakeSteamSessionScraper -------------------------------------------------------------------

class FakeSteamSessionScraper(var result: SteamCredential? = fakeSteamCredential()) : SteamSessionScraper {
    var scrapeCalls = 0

    override suspend fun scrape(): SteamCredential? {
        scrapeCalls++
        return result
    }
}

// ---- Marketplace credential fakes ------------------------------------------------------------

/** Returns a [MarketplaceCredential] with a far-future expiry for use in tests. */
fun fakeMarketplaceCredential(token: String = "fake-jwt-token"): MarketplaceCredential = MarketplaceCredential(
    token = token,
    expiresAt = Instant.fromEpochMilliseconds(1_781_697_600_000L), // FakeClock epoch + 24 h
)

/**
 * A [MarketplaceCredentialProvider] whose answer can be flipped mid-test.
 *
 * Replaces the old scraper/refresher pair for loop-level tests: the loop only ever asks this port for a
 * credential, so a fake at this altitude is both simpler and closer to what a mobile host actually supplies.
 */
class FakeMarketplaceCredentialProvider(var result: MarketplaceCredential? = fakeMarketplaceCredential(), loggedOut: Boolean = false) :
    MarketplaceCredentialProvider {
    var currentCalls = 0
    var forceRefreshCalls = 0

    override var lastRefreshFailedLoggedOut: Boolean = loggedOut
        private set

    override suspend fun current(): MarketplaceCredential? {
        currentCalls++
        lastRefreshFailedLoggedOut = result == null
        return result
    }

    override suspend fun forceRefresh(): MarketplaceCredential? {
        forceRefreshCalls++
        lastRefreshFailedLoggedOut = result == null
        return result
    }
}

/** In-memory [MarketplaceTokenStore]; [write] can be told to lose the race or to be blind. */
class FakeMarketplaceTokenStore(
    var stored: StoredMarketplaceTokens? = null,
    var writeOutcome: MarketplaceTokenStore.WriteOutcome = MarketplaceTokenStore.WriteOutcome.WRITTEN,
    var otherActorActive: Boolean = false,
    var readFailure: Throwable? = null,
) : MarketplaceTokenStore {
    var readCalls = 0
    val written = mutableListOf<MarketplaceTokenPair>()

    override suspend fun read(): StoredMarketplaceTokens? {
        readCalls++
        readFailure?.let { throw it }
        return stored
    }

    override suspend fun write(tokens: MarketplaceTokenPair): MarketplaceTokenStore.WriteOutcome {
        written += tokens
        if (writeOutcome == MarketplaceTokenStore.WriteOutcome.WRITTEN) {
            stored = StoredMarketplaceTokens(tokens.accessToken, tokens.refreshToken, tokens.refreshTokenExpiresAt)
        }
        return writeOutcome
    }

    override suspend fun otherActorLikelyActive(): Boolean = otherActorActive
}

/**
 * A [MarketplaceTokenRefreshClient] that returns a scripted pair, or throws a scripted failure.
 *
 * `open` so a test can script per-call behaviour (a first call that fails and mutates the store, a second that
 * succeeds) without a second fake.
 */
open class FakeMarketplaceTokenRefreshClient(var nextPair: MarketplaceTokenPair? = null, var failWith: Throwable? = null) :
    MarketplaceTokenRefreshClient {
    val presented = mutableListOf<String>()
    val accessTokensSent = mutableListOf<String?>()

    override suspend fun refresh(refreshToken: String, accessTokenOrNull: String?): MarketplaceTokenPair {
        presented += refreshToken
        accessTokensSent += accessTokenOrNull
        failWith?.let { throw it }
        return nextPair ?: error("FakeMarketplaceTokenRefreshClient has no scripted pair")
    }
}

/**
 * An unsigned JWT whose payload is `{"exp":<epochSeconds>}` — the only claim this library reads from a
 * DMarket access token.
 *
 * Tests must build the access token this way rather than as an opaque string, because the whole point of the
 * marketplace expiry rework is that the token's own `exp` is the authority and the cookie's `expirationDate`
 * (which the site sets to the *refresh* token's 30-day expiry) is not.
 */
fun jwtExpiringAt(expiresAt: Instant): String {
    val payload = base64UrlNoPad("{\"exp\":${expiresAt.epochSeconds}}")
    return "eyJhbGciOiJub25lIn0.$payload.sig"
}

private fun base64UrlNoPad(text: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val bytes = text.encodeToByteArray()
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
        out.append(alphabet[b0 shr 2])
        if (b1 < 0) {
            out.append(alphabet[(b0 and 0x03) shl 4])
        } else {
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (b2 < 0) {
                out.append(alphabet[(b1 and 0x0F) shl 2])
            } else {
                out.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                out.append(alphabet[b2 and 0x3F])
            }
        }
        i += 3
    }
    return out.toString()
}

// ---- FakeMarketplaceAuthenticator --------------------------------------------------------------

class FakeMarketplaceAuthenticator(private val token: String? = "test-bearer-token", private val refreshResult: Boolean = true) :
    MarketplaceAuthenticator {
    var tokenCalls = 0
    var refreshCalls = 0

    override suspend fun tokenOrNull(): String? {
        tokenCalls++
        return token
    }

    override suspend fun refreshOnUnauthorized(): Boolean {
        refreshCalls++
        return refreshResult
    }
}

// ---- FakeSteamSessionRefresher -----------------------------------------------------------------

class FakeSteamSessionRefresher(
    private val outcome: SessionRefreshOutcome = SessionRefreshOutcome.REFRESHED,
    private val failWith: Throwable? = null,
    /** What [sessionState] reports. Flippable mid-test. */
    var state: SteamWebSessionState = SteamWebSessionState.ALIVE,
    /** When set, [sessionState] throws it (the port's fail-open contract must absorb it). */
    private val stateFailsWith: Throwable? = null,
    /** When true, a forced (mint) refresh restores the session — what Steam accepting the handshake looks like. */
    private val mintRestoresSession: Boolean = false,
    /**
     * Which Steam account the session cookie belongs to. Flippable mid-test — that is what a re-login as a
     * different account looks like from the cookie jar. Left `null` (the default) the fake never reports
     * [SteamWebSessionState.OTHER_ACCOUNT], so every pre-existing test keeps its exact behaviour.
     */
    var cookieSteamId: SteamId? = null,
) : SteamSessionRefresher {
    var refreshCalls = 0
    var stateCalls = 0

    /** The `force` flag of each refreshSession call — `true` marks a mint (a session known to be gone). */
    val forcedCalls = mutableListOf<Boolean>()

    override suspend fun refreshSession(force: Boolean): SessionRefreshOutcome {
        refreshCalls++
        forcedCalls += force
        failWith?.let { throw it }
        // Simulate Steam accepting a mint: the handshake's settoken Set-Cookie lands a fresh session.
        if (force && mintRestoresSession) state = SteamWebSessionState.ALIVE
        return outcome
    }

    override suspend fun sessionState(expectedSteamId: SteamId?): SteamWebSessionState {
        stateCalls++
        stateFailsWith?.let { throw it }
        // Identity outranks the liveness verdict, mirroring DefaultSteamSessionRefresher — except for GONE,
        // which stays GONE: a session that is not there belongs to nobody.
        val cookieId = cookieSteamId
        if (state != SteamWebSessionState.GONE && expectedSteamId != null && cookieId != null && cookieId != expectedSteamId) {
            return SteamWebSessionState.OTHER_ACCOUNT
        }
        return state
    }
}

// ---- FakeSteamWebSessionGateway ----------------------------------------------------------------

class FakeSteamWebSessionGateway(
    private val cookies: MutableMap<Pair<String, String>, WebCookie> = mutableMapOf(),
    private val ajaxRefreshBody: String? = null,
    private val failWith: Throwable? = null,
    // When true, getWithSession throws TransientSessionException (simulates a non-OK 5xx/429/403 or a
    // network blip) so the refresher reports FAILED — distinct from a reachable "please log in" body.
    private val getThrowsTransient: Boolean = false,
    // Same, for the POST primitive the refresh handshake now uses.
    private val postThrowsTransient: Boolean = false,
    // What `login/settoken` answers. Steam replies `{"result":1}` when it accepted the transfer; the
    // default keeps every existing test on the happy path.
    private val setTokenBody: String? = """{"result":1,"token":"fresh"}""",
    // When set, POSTing to a `login/settoken` URL updates the community steamLoginSecure to this value,
    // simulating Steam's Set-Cookie landing in the jar. Left null to simulate a silently-rejected
    // settoken (cookie unchanged → refresher reports FAILED).
    private val settokenSetsCommunityCookie: WebCookie? = null,
) : SteamWebSessionGateway {
    val getUrls = mutableListOf<String>()
    val postedForms = mutableListOf<Pair<String, Map<String, String>>>()
    val cookieWrites = mutableListOf<Triple<String, String, Long?>>()

    override suspend fun readCookie(domain: String, name: String): WebCookie? {
        failWith?.let { throw it }
        return cookies[domain to name]
    }

    override suspend fun writeSessionCookie(domain: String, value: String, expiresAtEpochSeconds: Long?) {
        failWith?.let { throw it }
        cookieWrites += Triple(domain, value, expiresAtEpochSeconds)
        cookies[domain to "steamLoginSecure"] = WebCookie(value, expiresAtEpochSeconds)
    }

    override suspend fun getWithSession(url: String): String? {
        failWith?.let { throw it }
        getUrls += url
        if (getThrowsTransient) throw TransientSessionException("simulated transient ajaxrefresh failure")
        return ajaxRefreshBody
    }

    override suspend fun postFormWithSession(url: String, form: Map<String, String>): String? {
        failWith?.let { throw it }
        postedForms += url to form
        // The refresh handshake is a POST now (Steam rejects the GET form of it), so this one primitive
        // serves both hops: `jwt/ajaxrefresh` answers with the transfer, `login/settoken` with its result.
        if (url.endsWith("/jwt/ajaxrefresh")) {
            if (postThrowsTransient) throw TransientSessionException("simulated transient ajaxrefresh failure")
            return ajaxRefreshBody
        }
        // Mimic Steam's per-domain Set-Cookie: the community settoken response re-mints the cookie.
        if (settokenSetsCommunityCookie != null && url.contains("steamcommunity.com/login/settoken")) {
            cookies["steamcommunity.com" to "steamLoginSecure"] = settokenSetsCommunityCookie
        }
        return setTokenBody
    }
}

// ---- FakeScheduler -----------------------------------------------------------------------------

class FakeScheduler : Scheduler {
    private val tickChannel = Channel<Unit>(Channel.BUFFERED)
    override val ticks: Flow<Unit> = tickChannel.receiveAsFlow()

    val scheduledDelays = mutableListOf<Duration>()
    var cancelCount = 0
        private set

    override fun schedule(delay: Duration) {
        scheduledDelays += delay
    }

    override fun cancel() {
        cancelCount++
    }

    fun emitTick() {
        tickChannel.trySend(Unit)
    }
}

// ---- FakePushChannel ---------------------------------------------------------------------------

class FakePushChannel : PushChannel {
    private val channel = Channel<PushSignal>(Channel.BUFFERED)
    override val signals: Flow<PushSignal> = channel.receiveAsFlow()

    val registeredTokens = mutableListOf<String>()

    override suspend fun register(deviceToken: String) {
        registeredTokens += deviceToken
    }

    fun emit(signal: PushSignal) {
        channel.trySend(signal)
    }
}

// ---- FakeClock ---------------------------------------------------------------------------------

class FakeClock(private var current: Instant = Instant.fromEpochMilliseconds(1_781_611_200_000L)) : Clock {
    override fun now(): Instant = current
    fun advance(by: Duration) {
        current += by
    }
}

// ---- RecordingEventObserver --------------------------------------------------------------------

/** Records every [LifecycleEvent] the loop emits, for assertions. */
class RecordingEventObserver : EventObserver {
    val events = mutableListOf<LifecycleEvent>()

    override suspend fun onEvent(event: LifecycleEvent) {
        events += event
    }
}

// ---- FakeSteamOfferCanceller -------------------------------------------------------------------

class FakeSteamOfferCanceller(private val failWith: Throwable? = null) : SteamOfferCanceller {
    val cancelledOffers = mutableListOf<OfferId>()

    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId) {
        failWith?.let { throw it }
        cancelledOffers += offerId
    }
}

// ---- FakeSteamOfferCreator ---------------------------------------------------------------------

class FakeSteamOfferCreator(
    private val result: CreateOfferResult = CreateOfferResult.NeedsConfirmation(OfferId("offer-created")),
    /**
     * Thrown instead of returning [result] — the live shape of a rejected fetch (network down, missing
     * host permission, CORS) or of body/regex drift in the create actual. The attempt is still recorded
     * in [created]: it reached the port, it just never produced an answer.
     */
    private val throwable: Throwable? = null,
    /**
     * Per-partner script of results, consumed in order: the *n*-th create for a partner returns that
     * partner's *n*-th entry, and anything past the end falls back to [result]. This is how a test says
     * "Steam accepts one offer for this counterparty and refuses the next", which is the whole shape of the
     * per-partner outstanding-offer cap.
     */
    private val resultsByPartner: Map<SteamId, List<CreateOfferResult>> = emptyMap(),
    /**
     * Awaited (per partner, before recording) so a test can hold one create open and observe what the other
     * chains do meanwhile — the only way to prove creates for one partner are sequential while different
     * partners progress independently.
     */
    private val gate: (suspend (SteamId, Int) -> Unit)? = null,
) : SteamOfferCreator {
    val created = mutableListOf<TradeDraft>()

    /** Creates in flight per partner, sampled on entry — its max must never exceed 1 for any partner. */
    private val inFlight = mutableMapOf<SteamId, Int>()
    val maxInFlightPerPartner = mutableMapOf<SteamId, Int>()

    /** Every partner a create was attempted for, in attempt order — the chain interleaving, verbatim. */
    val attemptOrder = mutableListOf<SteamId>()

    private val perPartnerCalls = mutableMapOf<SteamId, Int>()

    override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult {
        val partner = draft.partner
        val index = perPartnerCalls.getOrElse(partner) { 0 }
        perPartnerCalls[partner] = index + 1
        val live = inFlight.getOrElse(partner) { 0 } + 1
        inFlight[partner] = live
        maxInFlightPerPartner[partner] = maxOf(maxInFlightPerPartner.getOrElse(partner) { 0 }, live)
        attemptOrder += partner
        try {
            gate?.invoke(partner, index)
            created += draft
            throwable?.let { throw it }
            return resultsByPartner[partner]?.getOrNull(index) ?: result
        } finally {
            inFlight[partner] = inFlight.getValue(partner) - 1
        }
    }

    /** How many creates this fake was asked to perform for [partner]. */
    fun callsFor(partner: SteamId): Int = perPartnerCalls.getOrElse(partner) { 0 }
}
