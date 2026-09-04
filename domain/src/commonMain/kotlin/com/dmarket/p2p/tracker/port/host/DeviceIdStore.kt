package com.dmarket.p2p.tracker.port.host

import com.dmarket.p2p.tracker.model.DeviceId

/**
 * Supplies the install-scoped, persistent `device_id` sent on every heartbeat. It MUST survive token
 * refresh / re-login / restart (it is **not** a session id) — the backend leases each directive to one
 * `device_id`, and a value that changed per session would let two contexts execute the same
 * `create_offer`/`cancel_offer` twice.
 *
 * The actual implementation persists a generated id (e.g. `crypto.randomUUID()` into `chrome.storage`)
 * and returns the same value forever after.
 */
interface DeviceIdStore {
    /** The persisted device id, generating and persisting one on first call. */
    suspend fun current(): DeviceId
}
