package com.dmarket.p2p.sdk.node

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * [DeviceKeyValueStore] over a directory: one file per key, owner-only, written to a temporary name and renamed
 * into place so a crash mid-write leaves the previous value rather than a torn one.
 *
 * Holds the loop's progress, claims, throttles and device id. **Never a secret:** the Steam credential's key is
 * refused outright, so a wiring mistake that pointed a vault at this store fails loudly instead of writing a
 * token to disk.
 */
class FileKeyValueStore(private val dir: String) : DeviceKeyValueStore {

    /** Creates [dir] (owner-only) when it does not exist. Call once before use. */
    suspend fun init() {
        NodeModules.fs.mkdir(dir, js("({recursive: true, mode: 448})")).unsafeCast<Promise<Any?>>().await()
    }

    override suspend fun get(key: String): String? = try {
        NodeModules.fs.readFile(file(key), "utf8").unsafeCast<Promise<String>>().await()
    } catch (e: Throwable) {
        if (e.nodeErrorCode() == "ENOENT") null else throw e
    }

    override suspend fun set(key: String, value: String) {
        val target = file(key)
        val temporary = "$target.${randomHex(TEMP_SUFFIX_BYTES)}.tmp"
        NodeModules.fs.writeFile(temporary, value, js("({encoding: 'utf8', mode: 384})")).unsafeCast<Promise<Any?>>().await()
        NodeModules.fs.rename(temporary, target).unsafeCast<Promise<Any?>>().await()
    }

    override suspend fun remove(key: String) {
        try {
            NodeModules.fs.unlink(file(key)).unsafeCast<Promise<Any?>>().await()
        } catch (e: Throwable) {
            if (e.nodeErrorCode() != "ENOENT") throw e
        }
    }

    private fun file(key: String): String {
        require(key != DeviceVaultKeys.STEAM_CREDENTIAL) { "the Steam credential is never written to disk" }
        require(SAFE_KEY.matches(key)) { "storage key must be [A-Za-z0-9._-]+: $key" }
        return NodeModules.path.join(dir, key) as String
    }

    private companion object {
        val SAFE_KEY = Regex("^[A-Za-z0-9._-]+$")
        const val TEMP_SUFFIX_BYTES = 6
    }
}
