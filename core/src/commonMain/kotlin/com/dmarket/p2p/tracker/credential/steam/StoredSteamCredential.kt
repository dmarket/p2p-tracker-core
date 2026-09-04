package com.dmarket.p2p.tracker.credential.steam

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Serialization DTO for persisting a [SteamCredential] in flat key-value stores such as
 * `chrome.storage.local`.
 *
 * Uses flat primitives (epoch-millisecond `Long`, plain-string Steam ID) to avoid the
 * `@Serializable` requirement on [Instant] and [SteamId]. Round-trips cleanly through
 * `kotlinx.serialization.json.Json`.
 */
@Serializable
data class StoredSteamCredential(
    val token: String,
    @SerialName("steam_id") val steamId: String,
    @SerialName("expires_at_ms") val expiresAtMs: Long,
) {
    fun toDomain(): SteamCredential = SteamCredential(
        token = token,
        subjectSteamId = SteamId(steamId),
        expiresAt = Instant.fromEpochMilliseconds(expiresAtMs),
    )

    /**
     * Redacted: the Steam JWT must never reach a log line or an exception message. Serialization is
     * unaffected — the persisted JSON is generated from the properties and still carries the token,
     * which is the point of this DTO.
     */
    override fun toString(): String = "StoredSteamCredential(token=<redacted>, steamId=$steamId, expiresAtMs=$expiresAtMs)"

    companion object {
        fun from(credential: SteamCredential): StoredSteamCredential = StoredSteamCredential(
            token = credential.token,
            steamId = credential.subjectSteamId.value,
            expiresAtMs = credential.expiresAt.toEpochMilliseconds(),
        )
    }
}

/**
 * String keys used by all platform [com.dmarket.p2p.tracker.port.host.CredentialVault] implementations
 * when reading from / writing to a key-value store.
 *
 * Shared here (in `:core` commonMain rather than each `jsMain`/`jvmMain` actual) so the on-disk
 * shape is testable on the JVM.
 */
object DeviceVaultKeys {
    /** Stores the JSON-encoded [StoredSteamCredential]. */
    const val STEAM_CREDENTIAL = "steam_credential"

    /**
     * Stores the JSON-encoded per-deal last-reported Steam codes (offer/history) so the watch loop only
     * re-POSTs `/trade-events` on a *change*, across MV3 service-worker respawns. See
     * `WebExtStorageTrackerProgressStore`.
     */
    const val TRACKER_REPORTED = "tracker_reported"

    /**
     * Stores the JSON-encoded set of handled `directive_id`s so a leased directive is executed at most
     * once (single-flight), across respawns. See `WebExtStorageTrackerProgressStore`.
     */
    const val TRACKER_HANDLED_DIRECTIVES = "tracker_handled_directives"

    /**
     * Stores the JSON-encoded map of handled-but-unacknowledged directive outcomes (keyed by
     * `directive_id`) so a failed `/trade-actions` report can be re-sent when the backend re-leases
     * the directive; pruned once accepted. See `WebExtStorageTrackerProgressStore`.
     */
    const val TRACKER_DIRECTIVE_OUTCOMES = "tracker_directive_outcomes"

    /**
     * Stores the JSON-encoded list of transitions the backend has answered `verified = true` for, with when
     * — so a proof it already holds is not re-minted on every cycle while the report it corroborates keeps
     * being refused (`P2P_PROOF_REQUIRED`). A re-proof is a full MPC session, so this bounds *cost*, not
     * duplicate work, and it must survive respawns or the bound does not bind: a worker restarts between
     * most cycles. Pruned when the report is accepted, when a fresh proof is refused, and when the deal
     * leaves the tracked set. See `WebExtStorageTrackerProgressStore` and `NotaryConfig.acceptedProofTtlMs`.
     */
    const val TRACKER_ACCEPTED_PROOFS = "tracker_accepted_proofs"

    /**
     * Stores the JSON-encoded per-deal online-decryption budgets learned from a refused proof, so the MPC
     * session that bought each lesson is spent once rather than on every wake — a worker respawns between
     * most cycles, so an in-memory-only value would be re-learned almost every time. Pruned when the deal
     * leaves the tracked set. See `WebExtStorageTrackerProgressStore` and `OnlineBudgetLesson`.
     */
    const val TRACKER_ONLINE_BUDGETS = "tracker_online_budgets"

    /**
     * Stores the JSON-encoded per-deal standing against the backend's `prove_after` freshness mark (DMA-280):
     * the greatest mark a verified proof has satisfied, plus the retry ladder for a refused one.
     *
     * Persisted because the *demand* is not — the tracking list that carries the mark is in-memory and
     * re-presented by every heartbeat and every watch-only wake, so an in-memory satisfaction would be
     * re-armed on nearly every respawn at one full MPC session each. Written only on the backend's
     * `verified = true`, and not cleared by a forced heartbeat. Pruned when the deal leaves the tracked set.
     * Delete a row to make the client re-answer the mark on its next cycle. See
     * `WebExtStorageTrackerProgressStore` and `ProofFreshness`.
     */
    const val TRACKER_PROVE_AFTER = "tracker_prove_after"

    /**
     * Stores the JSON-encoded list of live **deal-keyed write claims** (`create_offer` / `cancel_offer`
     * already performed or in flight per deal) so the duplicate guard survives a process death — an MV3
     * service-worker respawn, an Android process kill, an iOS relaunch. See `PersistedDealWriteClaimStore`.
     */
    const val DEAL_WRITE_CLAIMS = "tracker_deal_write_claims"

