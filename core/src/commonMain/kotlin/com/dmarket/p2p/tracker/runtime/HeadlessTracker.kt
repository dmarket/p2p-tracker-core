package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.adapter.host.NoOpEventObserver
import com.dmarket.p2p.tracker.adapter.host.PersistedDeviceIdStore
import com.dmarket.p2p.tracker.adapter.host.SystemClock
import com.dmarket.p2p.tracker.adapter.notary.NoOpNotaryProver
import com.dmarket.p2p.tracker.adapter.steam.NoOpSteamSessionRefresher
import com.dmarket.p2p.tracker.client.createMarketplaceHttpClient
import com.dmarket.p2p.tracker.client.createSteamHttpClient
import com.dmarket.p2p.tracker.client.marketplace.KtorMarketplaceClient
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.client.marketplace.TradingApiAuthenticator
import com.dmarket.p2p.tracker.client.marketplace.TradingApiNotaryUpgradeSigner
import com.dmarket.p2p.tracker.client.marketplace.platformEd25519Signer
import com.dmarket.p2p.tracker.client.steam.HeaderSessionSteamOfferCanceller
import com.dmarket.p2p.tracker.client.steam.HeaderSessionSteamOfferCreator
import com.dmarket.p2p.tracker.client.steam.KtorSteamInventoryReader
import com.dmarket.p2p.tracker.client.steam.KtorSteamNotificationReader
import com.dmarket.p2p.tracker.client.steam.KtorSteamReadClient
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.KeyChange
import com.dmarket.p2p.tracker.credential.marketplace.PushedTradingApiKey
import com.dmarket.p2p.tracker.credential.marketplace.TradingKeyPushResult
import com.dmarket.p2p.tracker.credential.steam.PushedSteamSession
import com.dmarket.p2p.tracker.credential.steam.SteamPushResult
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.game.GameRegistry
import com.dmarket.p2p.tracker.loop.LoopConfig
import com.dmarket.p2p.tracker.loop.PersistedDealWriteClaimStore
import com.dmarket.p2p.tracker.loop.PersistedLoopStateStore
import com.dmarket.p2p.tracker.loop.PersistedNotaryProofThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedSteamWriteThrottleStore
import com.dmarket.p2p.tracker.loop.PersistedTrackerProgressStore
import com.dmarket.p2p.tracker.loop.PersistedUnprovenClaimStore
import com.dmarket.p2p.tracker.loop.TradeTrackerLoop
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatRequest
import com.dmarket.p2p.tracker.model.marketplace.HeartbeatResponse
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.model.steam.TradeDraft
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.notary.HostedNotaryProver
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceClient
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.port.notary.NotaryProver
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import com.dmarket.p2p.tracker.port.steam.SteamOfferCanceller
import com.dmarket.p2p.tracker.port.steam.SteamOfferCreator
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What a headless host supplies from its platform. Nothing secret passes through it.
 *
 * @property storageFor where one Steam account's loop state persists — progress, claims, throttles, the device id —
 *   for the account DMarket links to the pushed key's ([AccountScopedStorage]): one location per account, so a
 *   process started with another account's key never reads the first one's state. Called with steamid64s only, and
 *   before anything is written. No secret is ever written to it.
 * @property eventObserver every lifecycle event the loop emits, after the tracker's own handling.
 * @property httpEngine the HTTP engine for both DMarket and Steam; `null` takes the platform's default.
 * @property signerFor builds the Ed25519 signer for a pushed key; throws when the key cannot sign. The platform's
 *   own by default.
 * @property proving how proofs are made; `null` makes none (the no-op prover), as before.
 */
class HeadlessPlatform(
    val storageFor: (SteamId) -> DeviceKeyValueStore,
    val clock: Clock = SystemClock(),
    val eventObserver: EventObserver = NoOpEventObserver,
    val httpEngine: HttpClientEngine? = null,
    val signerFor: suspend (TradingApiKey) -> Ed25519Signer = ::platformEd25519Signer,
    val proving: HeadlessProving? = null,
)

