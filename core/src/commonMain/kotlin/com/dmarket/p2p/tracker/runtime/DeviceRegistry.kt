package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.marketplace.ApnsTarget
import com.dmarket.p2p.tracker.model.marketplace.DeviceRegistration
import com.dmarket.p2p.tracker.model.marketplace.PushProvider
import com.dmarket.p2p.tracker.port.marketplace.DeviceRegistryClient

/**
 * What the core puts in a push device registration (contract d455888 §3.2) beside the app's token: the platform, the
 * app's version and what this build does.
 *
 * [capabilities] are only what the build can do: `wake_v1` (the app shows its own notification and the core acks,
 * §2.3), `steam_writes` when the tracker writes Steam offers, and `background_prove` when it has the native prover
 * ([backgroundProve]). No `wake_v2` (needs the early-access FCM SDK) and no `buyer_reports` (the buyer reporter exists,
 * but the backend has not said what the flag means yet): the backend counts a device reachable on them.
 */
class DeviceRegistry(
    private val client: DeviceRegistryClient,
    private val platform: String,
    private val clientVersion: String,
    steamWrites: Boolean,
    backgroundProve: Boolean = false,
) {
    val capabilities: List<String> =
        listOfNotNull(WAKE_V1, STEAM_WRITES.takeIf { steamWrites }, BACKGROUND_PROVE.takeIf { backgroundProve })

    suspend fun register(deviceId: DeviceId, pushToken: String, provider: PushProvider, apns: ApnsTarget?) {
        require(pushToken.isNotBlank()) { "a push token is required" }
        require((provider == PushProvider.APNS) == (apns != null)) { "apnsEnvironment and apnsTopic go with an APNs token only" }
        val registration = DeviceRegistration(
            platform = platform,
            pushProvider = provider,
            // FCM registration tokens for now (the owner's decision); a FID build would send `fid`.
            pushTokenType = TOKEN.takeIf { provider == PushProvider.FCM },
            pushToken = pushToken,
            clientVersion = clientVersion,
            capabilities = capabilities,
            apns = apns,
        )
        client.registerDevice(deviceId, registration)
    }

    suspend fun unregister(deviceId: DeviceId) = client.unregisterDevice(deviceId)

    companion object {
        const val WAKE_V1 = "wake_v1"
        const val STEAM_WRITES = "steam_writes"
        const val BACKGROUND_PROVE = "background_prove"
        private const val TOKEN = "token"
    }
}
