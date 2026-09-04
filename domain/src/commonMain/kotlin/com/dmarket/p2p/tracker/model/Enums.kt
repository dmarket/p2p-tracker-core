package com.dmarket.p2p.tracker.model

/**
 * The client runtime a tracker instance is running in.
 *
 * On the wire the heartbeat collapses these to `"web" | "ios" | "android"` — the
 * Chrome/Firefox split is kept only on-device for cadence floors and proof-placement decisions.
 */
enum class RuntimeSurface {
    WebChrome,
    WebFirefox,
    IosNative,
    AndroidNative,
    ;

    val isWeb: Boolean get() = this == WebChrome || this == WebFirefox
    val isMobile: Boolean get() = this == IosNative || this == AndroidNative

    /** The `platform` value sent in the heartbeat (`web | ios | android`). */
    val platformWireName: String
        get() = when (this) {
            WebChrome, WebFirefox -> "web"
            IosNative -> "ios"
            AndroidNative -> "android"
        }
}

/** Whether the tracker is currently driven by a user-visible session or a background tick. */
enum class TrackerMode {
    Foreground,
    Background,
}