/**
 * What a [HeadlessTracker] holds right now, without either secret.
 *
 * @property hasTradingApiKey whether a DMarket key is held.
 * @property steamTokenSteamId the Steam account of the held token; `null` before the first accepted push.
 * @property steamTokenExpiresAt when the held token expires; it stays held, expired, until a fresh one is pushed.
 * @property linkedSteamId the Steam account the last heartbeat named as linked to the DMarket account.
 * @property readySince since when, as of the last cycle, the tracker has owed heartbeats — a key held, and a live
 *   token or a bound account; `null` while it does not. A heartbeat is owed only from then.
 * @property trackedDealIds the deals the last heartbeat asked this device to track.
 * @property clockOffset how far DMarket's clock (the `Date` of its last reply) is ahead of this host's; `null`
 *   before a reply. Past [HeadlessTracker.MAX_CLOCK_SKEW] the gateway refuses every signature.
 * @property steamNotLinked whether the last heartbeat was refused because the DMarket account links no Steam
 *   account (`403 P2P_STEAM_NOT_LINKED`); cleared by the next heartbeat that comes back.
 */
data class HeadlessTrackerState(
    val hasTradingApiKey: Boolean,
    val steamTokenSteamId: SteamId?,
    val steamTokenExpiresAt: Instant?,
    val linkedSteamId: SteamId?,
    val readySince: Instant?,
    val trackedDealIds: List<DealId> = emptyList(),
    val clockOffset: Duration? = null,
    val steamNotLinked: Boolean = false,
) {
    /** Whether [clockOffset] is past what the gateway's signature check allows. */
    val clockSkewed: Boolean get() = clockOffset?.let { it.absoluteValue > HeadlessTracker.MAX_CLOCK_SKEW } ?: false
}

/**
 * The tracker loop composed for a host with no browser and no UI, and its driver. The host pushes the DMarket
 * Trading API key ([pushTradingApiKey]) and a Steam access token ([pushSteamToken]); both stay in memory.
 *
 * The loop is the browser extension's, with these differences:
 *  - DMarket calls are signed with the pushed key ([TradingApiAuthenticator]), not a session token.
 *  - The Steam session is the pushed token, held by [PushedSteamSession]: `steamLoginSecure` is built from it per
 *    request, and the `sessionid` double-submit value is generated once per tracker. Nothing refreshes it — the
 *    host pushes a fresh one.
 *  - The account binding fails closed (`requireLinkedSteamId`), and no create starts on a token with less than
 *    [MIN_WRITE_TOKEN_LIFETIME] left.
 *  - `foreground` follows the token, not a mode: `true` only while the account is bound to the token's and the token
 *    has [MIN_WRITE_TOKEN_LIFETIME] left. DMarket counts the tracker present only then.
 *  - It does not heartbeat at all until a key and a live token are held. Once a heartbeat has bound the account, a
 *    token that expires or is missing does not stop the heartbeat: it goes on for the bound account with
 *    `foreground: false`, and nothing touches Steam until a fresh token is pushed. After a restart nothing is bound
 *    (nothing is kept across one), so it waits for both pushes again.
 *  - It serves one DMarket account: a pushed key for another one is refused ([pushTradingApiKey]).
 *
 * Proofs are made only when the platform supplies [HeadlessPlatform.proving]: its host runs each one, with the
 * notary upgrade signed by the pushed key. No proof starts on a token with less than [MIN_WRITE_TOKEN_LIFETIME] left.
 *
 * Build one per process and Steam account: the persisted stores' locks are what serialise the loop's writes.
 *
 * @param scope runs the driver ([start]) and the cycle a push triggers.
 * @param baseUrl the DMarket gateway.
 * @param steamWrites `false` for an account that only buys: no Steam offer is created or cancelled, whatever is
 *   leased — the writers are not even built, and the loop drops such a directive unreported, so its lease lapses to
 *   a device that writes. Reads, reports and the heartbeat are unchanged.
 */
