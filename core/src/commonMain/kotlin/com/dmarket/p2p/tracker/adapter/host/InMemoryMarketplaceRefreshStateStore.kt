package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.port.marketplace.MarketplaceRefreshStateStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

/**
 * Process-lifetime [MarketplaceRefreshStateStore] — the constructor default, and all a single-process host
 * needs.
 *
 * Filed here rather than beside the provider because that is where this module's in-memory adapters for
 * **`:domain` ports** live (`InMemoryCredentialVault`, `InMemoryDeviceKeyValueStore`); in-memory defaults for
 * `:core`-owned interfaces sit beside their interface instead.
 *
 * Guarded by a [Mutex] like its siblings: the provider reads a value and writes it back on the refresh path,
 * and a bare `var` would make two concurrent acquires interleave read-modify-write on the failure counter.
 */
class InMemoryMarketplaceRefreshStateStore : MarketplaceRefreshStateStore {
    private val mutex = Mutex()
    private var rejected: String? = null
    private var lastRefreshed: Instant? = null
    private var failures: Int = 0

    override suspend fun rejectedRefreshFingerprint(): String? = mutex.withLock { rejected }

    override suspend fun setRejectedRefreshFingerprint(fingerprint: String?) = mutex.withLock { rejected = fingerprint }

    override suspend fun lastRefreshedAt(): Instant? = mutex.withLock { lastRefreshed }

    override suspend fun setLastRefreshedAt(at: Instant) = mutex.withLock { lastRefreshed = at }

    override suspend fun transientFailureCount(): Int = mutex.withLock { failures }

    override suspend fun setTransientFailureCount(count: Int) = mutex.withLock { failures = count }
}
