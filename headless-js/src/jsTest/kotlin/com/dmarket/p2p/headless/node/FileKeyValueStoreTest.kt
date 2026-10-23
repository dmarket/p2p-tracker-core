package com.dmarket.p2p.headless.node

import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A fresh, empty directory under the OS temp dir. */
internal suspend fun tempDir(): String = mkdtemp(join(tmpdir(), "headless-js-test-")).await()

class FileKeyValueStoreTest {
    private suspend fun exists(path: String): Boolean = runCatching { stat(path).await() }.isSuccess

    @Test
    fun the_file_store_round_trips_and_writes_owner_only_files() = runTest {
        val dir = tempDir()
        val store = FileKeyValueStore(join(dir, "data"))
        assertNull(store.get("device_id"))
        assertFalse(exists(join(dir, "data")), "a read creates nothing")
        store.set("device_id", "abc")
        assertEquals(448, stat(join(dir, "data")).await().mode and 511, "0700")
        store.set("device_id", "def")
        assertEquals("def", store.get("device_id"))

        assertEquals(384, stat(join(dir, "data", "device_id")).await().mode and 511, "0600")

        store.remove("device_id")
        store.remove("device_id")
        assertNull(store.get("device_id"))
    }

    @Test
    fun each_account_keeps_its_state_in_its_own_directory() = runTest {
        val dir = tempDir()
        val accounts = AccountDirectories(dir)
        accounts.storeFor("76561198000000001").set("device_id", "first")
        accounts.storeFor("76561198000000099").set("device_id", "second")
        assertEquals("first", accounts.storeFor("76561198000000001").get("device_id"))
        assertEquals("second", readFile(join(dir, "76561198000000099", "device_id"), "utf8").await())
        for (id in listOf("../etc", "", "7656119800000000a", "1/2")) {
            assertFailsWith<IllegalArgumentException>(id) { accounts.storeFor(id) }
        }
    }

    @Test
    fun the_file_store_refuses_the_steam_credential() = runTest {
        val store = FileKeyValueStore(tempDir())
        assertFailsWith<IllegalArgumentException> { store.set("steam_credential", "token") }
    }
}
