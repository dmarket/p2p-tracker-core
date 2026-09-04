package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore

/**
 * The JVM target exists for tests and manual composition, not as a shipping client, so it resolves to
 * the process-lifetime store. Anything layered on it loses its state on process exit — see
 * [InMemoryDeviceKeyValueStore] for why that stays safe for the write-claim ledger.
 */
actual fun platformKeyValueStore(): DeviceKeyValueStore = InMemoryDeviceKeyValueStore()
