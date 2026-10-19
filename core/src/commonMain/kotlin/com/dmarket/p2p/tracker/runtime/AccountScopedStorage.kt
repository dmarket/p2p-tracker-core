package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The headless loop's storage, scoped to the Steam account DMarket links to the pushed key's account.
 *
 * That account is only known once a heartbeat comes back, and the heartbeat needs a `device_id` first — the one kept
 * in that very account's storage. So until then the store is **provisional**: it reads the storage of the held
 * token's account ([provisionalAccount], the token's `sub`), and keeps every write in memory. The first heartbeat
 * that names a linked account [confirm]s it:
 *  - the token's account: the provisional reads were that account's own, and the writes held back are written to it.
 *    The `device_id` the heartbeat carried is the one the account keeps, so the directives it was leased stay leased
 *    to this device.
 *  - another account: the writes held back are dropped, and the loop reads and writes that account's storage from
 *    then on. The heartbeat for the token's account was not forwarded for another account's key, so the `device_id`
 *    it carried leased nothing and marked nothing present. The token is then refused as the wrong account.
 *
 * Confirmed once, for the life of the tracker: one tracker serves one account, and switching accounts is a restart.
 * An id that is not a steamid64 is never confirmed, so a malformed one never names a storage location.
 *
 * Read-through: no store the loop builds over this one caches, so what a read sees is where the next write lands.
 */
internal class AccountScopedStorage(
    private val storageFor: (SteamId) -> DeviceKeyValueStore,
    private val provisionalAccount: () -> SteamId?,
) : DeviceKeyValueStore {
    private val mutex = Mutex()
    private var confirmed: DeviceKeyValueStore? = null
    private val pending = linkedMapOf<String, String?>()
    private var provisional: Pair<SteamId, DeviceKeyValueStore>? = null

    /** The account whose storage this is; `null` while provisional. */
    var account: SteamId? = null
        private set

    override suspend fun get(key: String): String? = mutex.withLock {
        confirmed?.let { return@withLock it.get(key) }
        if (key in pending) return@withLock pending[key]
        provisionalStore()?.get(key)
    }

    override suspend fun set(key: String, value: String) = mutex.withLock {
        confirmed?.set(key, value) ?: run { pending[key] = value }
    }

    override suspend fun remove(key: String) = mutex.withLock {
        confirmed?.remove(key) ?: run { pending[key] = null }
    }

    /** Scopes the storage to [linked], once; later calls change nothing. See the class note. */
    suspend fun confirm(linked: SteamId) = mutex.withLock {
        if (confirmed != null || !STEAM_ID_64.matches(linked.value)) return@withLock
        val store = storageFor(linked)
        if (linked == provisionalAccount()) {
            pending.forEach { (key, value) -> if (value == null) store.remove(key) else store.set(key, value) }
        }
        pending.clear()
        provisional = null
        confirmed = store
        account = linked
    }

    private fun provisionalStore(): DeviceKeyValueStore? {
        val sub = provisionalAccount()?.takeIf { STEAM_ID_64.matches(it.value) } ?: return null
        provisional?.takeIf { it.first == sub }?.let { return it.second }
        return storageFor(sub).also { provisional = sub to it }
    }

    private companion object {
        val STEAM_ID_64 = Regex("^[0-9]{1,20}$")
    }
}
