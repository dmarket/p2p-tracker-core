package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceRefreshStateStore
import kotlin.time.Instant

/**
 * [MarketplaceRefreshStateStore] over the platform's key-value store, so the refresh guards survive a
 * process restart — which on the web target happens roughly once a minute, and is what turns an
 * in-memory guard into a per-wake retry.
 *
 * Written once in common code rather than per platform: [DeviceKeyValueStore] already has an actual on
 * every target. Nothing stored here is a credential — the refused refresh token is remembered by
 * fingerprint — which is what makes this store (contractually non-secret) the right home for it.
 */
class PersistedMarketplaceRefreshStateStore(private val storage: DeviceKeyValueStore) : MarketplaceRefreshStateStore {

    override suspend fun rejectedRefreshFingerprint(): String? =
        storage.get(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESH_REJECTED)?.takeIf { it.isNotBlank() }

    override suspend fun setRejectedRefreshFingerprint(fingerprint: String?) {
        if (fingerprint == null) {
            storage.remove(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESH_REJECTED)
        } else {
            storage.set(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESH_REJECTED, fingerprint)
        }
    }

    override suspend fun lastRefreshedAt(): Instant? = storage.get(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESHED_AT)
        ?.toLongOrNull()
        ?.let { Instant.fromEpochMilliseconds(it) }

    override suspend fun setLastRefreshedAt(at: Instant) {
        storage.set(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESHED_AT, at.toEpochMilliseconds().toString())
    }

    override suspend fun transientFailureCount(): Int = storage.get(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESH_FAILURES)?.toIntOrNull() ?: 0

    override suspend fun setTransientFailureCount(count: Int) {
        if (count <= 0) {
            storage.remove(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESH_FAILURES)
        } else {
            storage.set(DeviceVaultKeys.LOOP_MARKETPLACE_REFRESH_FAILURES, count.toString())
        }
    }
}
