package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.TradeId
import kotlin.time.Instant

/**
 * The `POST /heartbeat` body (golden `HeartbeatRequest`). Presence +
 * directive transport only — it carries **no** inventory data and **no** account id (the backend reads
 * the account from the Bearer token). [steamId] must equal the account's Steam id (wrong-account
 * guard); [deviceId] is install-scoped and persistent (the directive-lease key).
 */
data class HeartbeatRequest(
    val clientVersion: String,
    val platform: String,
    val foreground: Boolean,
    val steamId: SteamId,
    val deviceId: DeviceId,
)

/** Which Steam read the backend asks the tracker to perform for a watched deal (`TrackedDeal.watch`). */
enum class WatchTarget(val wireName: String) {
    GET_TRADE_OFFER("GetTradeOffer"),
    GET_TRADE_STATUS("GetTradeStatus"),
    GET_TRADE_HISTORY("GetTradeHistory"),
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(name: String?): WatchTarget = entries.firstOrNull { it.wireName == name } ?: UNKNOWN
    }
}

/**
 * Which side of a watched deal this device's account is on (`TrackedDeal.role`).
 *
 * The backend indexes the watch instruction under **both** participants' Steam ids — both sides observe
 * the same Steam trade, so either can report it — which means one heartbeat's `active_tracking[]`
 * legitimately mixes deals this account is selling with deals it is buying. The side is therefore a
 * property of the *entry*, never of the session.
 *
 * Only the **watch** is two-sided. `create_offer` / `cancel_offer` are leased to the seller alone,
 * because the buyer must never be instructed to write to Steam on the deal — that is what [BUYER] is
 * load-bearing for ([com.dmarket.p2p.tracker.engine.DealRoleBinding]).
 *
 * [UNKNOWN] means "no opinion", never "buyer": an entry that omits the field (or a backend that does not
 * send it yet) must not have its writes refused. Same fail-open rule as
 * [com.dmarket.p2p.tracker.engine.AccountBinding].
 */
enum class DealRole(val wireName: String) {
    SELLER("seller"),
    BUYER("buyer"),
    UNKNOWN("unknown"),
    ;

    companion object {
        /**
         * Tolerant by design: this field is **not in the frozen contract yet** (the live backend serves it
         * ahead of the golden `TrackedDeal`), so its final spelling is not settled. Matching
         * case-insensitively on the last `_`-separated segment reads `buyer`, `BUYER` and a proto-style
         * `ROLE_BUYER` alike — and mis-reading a `buyer` as [UNKNOWN] is the one direction that fails
         * *open* on a Steam write, so this is the cheap half of that guard.
         */
        fun fromWire(name: String?): DealRole {
            val token = name?.lowercase()?.substringAfterLast('_') ?: return UNKNOWN
            return entries.firstOrNull { it.wireName == token } ?: UNKNOWN
        }
    }
}

/**
 * A deal the backend wants the tracker to watch (golden `TrackedDeal`).
 * [proofRequired] flips this deal from client-reported (raw codes only) to proof-enforced (decisive
 * transitions must carry a TLSN `SubmitProof`). [role] is which side of this deal we are on.
 */
data class TrackedDeal(
    val dealId: DealId,
    val steamOfferId: OfferId? = null,
    val watch: Set<WatchTarget> = emptySet(),
    val proofRequired: Boolean = false,
    val role: DealRole = DealRole.UNKNOWN,
    /**
     * The last **offer-axis** Steam code the backend has recorded and considers **settled** for this deal, or
     * `null` when it has none — which is also every backend that does not send the field.
     *
     * The two halves of that wording are the contract, and only one of them is safe to get wrong: a value
     * *behind* reality costs a report the backend discards, while a value *ahead* of it — sent while a proof
     * is still outstanding, say — silently suppresses a report the backend needs and stalls the deal. Nothing
     * downstream can catch the second case, which is why the field is nullable rather than defaulted to a code.
     *
     * What the client does with it, and why there is no history-axis counterpart, is
     * [com.dmarket.p2p.tracker.engine.BaselineSeed]'s to explain.
     */
    val lastOfferCode: Int? = null,
    /**
     * Steam's own `tradeid` for the trade this deal settled into, or `null` when the backend has none —
     * which is also every deal that has not been accepted yet, and every backend that does not send the
     * field.
     *
     * **Load-bearing rather than convenient, and this is the reason it is on the wire at all.** The history
     * axis's proven read addresses one trade by this id (`GetTradeStatus?tradeid=…`), and by the time a
     * protection hold expires the client has no local source left for it: the trade is days old, the
     * account-wide history read is bounded to `historyMaxTrades`, and Steam may no longer list the offer.
     * A [proveAfter] mark therefore has to name the trade it wants proven, because the client cannot
     * re-derive it.
     *
     * Preferred over a locally observed id for a demanded proof: it is the backend's own statement of which
     * trade it stamped the mark for, whereas the local join can match a *different* trade of the same asset
     * after a rollback (see `TradeTrackerLoop.correlateTransfer`) — which is exactly the scenario the mark
     * exists for.
     */
    val steamTradeId: TradeId? = null,
    /**
     * The instant a proof of this deal's watch axis must be attested at or **after** — the backend's
     * *freshness mark*, stamped when a protection hold expires so the payout is released against a
     * confirmation that is current rather than one recorded days earlier. `null` means no demand, and that
     * is also what every absent, blank, unparseable or epoch value degrades to (see the mapper), so the
     * pre-mark behaviour is the default in every direction.
     *
     * **The unsafe direction is the reverse of [lastOfferCode]'s, which is why this field is honoured
     * whenever it is present and greater than the mark this device has already satisfied.** A mark the
     * client ignores strands a settlement the backend will not release, with the seller's funds locked and
     * nothing downstream able to notice; a mark it answers needlessly costs one MPC session. So there is no
     * `proofRequired` conjunct and no `watch` conjunct on acting: the mark *is* the request, and a flag or a
     * `watch` spelling lagging behind it would park a payout indefinitely.
     *
     * Truncated to whole milliseconds by the mapper. Everything this client persists round-trips through
     * epoch millis, so a mark stored at nanosecond precision would read back strictly *less* than the same
     * mark re-parsed from the next heartbeat, and the monotone latch that stops it being re-proven would
     * never hold. What the client does with it is `ProofFreshness`'s to explain.
     */
    val proveAfter: Instant? = null,
)

