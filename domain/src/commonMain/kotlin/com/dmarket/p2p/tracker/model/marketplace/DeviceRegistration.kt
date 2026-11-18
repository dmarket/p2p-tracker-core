package com.dmarket.p2p.tracker.model.marketplace

/**
 * The body of a push device registration (contract d455888 §3.2). The core fills [platform], [clientVersion] and
 * [capabilities]; the app gives the token and its provider.
 *
 * @property platform `android` | `ios`, the heartbeat's value.
 * @property pushTokenType `token` | `fid`, FCM only; `null` for APNs.
 * @property capabilities what this build does: `wake_v1`, `steam_writes`, … (a closed list on the backend).
 * @property apns the APNs endpoint and topic, required for [PushProvider.APNS] and refused otherwise (Q15).
 */
data class DeviceRegistration(
    val platform: String,
    val pushProvider: PushProvider,
    val pushTokenType: String?,
    val pushToken: String,
    val clientVersion: String,
    val capabilities: List<String>,
    val apns: ApnsTarget? = null,
) {
    override fun toString(): String = "DeviceRegistration(platform=$platform, pushProvider=$pushProvider, pushTokenType=$pushTokenType, " +
        "pushToken=<redacted>, clientVersion=$clientVersion, capabilities=$capabilities, apns=$apns)"
}

/** The push provider a device registers with; HMS is not supported (§3.2). */
enum class PushProvider(val wireName: String) {
    FCM("fcm"),
    APNS("apns"),
}

/**
 * The APNs target of an iOS registration.
 *
 * @property environment `sandbox` (an Xcode-run build) or `production` (TestFlight, App Store).
 * @property topic the build's bundle id.
 */
data class ApnsTarget(val environment: String, val topic: String)