class HeadlessTracker(
    private val platform: HeadlessPlatform,
    private val scope: CoroutineScope,
    private val baseUrl: String = TrackerConfig.DEFAULT_DMARKET_BASE_URL,
    private val tunables: TrackerConfig = TrackerConfig.defaults(),
    private val steamWrites: Boolean = true,
) {
    private val clock = platform.clock
    private val key = PushedTradingApiKey(platform.signerFor)
    internal val steam = PushedSteamSession(clock) { loop.linkedSteamId }
    private val scheduler = CoroutineScheduler(scope)
    private val cycleMutex = Mutex()
    private val storage = AccountScopedStorage(platform.storageFor) { steam.credential?.subjectSteamId }
    private val deviceIds = PersistedDeviceIdStore(storage)
    private val requestTimeoutMs = tunables.http.requestTimeoutMs.toLong()
    private val marketplaceHttp = platform.httpEngine?.let { createMarketplaceHttpClient(it, requestTimeoutMs) }
        ?: createMarketplaceHttpClient(requestTimeoutMs = requestTimeoutMs)

    // Written under cycleMutex only. No @Volatile: JS is single-threaded; JVM callers share one scope.
    private var readySince: Instant? = null

    // Written by the marketplace calls, the loop's and the key check's. No @Volatile, as above.
    private var clockOffset: Duration? = null
    private var steamNotLinked = false

    private val observer = EventObserver { event ->
        if (event is LifecycleEvent.HeartbeatSent) steam.heartbeatReturned()
        platform.eventObserver.onEvent(event)
    }

    private val steamSessionId = newSteamSessionId()
    private val authenticator = TradingApiAuthenticator({ key.key }, clock, platform.signerFor)

    /** What proves for the loop: the platform's host when it gave one, the no-op prover otherwise. */
    internal val prover: NotaryProver = platform.proving?.let { proving ->
        HostedNotaryProver(
            host = proving.host,
            upgrade = TradingApiNotaryUpgradeSigner(authenticator, tunables.notary.notaryUrl),
            clock = clock,
            steamSessionId = steamSessionId,
            maxConcurrency = proving.maxConcurrency,
            minTokenLifetime = MIN_WRITE_TOKEN_LIFETIME,
        )
    } ?: NoOpNotaryProver

    internal val loop: TradeTrackerLoop = buildLoop()

    /** Starts the driver: a cycle now, then one per wake the loop asks for. */
    fun start() {
        scope.launch { scheduler.ticks.collect { cycle() } }
        scope.launch { cycle() }
    }

    /**
     * Runs one loop cycle when the tracker owes heartbeats, and arms the next wake either way. Without a key, or
     * with neither a live token nor a bound account, it neither heartbeats nor touches Steam; it re-checks every
     * [IDLE_RECHECK] and on every push.
     */
    suspend fun cycle() {
        cycleMutex.withLock {
            if (!heartbeating()) {
                readySince = null
                scheduler.schedule(IDLE_RECHECK)
                return
            }
            if (readySince == null) readySince = clock.now()
            loop.runOnceReporting()
            scheduler.schedule(loop.nextWakeDelay())
        }
    }

    /**
     * Holds the DMarket Trading API key pair, once a signer has been built for it.
     *
     * One tracker serves one DMarket account, so a key other than the one held replaces it only after a heartbeat
     * signed with it ([checkKey]) names the Steam account the held key is bound to. A key for another account is
     * refused ([TradingKeyPushResult.AccountChanged]), and so is one DMarket refuses or that could not be checked:
     * the held key and the binding stay. The first key heartbeats at once; a rotated one does not, its check was
     * that heartbeat. A key that replaced one with no account bound yet unbinds until its own heartbeat comes back.
     */
    suspend fun pushTradingApiKey(publicKey: String, secretKey: String): TradingKeyPushResult {
        val push = key.push(publicKey, secretKey, ::checkKey)
        if (push is TradingKeyPushResult.Held) {
            when (push.change) {
                KeyChange.FIRST -> nudge()
                KeyChange.REPLACED -> {
                    steam.forgetLinkedAccount()
                    nudge()
                }
                KeyChange.SAME, KeyChange.ROTATED -> Unit
            }
        }
        return push
    }

    /**
     * Answers for [candidate] replacing [held]: one heartbeat signed with it, for the held token's Steam account and
     * this device, sent beside the loop rather than through it, so the loop's schedule and state stay as they were.
     * The directives it leases are leased to this device, so the loop's next heartbeat is served them again.
     *
     * The candidate passes when its heartbeat names the account the held key is bound to ([KeyChange.ROTATED]),
     * or when none is bound yet ([KeyChange.REPLACED]). With no token ever held there is no account to heartbeat
     * for, and the held key has never been sent either, so there is nothing to check.
     */
    private suspend fun checkKey(candidate: TradingApiKey, held: TradingApiKey): TradingKeyPushResult {
        val replaced = TradingKeyPushResult.Held(candidate.publicKey, KeyChange.REPLACED)
        val steamId = steam.credential?.subjectSteamId ?: return replaced
        val request = HeartbeatRequest(
            clientVersion = TradeTrackerCore.VERSION,
            platform = RuntimeSurface.Headless.platformWireName,
            foreground = false,
            steamId = steamId,
            deviceId = deviceIds.current(),
        )
        val client = KtorMarketplaceClient(
            httpClient = marketplaceHttp,
            baseUrl = baseUrl,
            authenticator = TradingApiAuthenticator({ candidate }, clock, platform.signerFor),
            onServerDate = ::heardServerDate,
        )
        val unverified = { reason: String -> TradingKeyPushResult.Unverified(candidate.publicKey, reason) }
        val heartbeat = try {
            client.heartbeat(request)
        } catch (e: CancellationException) {
            throw e
        } catch (_: MarketplaceUnauthorizedException) {
            // A refused signature says nothing about the key while the clock it was made with is off.
            if (state().clockSkewed) return unverified(CLOCK_SKEWED)
            return TradingKeyPushResult.Invalid(KEY_REFUSED)
        } catch (e: MarketplaceServerErrorException) {
            return when {
                // The key's account links no Steam account, so it is not the account the held key is bound to.
                e.errorCode == STEAM_NOT_LINKED_CODE -> steam.boundSteamId()
                    ?.let { TradingKeyPushResult.AccountChanged(candidate.publicKey, held.publicKey, it, null) }
                    ?: TradingKeyPushResult.Invalid(KEY_ACCOUNT_NOT_LINKED)
                e.statusCode == HTTP_FORBIDDEN -> TradingKeyPushResult.Invalid(KEY_REFUSED)
                else -> unverified("its heartbeat failed: HTTP ${e.statusCode}")
            }
        } catch (_: RateLimitedException) {
            return unverified("its heartbeat was rate-limited")
        } catch (_: Throwable) {
            return unverified("its heartbeat did not come back")
        }
        val bound = steam.boundSteamId() ?: return replaced
        if (heartbeat.linkedSteamId == bound) return TradingKeyPushResult.Held(candidate.publicKey, KeyChange.ROTATED)
        return TradingKeyPushResult.AccountChanged(candidate.publicKey, held.publicKey, bound, heartbeat.linkedSteamId)
    }

    /** Checks and holds a Steam access token; [PushedSteamSession] says which one is held. A held one heartbeats at once. */
    suspend fun pushSteamToken(accessToken: String): SteamPushResult = steam.push(accessToken).also {
        if (it is SteamPushResult.Held) nudge()
    }

    fun state(): HeadlessTrackerState {
        val credential = steam.credential
        return HeadlessTrackerState(
            hasTradingApiKey = key.key != null,
            steamTokenSteamId = credential?.subjectSteamId,
            steamTokenExpiresAt = credential?.expiresAt,
            linkedSteamId = loop.linkedSteamId,
            readySince = readySince,
            trackedDealIds = loop.trackedDealIds,
            clockOffset = clockOffset,
            steamNotLinked = steamNotLinked,
        )
    }

    private fun heartbeating(): Boolean = key.key != null && (steam.ready() || steam.boundSteamId() != null)

    private fun heardServerDate(serverNow: Instant) {
        clockOffset = serverNow - clock.now()
    }

    /**
     * The loop's marketplace client, watched for what only the heartbeat says about the account: the linked Steam
     * account its state is kept under ([AccountScopedStorage.confirm]), and the one refusal the account's owner has to
     * fix on DMarket — no Steam account linked, which the loop itself reads as any other refused heartbeat.
     */
    private inner class AccountWatch(private val client: MarketplaceClient) : MarketplaceClient by client {
        override suspend fun heartbeat(request: HeartbeatRequest): HeartbeatResponse = try {
            client.heartbeat(request).also { response ->
                steamNotLinked = false
                response.linkedSteamId?.let { storage.confirm(it) }
            }
        } catch (e: MarketplaceServerErrorException) {
            steamNotLinked = e.errorCode == STEAM_NOT_LINKED_CODE
            throw e
        }
    }

    /** A push changed what the tracker can do: heartbeat now instead of at the next scheduled wake. */
    private fun nudge() {
        scope.launch {
            loop.forceHeartbeatNow()
            cycle()
        }
    }

    private fun buildLoop(): TradeTrackerLoop {
        val secretParams = NetworkRedaction.plusSecretParam(tunables.steamEndpoints.paramAccessToken)
        val steamHttp = platform.httpEngine?.let { createSteamHttpClient(it, requestTimeoutMs, secretParamNames = secretParams) }
            ?: createSteamHttpClient(requestTimeoutMs = requestTimeoutMs, secretParamNames = secretParams)
        val community = tunables.steamEndpoints.communityBaseUrl
        val adapter = Cs2GameAdapter(tunables.game)
        val sessionId = steamSessionId
        val creator = if (steamWrites) HeaderSessionSteamOfferCreator(steamHttp, sessionId, community, adapter) else RefusedOfferWrites
        val canceller = if (steamWrites) HeaderSessionSteamOfferCanceller(steamHttp, sessionId, community) else RefusedOfferWrites
        return TradeTrackerCore(GameRegistry.v1(tunables.game)).createLoop(
            config = LoopConfig(
                clientVersion = TradeTrackerCore.VERSION,
                surface = RuntimeSurface.Headless,
                mode = TrackerMode.Background,
                tunables = tunables,
                requireLinkedSteamId = true,
                minWriteTokenLifetime = MIN_WRITE_TOKEN_LIFETIME,
                foregroundMinTokenLifetime = MIN_WRITE_TOKEN_LIFETIME,
                heartbeatWithoutCredential = true,
                steamWrites = steamWrites,
            ),
            marketplace = AccountWatch(
                KtorMarketplaceClient(
                    httpClient = marketplaceHttp,
                    baseUrl = baseUrl,
                    authenticator = authenticator,
                    retry = tunables.marketplaceRetry,
                    maxReportBodyBytes = tunables.http.maxReportBodyBytes ?: MAX_REPORT_BODY_BYTES,
                    onServerDate = ::heardServerDate,
                ),
            ),
            steamReader = KtorSteamReadClient(steamHttp, tunables.steamEndpoints),
            scraper = steam.scraper,
            scheduler = scheduler,
            deviceId = deviceIds,
            vault = steam.vault,
            clock = clock,
            notary = prover,
            offerCreator = creator,
            offerCanceller = canceller,
            inventoryReader = KtorSteamInventoryReader(
                httpClient = steamHttp,
                communityBaseUrl = community,
                adapter = adapter,
                pageCount = tunables.steamEndpoints.inventoryPageCount,
                maxPages = tunables.steamEndpoints.inventoryMaxPages,
            ),
            sessionRefresher = NoOpSteamSessionRefresher,
            progress = PersistedTrackerProgressStore(storage),
            claims = PersistedDealWriteClaimStore(storage),
            unprovenClaims = PersistedUnprovenClaimStore(storage),
            throttle = PersistedSteamWriteThrottleStore(limits = tunables.steamWrites, storage = storage),
            notaryThrottle = PersistedNotaryProofThrottleStore(limits = tunables.notary.breaker, storage = storage),
            loopState = PersistedLoopStateStore(storage),
            directivesEnabled = true,
            eventObserver = observer,
            notifications = KtorSteamNotificationReader(steamHttp, tunables.steamEndpoints),
        )
    }

    companion object {
        /**
         * Under this much token life no create starts — the seller could not confirm it before the session ends — and
         * the heartbeat stops claiming the device present.
         */
        val MIN_WRITE_TOKEN_LIFETIME: Duration = 30.minutes

        /**
         * Default report body cap ([com.dmarket.p2p.tracker.config.HttpConfig.maxReportBodyBytes]). Every request
         * here is signed, and a gateway that spools a body past its buffer checks the signature against an empty
         * body; the buffer is not known exactly but sits below 9.8 KB, so this stays clear of an 8 KiB one.
         */
        const val MAX_REPORT_BODY_BYTES: Int = 7 * 1024

        /** How often a tracker that may not act yet re-checks, besides every push. */
        val IDLE_RECHECK: Duration = 30.seconds

        /**
         * How far this host's clock may be off DMarket's before it is reported. The gateway accepts a signature made
         * within two minutes of its own clock; this leaves room for the reply's own transit.
         */
        val MAX_CLOCK_SKEW: Duration = 60.seconds

        /** The gateway's refusal of a heartbeat for an account that links no Steam account. */
        const val STEAM_NOT_LINKED_CODE: String = "P2P_STEAM_NOT_LINKED"

        private const val KEY_REFUSED = "DMarket refused the key"
        private const val KEY_ACCOUNT_NOT_LINKED = "the key's DMarket account has no Steam account linked"
        private const val CLOCK_SKEWED = "DMarket refused its signature, and this host's clock is off DMarket's by more than a minute"
        private const val HTTP_FORBIDDEN = 403
    }
}

