package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.client.HttpTransportException
import com.dmarket.p2p.tracker.client.marketplace.RateLimitedException
import com.dmarket.p2p.tracker.engine.TrackerBlock
import com.dmarket.p2p.tracker.model.marketplace.Deal
import com.dmarket.p2p.tracker.model.marketplace.DealActionResult
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceServerErrorException
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceUnauthorizedException
import com.dmarket.p2p.tracker.port.steam.CreateOfferResult
import io.ktor.client.plugins.HttpRequestTimeoutException
import kotlinx.io.IOException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What [P2pTracker.startDelivery] did for a deal (S-14).
 *
 * @property result the create's result, as the extension gets it from the same path.
 * @property reasonCode the backend's refusal of the delivery (`DeliveryWindowClosed`), set with a
 *   [CreateOfferResult.Failed] when the last `create-trade` call carried one; nothing reached Steam.
 * @property error why the last `create-trade` call got no answer, set with a [CreateOfferResult.Failed] when that call
 *   failed; `null` with a [CreateOfferResult.Failed] and no [reasonCode] means the backend answered without a
 *   directive (the next sync brings it, if there is one).
 */
data class DeliveryOutcome(val result: CreateOfferResult, val reasonCode: String? = null, val error: DealCallError? = null)

/** What [P2pTracker.acceptDeal] did. */
sealed interface AcceptOutcome {
    /** The backend answered the accept; [result]'s `applied` is `false` when the accept window had closed. */
    data class Done(val result: DealActionResult) : AcceptOutcome

    /** The sync left the tracker blocked by [block]; no accept was sent. */
    data class Blocked(val block: TrackerBlock) : AcceptOutcome

    /** The accept got no answer; [error] says why. */
    data class Failed(val error: DealCallError) : AcceptOutcome
}

/** What [P2pTracker.getDeal] read. */
sealed interface DealRead {
    data class Found(val deal: Deal) : DealRead

    /** The read got no answer; [error] says why. */
    data class Failed(val error: DealCallError) : DealRead
}

/**
 * Why a DMarket deal call got no answer the app can use. [statusCode] and [errorCode] are what the gateway sent, for
 * the app to classify the way it classifies its own calls.
 *
 * @property statusCode the HTTP status; `null` when no reply came (network, timeout) or no call was made.
 * @property errorCode the gateway's `P2P_…` code from the reply's body, when it carried one.
 * @property retryAfter the 429's `Retry-After`, when it sent one.
 */
data class DealCallError(
    val kind: DealCallErrorKind,
    val statusCode: Int? = null,
    val errorCode: String? = null,
    val retryAfter: Duration? = null,
)

enum class DealCallErrorKind {
    /** 429. */
    RATE_LIMITED,

    /** 401 the token refresh did not fix: the app's DMarket session is gone. */
    UNAUTHORIZED,

    /** 404, 405 or 501 without a P2P code: the route is not served in this environment. */
    UNAVAILABLE,

    /** Any other 4xx or 5xx; [DealCallError.errorCode] names it when the gateway did. */
    SERVER,

    /** The connection failed. */
    NETWORK,

    /** No reply within the request timeout. */
    TIMEOUT,

    /** No tracker runs: the app's user links no Steam account, or its loop did not start. No call was made. */
    NOT_RUNNING,

    /** The reply could not be read, or the call failed in a way none of the above names. */
    OTHER,
}

private val UNSERVED_ROUTE = setOf(404, 405, 501)

/** The [DealCallError] a failed deal call threw. */
internal fun Throwable.toDealCallError(): DealCallError = when (this) {
    is RateLimitedException -> DealCallError(DealCallErrorKind.RATE_LIMITED, 429, retryAfter = retryAfterSeconds?.seconds)
    is MarketplaceUnauthorizedException -> DealCallError(DealCallErrorKind.UNAUTHORIZED, 401)
    is MarketplaceServerErrorException -> DealCallError(
        if (errorCode == null && statusCode in UNSERVED_ROUTE) DealCallErrorKind.UNAVAILABLE else DealCallErrorKind.SERVER,
        statusCode,
        errorCode,
    )
    is HttpRequestTimeoutException -> DealCallError(DealCallErrorKind.TIMEOUT)
    is HttpTransportException -> DealCallError(if (kind == "timeout") DealCallErrorKind.TIMEOUT else DealCallErrorKind.NETWORK)
    // OkHttp's SocketTimeoutException is an IOException too; its name is all common code can see of it.
    is IOException -> DealCallError(
        if ("Timeout" in
            (this::class.simpleName ?: "")
        ) {
            DealCallErrorKind.TIMEOUT
        } else {
            DealCallErrorKind.NETWORK
        },
    )
    else -> DealCallError(DealCallErrorKind.OTHER)
}
