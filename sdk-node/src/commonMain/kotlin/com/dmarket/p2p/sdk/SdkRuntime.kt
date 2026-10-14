package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.api.ManagementActions
import com.dmarket.p2p.sdk.api.PushOutcome
import com.dmarket.p2p.sdk.health.Health
import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.sdk.secrets.KeyPush
import com.dmarket.p2p.sdk.secrets.SdkSecrets
import com.dmarket.p2p.tracker.adapter.host.CoroutineScheduler
import com.dmarket.p2p.tracker.adapter.host.PersistedDeviceIdStore
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
import com.dmarket.p2p.tracker.model.TrackerMode
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.net.NetworkRedaction
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.runtime.TradeTrackerCore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * What the composition needs from the platform it runs on.
 *
 * @property storage where the loop's progress, claims, throttles and device id persist. No secret is ever
 *   written to it.
 * @property signerFor builds the Ed25519 signer for a pushed key; throws when the key cannot sign. The platform's
 *   own by default.
 * @property randomHex [bytes] cryptographically random bytes, hex-encoded.
 * @property httpEngine the HTTP engine for both DMarket and Steam; `null` takes the platform's default.
 */
class SdkPlatform(
    val storage: DeviceKeyValueStore,
    val randomHex: (bytes: Int) -> String,
    val clock: Clock,
    val log: SdkLog,
    val httpEngine: HttpClientEngine? = null,
    val signerFor: suspend (TradingApiKey) -> Ed25519Signer = ::platformEd25519Signer,
)

/**
 * The SDK: the tracker loop composed for a headless host, its driver, and the actions the management API takes.
 *
 * The loop is the browser extension's, with these differences:
 *  - DMarket calls are signed with the pushed Trading API key ([TradingApiAuthenticator]), not a session token.
 *  - The Steam session is the pushed access token, held by [PushedSteamSession]: `steamLoginSecure` is built
 *    from it per request, and the `sessionid` double-submit value is generated here once per process. Nothing
 *    refreshes it — the bot pushes a fresh one.
 *  - The account binding fails closed (`requireLinkedSteamId`), and no create starts on a token with less than
 *    [MIN_WRITE_TOKEN_LIFETIME] left.
 *  - It does not heartbeat at all until a key and a live token are held.
 *
 * No proof is made yet: the prover runs as a separate process, which is not wired in.
 */
class SdkRuntime(
    private val config: SdkConfig,
    private val platform: SdkPlatform,
    private val scope: CoroutineScope,
    private val tunables: TrackerConfig = TrackerConfig.defaults(),
) : ManagementActions {
    private val clock = platform.clock
    private val log = platform.log
    val secrets = SdkSecrets()
    val steam = PushedSteamSession(clock) { loop.linkedSteamId }
    val health = HealthTracker(clock, version = VERSION)
    private val scheduler = CoroutineScheduler(scope)
    private val cycleMutex = Mutex()

    private val observer = EventObserver { event ->
        if (event is LifecycleEvent.HeartbeatSent) steam.heartbeatReturned()
        health.onEvent(event)
        log.onEvent(event)
    }

    val loop: TradeTrackerLoop = buildLoop()

    /** Starts the driver: a cycle now, then one per wake the loop asks for. */
    fun start() {
        scope.launch { scheduler.ticks.collect { cycle() } }
        scope.launch { cycle() }
    }

    /**
     * Runs one loop cycle when the SDK may act, and arms the next wake either way. Without a key and a live token
     * it neither heartbeats nor touches Steam; it re-checks on a short timer and on every push.
     */
    suspend fun cycle() {
        cycleMutex.withLock {
            health.ableToAct(ready())
            if (!ready()) {
                scheduler.schedule(IDLE_RECHECK)
                return
            }
            loop.runOnceReporting()
            scheduler.schedule(loop.nextWakeDelay())
        }
    }

    override suspend fun putDmarketKey(publicKey: String, secretKey: String): PushOutcome {
        val key = TradingApiKey.parse(publicKey, secretKey)
            ?: return PushOutcome.Invalid("INVALID_KEY", "expected a hex Ed25519 key pair: a 32-byte public key and its secret")
        try {
            platform.signerFor(key)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            return PushOutcome.Invalid("INVALID_KEY", "the secret key cannot sign")
        }
        when (secrets.putDmarketKey(key)) {
            KeyPush.SAME -> return PushOutcome.Held
            KeyPush.FIRST -> log.info("dmarket key pushed", "publicKey" to key.publicKey)
            KeyPush.REPLACED -> {
                steam.forgetLinkedAccount()
                log.info("dmarket key replaced", "publicKey" to key.publicKey)
            }
        }
        nudge()
        return PushOutcome.Held
    }

    override suspend fun putSteamToken(accessToken: String): PushOutcome = when (val push = steam.push(accessToken)) {
        is SteamPushResult.Invalid -> {
            log.warn("steam token refused", "reason" to push.reason)
            PushOutcome.Invalid("INVALID_TOKEN", push.reason)
        }
        is SteamPushResult.Held -> {
            health.tokenHeld()
            log.info(
                "steam token held",
                "steamId" to push.steamId.value,
                "expiresAt" to push.expiresAt.toString(),
            )
            nudge()
            PushOutcome.Held
        }
        is SteamPushResult.WrongAccount -> {
            health.tokenRefused(push.tokenSteamId, push.linkedSteamId)
            val expected = push.linkedSteamId?.value ?: "the account of the token already held"
            log.warn("steam token for another account refused", "tokenSteamId" to push.tokenSteamId.value, "expected" to expected)
            PushOutcome.Conflict("STEAM_WRONG_ACCOUNT", "the token is for ${push.tokenSteamId.value}, not $expected")
        }
    }

    override fun health(): Health = health.snapshot(hasKey = secrets.dmarketKey != null, credential = steam.credential)

    private fun ready(): Boolean = secrets.dmarketKey != null && steam.ready()

    /** A push changed what the SDK can do: heartbeat now instead of at the next scheduled wake. */
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
        val sessionId = platform.randomHex(SESSION_ID_BYTES)
        val storage = platform.storage
        return TradeTrackerCore(GameRegistry.v1(tunables.game)).createLoop(
            config = LoopConfig(
                clientVersion = VERSION,
                surface = RuntimeSurface.Headless,
                mode = TrackerMode.Background,
                tunables = tunables,
                requireLinkedSteamId = true,
                minWriteTokenLifetime = MIN_WRITE_TOKEN_LIFETIME,
            ),
            marketplace = KtorMarketplaceClient(
                httpClient = marketplaceHttp,
                baseUrl = config.dmarketBaseUrl,
                authenticator = TradingApiAuthenticator({ secrets.dmarketKey }, clock, platform.signerFor),
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
        /**
         * Reported as the heartbeat's `clientVersion` and in health: the library's own SemVer, which the backend
         * compares against its minimum for platform `sdk`.
         */
        const val VERSION: String = TradeTrackerCore.VERSION

        /** Under this much token life no create starts: the seller could not confirm it before the session ends. */
        val MIN_WRITE_TOKEN_LIFETIME: Duration = 30.minutes

        /** How often an SDK that may not act yet re-checks, besides every push. */
        val IDLE_RECHECK: Duration = 30.seconds

        /** 12 random bytes, the size Steam's own clients generate for `sessionid`. */
        private const val SESSION_ID_BYTES = 12
    }
}
