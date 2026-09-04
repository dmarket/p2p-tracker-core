package com.dmarket.p2p.tracker.model.steam

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.time.Instant

/**
 * One entry of Steam's notification stream, reduced to the only three fields reversal attribution needs.
 *
 * Deliberately narrow: the upstream payload carries unrelated personal traffic (comments, friend
 * invites, gifts, support messages), and none of it may travel further into the library. The reader
 * actual maps to this type at the IO edge and discards the rest, so no other component can see it.
 *
 * @property type Steam's `notification_type`. Reversal attribution matches only [REVERSAL_TYPE].
 * @property actorAccountId the **32-bit** account id of whoever caused the notification; expand with
 *   [ACCOUNT_ID_OFFSET] to compare against a steamid64.
 * @property timestamp the notification's own timestamp, matched for exact equality against the
 *   rolled-back transfer's `time_mod`.
 */
data class SteamNotification(val type: Int, val actorAccountId: Long, val timestamp: Instant) {
    companion object {
        /** `notification_type` of a trade-reversal notification. */
        const val REVERSAL_TYPE: Int = 29

        /** steamid64 = this + a 32-bit account id. */
        const val ACCOUNT_ID_OFFSET: Long = 76_561_197_960_265_728L
    }

    /** This notification's actor as a full steamid64. */
    val actorSteamId: SteamId get() = SteamId((actorAccountId + ACCOUNT_ID_OFFSET).toString())
}

/**
 * Picks the notification that names who reversed a trade — or nothing at all.
 *
 * Pure, zero-IO, no clock. The rule is exact and has **no tolerance** by design, because the output is
 * attached to a money-sensitive transition: a wrong actor is far worse than no actor. Ambiguity is
 * therefore resolved as "undecided", never as a best guess.
 */
object ReversalAttribution {

    /**
     * The reversal actor for a transfer, or `null` when it cannot be determined.
     *
     * Requires, with no fuzziness: [SteamNotification.REVERSAL_TYPE]; an actor equal to
     * [counterparty] once expanded to a steamid64; a timestamp **exactly** equal to [modifiedAt]; and
     * exactly one **distinct actor** among the notifications satisfying all three. Zero matches, or
     * several naming different actors ⇒ `null`.
     *
     * Ambiguity is measured in **actors, not in rows**. A rollback is all-or-nothing over the initiator's
     * whole protected set and Steam raises one notification per affected trade, so several trades undone
     * in one sweep share the reversal instant and a deal legitimately sees several notifications that are
     * indistinguishable under this filter. Counting rows rejected those as ambiguous — yet the filter has
     * already pinned the actor to [counterparty], so every match names the same party and there was never
     * anything to be ambiguous about. Counting distinct actors keeps the money-safe intent (disagreement
     * about who acted is still undecided) without discarding an answer the filter itself proved unanimous.
     *
     * A `null` [modifiedAt] or [counterparty] is itself undecidable and returns `null` without
     * inspecting the stream — the caller must send nothing rather than fall back to a guess.
     */
    fun resolve(notifications: List<SteamNotification>, counterparty: SteamId?, modifiedAt: Instant?): SteamId? {
        if (counterparty == null || modifiedAt == null) return null
        val actors = notifications
            .filter { candidate ->
                candidate.type == SteamNotification.REVERSAL_TYPE &&
                    candidate.actorSteamId == counterparty &&
                    candidate.timestamp == modifiedAt
            }
            .map { it.actorSteamId }
            .distinct()
        return actors.singleOrNull()
    }
}
