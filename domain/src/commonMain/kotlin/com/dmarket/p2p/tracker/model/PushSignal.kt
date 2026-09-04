package com.dmarket.p2p.tracker.model

/**
 * A wake-up signal from the backend's push channel, independent of the concrete transport (a web push
 * transport, APNs on iOS, FCM on Android — **not Centrifugo**).
 *
 * Push is a **later optimization** (v1 is poll-only). Under the C1 contract, a signal just nudges
 * the loop to run a cycle now instead of waiting for the next scheduled wake;
 * it is kept behind a NoOp default until the push contract is finalized.
 */
sealed interface PushSignal {
    /** Re-check a specific deal now (e.g. the buyer just accepted the trade on Steam). */
    data class WakeForDeal(val dealId: DealId) : PushSignal

    /** Run a full cycle now (e.g. a new deal or directive appeared). */
    data object WakeAll : PushSignal
}
