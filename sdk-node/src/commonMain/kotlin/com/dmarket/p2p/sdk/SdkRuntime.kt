package com.dmarket.p2p.sdk

import com.dmarket.p2p.sdk.api.ManagementActions
import com.dmarket.p2p.sdk.api.PushOutcome
import com.dmarket.p2p.sdk.health.Health
import com.dmarket.p2p.sdk.health.HealthTracker
import com.dmarket.p2p.tracker.client.marketplace.platformEd25519Signer
import com.dmarket.p2p.tracker.config.TrackerConfig
import com.dmarket.p2p.tracker.credential.marketplace.KeyChange
import com.dmarket.p2p.tracker.credential.marketplace.TradingKeyPushResult
import com.dmarket.p2p.tracker.credential.steam.SteamPushResult
import com.dmarket.p2p.tracker.model.marketplace.TradingApiKey
import com.dmarket.p2p.tracker.port.host.Clock
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.EventObserver
import com.dmarket.p2p.tracker.port.marketplace.Ed25519Signer
import com.dmarket.p2p.tracker.runtime.HeadlessPlatform
import com.dmarket.p2p.tracker.runtime.HeadlessTracker
import com.dmarket.p2p.tracker.runtime.TradeTrackerCore
import io.ktor.client.engine.HttpClientEngine
import kotlinx.coroutines.CoroutineScope

/**
 * What the SDK needs from the platform it runs on.
 *
 * @property storage where the loop's progress, claims, throttles and device id persist. No secret is ever
 *   written to it.
 * @property httpEngine the HTTP engine for both DMarket and Steam; `null` takes the platform's default.
 * @property signerFor builds the Ed25519 signer for a pushed key; throws when the key cannot sign. The platform's
 *   own by default.
 */
class SdkPlatform(
    val storage: DeviceKeyValueStore,
    val clock: Clock,
    val log: SdkLog,
    val httpEngine: HttpClientEngine? = null,
    val signerFor: suspend (TradingApiKey) -> Ed25519Signer = ::platformEd25519Signer,
)

/**
 * The SDK: the library's [HeadlessTracker] behind the management API. The tracker holds the pushed secrets and
 * runs the loop; this answers the API's pushes, and feeds [HealthTracker] and [SdkLog] from the pushes and the
 * loop's events.
 */
class SdkRuntime(config: SdkConfig, platform: SdkPlatform, scope: CoroutineScope, tunables: TrackerConfig = TrackerConfig.defaults()) :
    ManagementActions {
    private val log = platform.log
    private val health = HealthTracker(platform.clock, version = VERSION)

    internal val tracker = HeadlessTracker(
        platform = HeadlessPlatform(
            storage = platform.storage,
            clock = platform.clock,
            eventObserver = EventObserver { event ->
                health.onEvent(event)
                log.onEvent(event)
            },
            httpEngine = platform.httpEngine,
            signerFor = platform.signerFor,
        ),
        scope = scope,
        baseUrl = config.dmarketBaseUrl,
        tunables = tunables,
    )

    /** Starts the tracker, which waits for the bot's pushes before it heartbeats. */
    fun start() = tracker.start()

    override suspend fun putDmarketKey(publicKey: String, secretKey: String): PushOutcome =
        when (val push = tracker.pushTradingApiKey(publicKey, secretKey)) {
            is TradingKeyPushResult.Invalid -> {
                log.warn("dmarket key refused", "reason" to push.reason)
                PushOutcome.Invalid("INVALID_KEY", push.reason)
            }
            is TradingKeyPushResult.Held -> {
                health.keyHeld()
                when (push.change) {
                    KeyChange.FIRST -> log.info("dmarket key pushed", "publicKey" to push.publicKey)
                    KeyChange.ROTATED -> log.info("dmarket key rotated", "publicKey" to push.publicKey)
                    KeyChange.REPLACED -> log.info("dmarket key replaced", "publicKey" to push.publicKey)
                    KeyChange.SAME -> Unit
                }
                PushOutcome.Held
            }
            is TradingKeyPushResult.AccountChanged -> {
                health.keyRefused(push.linkedSteamId, push.keyLinkedSteamId)
                val theirs = push.keyLinkedSteamId?.let { "Steam account ${it.value}" } ?: "no Steam account"
                log.warn(
                    "dmarket key for another account refused",
                    "publicKey" to push.publicKey,
                    "heldPublicKey" to push.heldPublicKey,
                    "linkedSteamId" to push.linkedSteamId.value,
                    "keyLinkedSteamId" to push.keyLinkedSteamId?.value,
                )
                PushOutcome.Conflict(
                    "DMARKET_KEY_ACCOUNT_CHANGED",
                    "the key's account links $theirs, not ${push.linkedSteamId.value}; the held key stays: switching accounts takes a restart",
                )
            }
            is TradingKeyPushResult.Unverified -> {
                log.warn("dmarket key unchecked", "publicKey" to push.publicKey, "reason" to push.reason)
                PushOutcome.Unavailable("DMARKET_UNREACHABLE", "the key could not be checked: ${push.reason}; the held key stays")
            }
        }

    override suspend fun putSteamToken(accessToken: String): PushOutcome = when (val push = tracker.pushSteamToken(accessToken)) {
        is SteamPushResult.Invalid -> {
            log.warn("steam token refused", "reason" to push.reason)
            PushOutcome.Invalid("INVALID_TOKEN", push.reason)
        }
        is SteamPushResult.Held -> {
            health.tokenHeld()
            log.info("steam token held", "steamId" to push.steamId.value, "expiresAt" to push.expiresAt.toString())
            PushOutcome.Held
        }
        is SteamPushResult.WrongAccount -> {
            health.tokenRefused(push.tokenSteamId, push.linkedSteamId)
            val expected = push.linkedSteamId?.value ?: "the account of the token already held"
            log.warn("steam token for another account refused", "tokenSteamId" to push.tokenSteamId.value, "expected" to expected)
            PushOutcome.Conflict("STEAM_WRONG_ACCOUNT", "the token is for ${push.tokenSteamId.value}, not $expected")
        }
    }

    override fun health(): Health = health.snapshot(tracker.state())

    companion object {
        /**
         * Reported as the heartbeat's `clientVersion` and in health: the library's own SemVer, which the backend
         * compares against its minimum for platform `sdk`.
         */
        const val VERSION: String = TradeTrackerCore.VERSION
    }
}
