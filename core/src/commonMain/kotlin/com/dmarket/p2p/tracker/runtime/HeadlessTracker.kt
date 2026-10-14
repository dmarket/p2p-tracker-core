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
import com.dmarket.p2p.tracker.client.marketplace.TradingApiAuthenticator
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
import com.dmarket.p2p.tracker.model.LifecycleEvent
import com.dmarket.p2p.tracker.model.RuntimeSurface
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * What a headless host supplies from its platform. Nothing secret passes through it.
 *
 * @property storage where the loop's progress, claims, throttles and device id persist. No secret is ever
 *   written to it.
 * @property eventObserver every lifecycle event the loop emits, after the tracker's own handling.
 * @property httpEngine the HTTP engine for both DMarket and Steam; `null` takes the platform's default.
 * @property signerFor builds the Ed25519 signer for a pushed key; throws when the key cannot sign. The platform's
 *   own by default.
 */
class HeadlessPlatform(
    val storage: DeviceKeyValueStore,
    val clock: Clock = SystemClock(),
    val eventObserver: EventObserver = NoOpEventObserver,
    val httpEngine: HttpClientEngine? = null,
    val signerFor: suspend (TradingApiKey) -> Ed25519Signer = ::platformEd25519Signer,
)

/**
 * What a [HeadlessTracker] holds right now, without either secret.
 *
 * @property hasTradingApiKey whether a DMarket key is held.
 * @property steamTokenSteamId the Steam account of the held token; `null` before the first accepted push.
 * @property steamTokenExpiresAt when the held token expires; it stays held, expired, until a fresh one is pushed.
 * @property linkedSteamId the Steam account the last heartbeat named as linked to the DMarket account.
 * @property readySince since when, as of the last cycle, a key and a live token have both been held; `null` while
 *   they are not. A heartbeat is owed only from then.
 */
data class HeadlessTrackerState(
    val hasTradingApiKey: Boolean,
    val steamTokenSteamId: SteamId?,
    val steamTokenExpiresAt: Instant?,
    val linkedSteamId: SteamId?,
    val readySince: Instant?,
)

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
 *  - It does not heartbeat at all until a key and a live token are held.
 *
 * No proof is made yet.
 *
 * Build one per process and Steam account: the persisted stores' locks are what serialise the loop's writes.
 *
 * @param scope runs the driver ([start]) and the cycle a push triggers.
 * @param baseUrl the DMarket gateway.
 */
class HeadlessTracker(
    private val platform: HeadlessPlatform,
    private val scope: CoroutineScope,
    private val baseUrl: String = TrackerConfig.DEFAULT_DMARKET_BASE_URL,
    private val tunables: TrackerConfig = TrackerConfig.defaults(),
) {
    private val clock = platform.clock
    private val key = PushedTradingApiKey(platform.signerFor)
    internal val steam = PushedSteamSession(clock) { loop.linkedSteamId }
    private val scheduler = CoroutineScheduler(scope)
    private val cycleMutex = Mutex()

    // Written under cycleMutex only. No @Volatile: JS is single-threaded; JVM callers share one scope.
    private var readySince: Instant? = null

    private val observer = EventObserver { event ->
        if (event is LifecycleEvent.HeartbeatSent) steam.heartbeatReturned()
        platform.eventObserver.onEvent(event)
    }

    internal val loop: TradeTrackerLoop = buildLoop()

    /** Starts the driver: a cycle now, then one per wake the loop asks for. */
    fun start() {
        scope.launch { scheduler.ticks.collect { cycle() } }
        scope.launch { cycle() }
    }

    /**
     * Runs one loop cycle when the tracker may act, and arms the next wake either way. Without a key and a live
     * token it neither heartbeats nor touches Steam; it re-checks every [IDLE_RECHECK] and on every push.
     */
    suspend fun cycle() {
        cycleMutex.withLock {
            if (!ready()) {
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
     * Holds the DMarket Trading API key pair, once a signer has been built for it. Another key than the one held
     * may sign for another DMarket account, so the linked Steam account stops counting until a heartbeat signed
     * with the new key comes back. A new key heartbeats at once.
     */
    suspend fun pushTradingApiKey(publicKey: String, secretKey: String): TradingKeyPushResult {
        val push = key.push(publicKey, secretKey)
        if (push is TradingKeyPushResult.Held && push.change != KeyChange.SAME) {
            if (push.change == KeyChange.REPLACED) steam.forgetLinkedAccount()
            nudge()
        }
        return push
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
        )
    }

    private fun ready(): Boolean = key.key != null && steam.ready()

    /** A push changed what the tracker can do: heartbeat now instead of at the next scheduled wake. */
    private fun nudge() {
        scope.launch {
            loop.forceHeartbeatNow()
            cycle()
        }
    }

    private fun buildLoop(): TradeTrackerLoop {
        val timeoutMs = tunables.http.requestTimeoutMs.toLong()
        val secretParams = NetworkRedaction.plusSecretParam(tunables.steamEndpoints.paramAccessToken)
        val steamHttp = platform.httpEngine?.let { createSteamHttpClient(it, timeoutMs, secretParamNames = secretParams) }
            ?: createSteamHttpClient(requestTimeoutMs = timeoutMs, secretParamNames = secretParams)
        val marketplaceHttp = platform.httpEngine?.let { createMarketplaceHttpClient(it, timeoutMs) }
            ?: createMarketplaceHttpClient(requestTimeoutMs = timeoutMs)
        val community = tunables.steamEndpoints.communityBaseUrl
        val adapter = Cs2GameAdapter(tunables.game)
        val sessionId = newSteamSessionId()
        val storage = platform.storage
        return TradeTrackerCore(GameRegistry.v1(tunables.game)).createLoop(
            config = LoopConfig(
                clientVersion = TradeTrackerCore.VERSION,
                surface = RuntimeSurface.Headless,
                mode = TrackerMode.Background,
                tunables = tunables,
                requireLinkedSteamId = true,
                minWriteTokenLifetime = MIN_WRITE_TOKEN_LIFETIME,
            ),
            marketplace = KtorMarketplaceClient(
                httpClient = marketplaceHttp,
                baseUrl = baseUrl,
                authenticator = TradingApiAuthenticator({ key.key }, clock, platform.signerFor),
                retry = tunables.marketplaceRetry,
            ),
            steamReader = KtorSteamReadClient(steamHttp, tunables.steamEndpoints),
            scraper = steam.scraper,
            scheduler = scheduler,
            deviceId = PersistedDeviceIdStore(storage),
            vault = steam.vault,
            clock = clock,
            notary = NoOpNotaryProver,
            offerCreator = HeaderSessionSteamOfferCreator(steamHttp, sessionId, community, adapter),
            offerCanceller = HeaderSessionSteamOfferCanceller(steamHttp, sessionId, community),
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
        /** Under this much token life no create starts: the seller could not confirm it before the session ends. */
        val MIN_WRITE_TOKEN_LIFETIME: Duration = 30.minutes

        /** How often a tracker that may not act yet re-checks, besides every push. */
        val IDLE_RECHECK: Duration = 30.seconds
    }
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
