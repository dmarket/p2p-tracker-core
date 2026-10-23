package com.dmarket.p2p.headless.node

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore
import kotlinx.coroutines.await

/**
 * [DeviceKeyValueStore] over a directory: one file per key, owner-only, written to a temporary name and renamed
 * into place so a crash mid-write leaves the previous value rather than a torn one.
 *
 * Holds the loop's progress, claims, throttles and device id. **Never a secret:** the Steam credential's key is
 * refused outright, so a wiring mistake that pointed a vault at this store fails loudly instead of writing a
 * token to disk.
 *
 * [dir] is created (owner-only) by the first write, not before: reading a store nobody has written reads nothing
 * and leaves nothing behind.
 */
class FileKeyValueStore(private val dir: String) : DeviceKeyValueStore {
    private var dirReady = false

    override suspend fun get(key: String): String? = try {
        readFile(file(key), "utf8").await()
    } catch (e: Throwable) {
        if (e.nodeErrorCode() == "ENOENT") null else throw e
    }

    override suspend fun set(key: String, value: String) {
        val target = file(key)
        if (!dirReady) {
            mkdir(
                dir,
                options<MkdirOptions> {
                    recursive = true
                    mode = OWNER_ONLY_DIR
                },
            ).await()
            dirReady = true
        }
        val temporary = "$target.${randomBytes(TEMP_SUFFIX_BYTES).toString("hex") as String}.tmp"
        writeFile(
            temporary,
            value,
            options<WriteFileOptions> {
                encoding = "utf8"
                mode = OWNER_ONLY_FILE
            },
        ).await()
        rename(temporary, target).await()
    }

    override suspend fun remove(key: String) {
        try {
            unlink(file(key)).await()
        } catch (e: Throwable) {
            if (e.nodeErrorCode() != "ENOENT") throw e
        }
    }

    private fun file(key: String): String {
        require(key != DeviceVaultKeys.STEAM_CREDENTIAL) { "the Steam credential is never written to disk" }
        require(SAFE_KEY.matches(key)) { "storage key must be [A-Za-z0-9._-]+: $key" }
        return join(dir, key)
    }

    private companion object {
        val SAFE_KEY = Regex("^[A-Za-z0-9._-]+$")
        const val TEMP_SUFFIX_BYTES = 6

        /** 0700 and 0600. */
        const val OWNER_ONLY_DIR = 448
        const val OWNER_ONLY_FILE = 384
    }
}

/**
 * One [FileKeyValueStore] per Steam account, under `<dataDir>/<steamId>/`. Only a steamid64 names a directory, so an
 * id from the network can never reach outside [dataDir].
 */
class AccountDirectories(private val dataDir: String) {
    private val stores = mutableMapOf<String, FileKeyValueStore>()

    fun storeFor(steamId: String): FileKeyValueStore {
        require(STEAM_ID_64.matches(steamId)) { "not a steamid64" }
        return stores.getOrPut(steamId) { FileKeyValueStore(join(dataDir, steamId)) }
    }

    private companion object {
        val STEAM_ID_64 = Regex("^[0-9]{1,20}$")
    }
}

/** A fresh plain object of type [T], filled in by [fill]. */
private inline fun <T : Any> options(fill: T.() -> Unit): T = js("({})").unsafeCast<T>().apply(fill)

/** The `code` of a Node system error (`ENOENT`, …), or `null`. */
private fun Throwable.nodeErrorCode(): String? {
    val error: dynamic = this
    return error.code as? String
}