/**
 * Whether the backend named [source]'s axis in this deal's [TrackedDeal.watch] list.
 *
 * Strict, and the polling path wants it that way: a deal the backend did not ask to be watched on an axis
 * must not cost a Steam read on it. [mayProveOn] is the fail-open sibling for the proof gate — read its
 * note before reusing this one there.
 */
fun TrackedDeal.watches(source: TradeStatusSource): Boolean = when (source) {
    TradeStatusSource.OFFER -> WatchTarget.GET_TRADE_OFFER in watch
    // Both spellings mean the transfer axis. The backend sends `GetTradeHistory`; `GetTradeStatus` is the
    // endpoint the *proven* read uses for it, and either naming has been observed on the wire.
    TradeStatusSource.HISTORY ->
        WatchTarget.GET_TRADE_HISTORY in watch || WatchTarget.GET_TRADE_STATUS in watch
}

/**
 * Whether a decisive transition observed on [source] should be witnessed by a proof for this deal.
 *
 * **Why this gate exists.** The offer axis is polled for every tracked deal — the account-wide list read
 * answers for all of them at once, so an offer code is observed whether or not the backend asked for that
 * axis. Nothing used to stop that observation from raising a proof intent, so a deal the backend was
 * watching on the transfer axis had an *offer* proof produced and submitted for it, which the backend
 * rejects as the wrong read ("the proof covers the sent-offers list, not the trade history" — observed live
 * on 2026-09-03 for a `watch=[GetTradeHistory]` deal sitting at offer code 3). Proof-before-report then
 * withholds that transition's report behind the rejected proof, so the deal stalls on an axis it was never
 * meant to be proven on. The report itself stays unconditional — see [TrackedDeal.lastOfferCode], which is
 * the backend's own offer-axis baseline and therefore evidence that it consumes offer reports for deals it
 * watches elsewhere.
 *
 * **Fail-open when the deal names no axis this build recognises**, i.e. an empty list or nothing but
 * [WatchTarget.UNKNOWN]. A strict gate would let a backend that stops sending `watch`, or renames a target,
 * silently stop producing every proof — and a proof that is never produced is a deal that never settles,
 * with nothing on either side saying why. Losing one wrong-axis proof is cheap; losing all of them is not.
 *
 * Deliberately NOT applied to a **demanded** proof ([TrackedDeal.proveAfter]): that mark is honoured with no
 * `watch` conjunct at all, on purpose — see its own KDoc.
 */
fun TrackedDeal.mayProveOn(source: TradeStatusSource): Boolean = watch.none { it != WatchTarget.UNKNOWN } || watches(source)

/**
 * The `POST /heartbeat` response (golden `HeartbeatResponse`):
 * [activeTracking] (deals to watch), [directives] (one-shot leased commands), and [ttlSeconds] (the
 * presence TTL the client schedules its next heartbeat from). [directives] is empty until the backend
 * `device_id` lease is live (the launch gate).
 *
 * [linkedSteamId] is the Steam id the backend has bound to this DMarket account. The client compares it
 * against the Steam id of the token it holds (`SteamCredential.subjectSteamId`) to detect a wrong-account
 * session — the browser logged into a *different* Steam account than the DMarket profile is linked to —
 * and blocks all Steam activity until they agree. `null` when the backend hasn't supplied it (older
 * backend), which is treated as "unknown", never a mismatch.
 */
data class HeartbeatResponse(
    val activeTracking: List<TrackedDeal> = emptyList(),
    val directives: List<Directive> = emptyList(),
    val serverTime: Instant? = null,
    val ttlSeconds: Int = 0,
    val linkedSteamId: SteamId? = null,
)