/** The offer writers of a tracker built without Steam writes. The loop never calls them; if it did, nothing is sent. */
private object RefusedOfferWrites : SteamOfferCreator, SteamOfferCanceller {
    private const val REFUSED = "offer writes are disabled on this client"

    override suspend fun createOffer(credential: SteamCredential, draft: TradeDraft): CreateOfferResult = CreateOfferResult.Failed(REFUSED)

    override suspend fun cancelOffer(credential: SteamCredential, offerId: OfferId): Unit = throw IllegalStateException(REFUSED)
}

/**
 * A fresh Steam `sessionid`: 12 random bytes, hex, the size Steam's own clients generate. Steam only checks that
 * the cookie and the form carry the same value; it is unpredictable all the same, like the ones Steam issues.
 *
 * A random UUID is drawn from the platform's CSPRNG on every target, so no platform hook is needed. Its bytes 6
 * and 8 carry the version and the variant and are skipped, which leaves 14 fully random bytes.
 */
@OptIn(ExperimentalUuidApi::class)
internal fun newSteamSessionId(): String {
    val bytes = Uuid.random().toByteArray()
    return bytes.filterIndexed { index, _ -> index != UUID_VERSION_BYTE && index != UUID_VARIANT_BYTE }
        .take(SESSION_ID_BYTES)
        .toByteArray()
        .toHexString()
}

private const val SESSION_ID_BYTES = 12
private const val UUID_VERSION_BYTE = 6
private const val UUID_VARIANT_BYTE = 8