    /**
     * Stores the JSON-encoded `create_offer` back-pressure state (per-partner cooldowns after a Steam
     * rate-limit refusal, plus the surface-wide breaker) so a cooldown outlives a process death. The
     * heartbeat TTL is shorter than the MV3 idle timeout, so a worker respawns between most cycles — an
     * in-memory-only cooldown would be forgotten on every wake. See `PersistedSteamWriteThrottleStore`.
     */
    const val STEAM_WRITE_THROTTLE = "tracker_steam_write_throttle"

    /**
     * Stores the JSON-encoded proof-generation back-pressure state (until when proving is parked after
     * repeated failures, plus the escalation ladder) so a cooldown outlives a process death.
     *
     * Persisted for the same reason as [STEAM_WRITE_THROTTLE], and the cost it bounds is larger: every attempt
     * is a full MPC session, measured at ~30 MB uploaded to the notary, and a worker respawns between most
     * cycles — so an in-memory-only cooldown would re-spend that on every wake. All three fields in one row
     * because they are written by the same fold; splitting them makes a half-updated ladder representable.
     * See `PersistedNotaryProofThrottleStore`.
     */
    const val NOTARY_PROOF_THROTTLE = "tracker_notary_proof_throttle"

    /**
     * Stores the install-scoped persistent `device_id` (the directive-lease key) so it survives token
     * refresh / re-login / restart. See `WebExtStorageDeviceIdStore`.
     */
    const val DEVICE_ID = "device_id"

    /**
     * Stores the epoch-millis at which the next presence heartbeat is due, so its own (`ttl_seconds`)
     * cadence survives a service-worker respawn. See `WebExtStorageLoopStateStore`.
     */
    const val LOOP_HEARTBEAT_AT = "loop_next_heartbeat_at_ms"

    /**
     * Stores the epoch-millis until which the expedited-poll window is open, so a just-created offer's
     * fast cadence survives a service-worker respawn. See `WebExtStorageLoopStateStore`.
     */
    const val LOOP_EXPEDITED_UNTIL = "loop_expedited_until_ms"

    /** When the sparse revert watch (`GetTradeHistory`) last ran; gates it across service-worker respawns. */
    const val LOOP_REVERT_WATCH_AT = "loop_revert_watch_at_ms"

    /**
     * Stores the count of consecutive heartbeat server-error failures, so a persistent outage still
     * crosses the debounce threshold when the service worker dies between retries. See
     * `WebExtStorageLoopStateStore`.
     */
    const val LOOP_SERVER_ERROR_COUNT = "loop_server_error_count"

    /**
     * Stores whether the last cycle found no Steam web session, so the host's blocking prompt survives
     * a service-worker respawn — an in-memory flag would read as "nothing is blocking" on the first
     * wake of every respawn. See `WebExtStorageLoopStateStore`.
     */
    const val LOOP_STEAM_SESSION_MISSING = "loop_steam_session_missing"

    /**
     * Stores whether Steam has already been asked to mint a new session during the current
     * missing-session episode, so one attempt stays one attempt across service-worker respawns.
     * See `WebExtStorageLoopStateStore`.
     */
    const val LOOP_STEAM_MINT_ATTEMPTED = "loop_steam_mint_attempted"

    /**
     * Stores the Steam id of the **token** the last heartbeat found bound to a different DMarket account
     * (a wrong-account session), or nothing when the accounts agree.
     *
     * Persisted for the same reason as [LOOP_STEAM_SESSION_MISSING] — it is a blocking state the host
     * renders, and an in-memory-only flag reads as "nothing is blocking" on the first wake of every
     * respawn. Stored as the *id* rather than a boolean because that is what makes the verdict
     * falsifiable: a credential whose subject differs from it is evidence the account changed, which is
     * the only thing that can clear the block without a heartbeat. See `WebExtStorageLoopStateStore`.
     */
    const val LOOP_STEAM_MISMATCH_TOKEN_ID = "loop_steam_mismatch_token_id"

    /**
     * Stores whether the credential named by [LOOP_STEAM_MISMATCH_TOKEN_ID] has already been re-acquired
     * from Steam during the current wrong-account episode, so one re-acquisition stays one across
     * service-worker respawns. See `WebExtStorageLoopStateStore`.
     */
    const val LOOP_STEAM_MISMATCH_RECHECKED = "loop_steam_mismatch_rechecked"

    /**
     * Stores the [com.dmarket.p2p.tracker.model.TokenFingerprint] of the DMarket refresh token the server most
     * recently **refused**, so a signed-out state costs zero network across service-worker respawns.
     *
     * A fingerprint, never the token: this store is contractually non-secret. Keyed on the token rather than
     * on a boolean so the latch falsifies itself — once the jar holds a different refresh token the
     * fingerprints differ and the next attempt proceeds, with nothing needing to "clear" it.
     */
    const val LOOP_MARKETPLACE_REFRESH_REJECTED = "loop_marketplace_refresh_rejected"

    /**
     * Stores the epoch-millis of this client's last completed DMarket token refresh, so the rotation rate
     * limit survives a respawn. Without it, a storm of worker spawns is a storm of rotations of a credential
     * shared with the user's browser session.
     */
    const val LOOP_MARKETPLACE_REFRESHED_AT = "loop_marketplace_refreshed_at_ms"

    /**
     * Stores the count of consecutive DMarket refresh failures that were **not** a refusal of the token (a
     * gateway 404, a WAF 403, a 502, a timeout), so a permanently broken endpoint stops being retried once
     * per wake forever and surfaces as a connection error instead.
     */
    const val LOOP_MARKETPLACE_REFRESH_FAILURES = "loop_marketplace_refresh_failures"
}
