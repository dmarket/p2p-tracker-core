package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.loop.LoopStateStore
import kotlin.time.Instant

/**
 * [LoopStateStore] backed by `chrome.storage.local`, so the loop's cross-tick schedule survives an
 * MV3 service-worker respawn: the heartbeat stays on its own (`ttl_seconds`) cadence instead of
 * firing on every deal-watch wake, and a just-created offer keeps its expedited poll window.
 */
class WebExtStorageLoopStateStore : LoopStateStore {

    override suspend fun nextHeartbeatAt(): Instant? = instant(DeviceVaultKeys.LOOP_HEARTBEAT_AT)

    override suspend fun setNextHeartbeatAt(at: Instant) = setInstant(DeviceVaultKeys.LOOP_HEARTBEAT_AT, at)

    override suspend fun expeditedUntil(): Instant? = instant(DeviceVaultKeys.LOOP_EXPEDITED_UNTIL)

    override suspend fun setExpeditedUntil(at: Instant) = setInstant(DeviceVaultKeys.LOOP_EXPEDITED_UNTIL, at)

    override suspend fun revertWatchAt(): Instant? = instant(DeviceVaultKeys.LOOP_REVERT_WATCH_AT)

    override suspend fun setRevertWatchAt(at: Instant) = setInstant(DeviceVaultKeys.LOOP_REVERT_WATCH_AT, at)

    override suspend fun serverErrorCount(): Int = webExtStorageGet(DeviceVaultKeys.LOOP_SERVER_ERROR_COUNT)
        ?.toIntOrNull()
        ?: 0

    override suspend fun setServerErrorCount(count: Int) {
        webExtStorageSet(DeviceVaultKeys.LOOP_SERVER_ERROR_COUNT, count.toString())
    }

    override suspend fun steamSessionMissing(): Boolean = webExtStorageGet(DeviceVaultKeys.LOOP_STEAM_SESSION_MISSING) == "1"

    override suspend fun setSteamSessionMissing(missing: Boolean) {
        webExtStorageSet(DeviceVaultKeys.LOOP_STEAM_SESSION_MISSING, if (missing) "1" else "0")
    }

    override suspend fun steamMintAttempted(): Boolean = webExtStorageGet(DeviceVaultKeys.LOOP_STEAM_MINT_ATTEMPTED) == "1"

    override suspend fun setSteamMintAttempted(attempted: Boolean) {
        webExtStorageSet(DeviceVaultKeys.LOOP_STEAM_MINT_ATTEMPTED, if (attempted) "1" else "0")
    }

    // Removed rather than written as an empty string when the block clears, so "no wrong-account block"
    // is the absence of the row — the same shape the credential vault uses, and one less value a host's
    // storage inspector can show as a confusing blank.
    override suspend fun steamMismatchTokenId(): String? =
        webExtStorageGet(DeviceVaultKeys.LOOP_STEAM_MISMATCH_TOKEN_ID)?.takeIf { it.isNotBlank() }

    override suspend fun setSteamMismatchTokenId(steamId: String?) {
        if (steamId == null) {
            webExtStorageRemove(DeviceVaultKeys.LOOP_STEAM_MISMATCH_TOKEN_ID)
        } else {
            webExtStorageSet(DeviceVaultKeys.LOOP_STEAM_MISMATCH_TOKEN_ID, steamId)
        }
    }

    override suspend fun steamMismatchRechecked(): Boolean = webExtStorageGet(DeviceVaultKeys.LOOP_STEAM_MISMATCH_RECHECKED) == "1"

    override suspend fun setSteamMismatchRechecked(rechecked: Boolean) {
        webExtStorageSet(DeviceVaultKeys.LOOP_STEAM_MISMATCH_RECHECKED, if (rechecked) "1" else "0")
    }

    // One place that knows how an Instant is stored (epoch millis as a string). Three accessors had written
    // this codec out by hand, so a change to the encoding was three edits with nothing forcing the third.
    private suspend fun instant(key: String): Instant? = webExtStorageGet(key)?.toLongOrNull()?.let { Instant.fromEpochMilliseconds(it) }

    private suspend fun setInstant(key: String, at: Instant) {
        webExtStorageSet(key, at.toEpochMilliseconds().toString())
    }
}
