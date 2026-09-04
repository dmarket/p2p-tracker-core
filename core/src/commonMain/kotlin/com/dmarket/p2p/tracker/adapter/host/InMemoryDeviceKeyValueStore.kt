package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-lifetime [DeviceKeyValueStore] — the JVM `actual` (the JVM target is a test/composition
 * target, not a shipping client), the default for a host that wires no persistence, and the fake for
 * tests.
 *
 * Nothing survives process exit, so a store layered on this one degrades to in-memory-only semantics.
 * For the write-claim ledger that is safe by construction: the claim still blocks duplicates for the
 * life of the process, and a lost claim costs at most one re-write after a restart.
 */
class InMemoryDeviceKeyValueStore : DeviceKeyValueStore {
    private val mutex = Mutex()
    private val entries = mutableMapOf<String, String>()

    override suspend fun get(key: String): String? = mutex.withLock { entries[key] }

    override suspend fun set(key: String, value: String) {
        mutex.withLock { entries[key] = value }
    }

    override suspend fun remove(key: String) {
        mutex.withLock { entries.remove(key) }
    }
}
