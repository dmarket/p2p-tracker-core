package com.dmarket.p2p.tracker.adapter.host

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.model.DeviceId
import com.dmarket.p2p.tracker.port.host.DeviceIdStore
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * [DeviceIdStore] over a [DeviceKeyValueStore]. On the first call a random UUID is generated and persisted;
 * subsequent calls return the same value.
 *
 * The `device_id` is install-scoped and persistent — it must survive token refresh, re-login, an MV3
 * service-worker respawn and a process restart, because the backend leases each directive to one `device_id`.
 * Locked so two first calls cannot persist two different ids.
 */
@OptIn(ExperimentalUuidApi::class)
class PersistedDeviceIdStore(private val storage: DeviceKeyValueStore, private val newId: () -> String = { Uuid.random().toString() }) :
    DeviceIdStore {
    private val mutex = Mutex()

    override suspend fun current(): DeviceId = mutex.withLock {
        storage.get(DeviceVaultKeys.DEVICE_ID)?.takeIf { it.isNotBlank() }?.let { return@withLock DeviceId(it) }
        val generated = newId()
        storage.set(DeviceVaultKeys.DEVICE_ID, generated)
        DeviceId(generated)
    }
}
