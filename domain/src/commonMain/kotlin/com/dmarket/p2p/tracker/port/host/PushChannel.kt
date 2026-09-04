package com.dmarket.p2p.tracker.port.host

import com.dmarket.p2p.tracker.model.PushSignal
import kotlinx.coroutines.flow.Flow

/**
 * The backend→client push channel — a transport-agnostic wake-up seam. A signal here drives an
 * immediate tick instead of waiting for the next scheduled poll. The concrete transport is the
 * host's choice, wired at the platform edge (e.g. APNs silent push + Live Activities on iOS, FCM
 * data messages on Android, a web push transport on the extension) — **not Centrifugo**. Greenfield:
 * the reference has no push at all.
 */
interface PushChannel {
    /** Inbound wake-up signals from the backend. */
    val signals: Flow<PushSignal>

    /** Register this device's push token with the backend transport. */
    suspend fun register(deviceToken: String)
}
