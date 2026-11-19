package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.port.marketplace.RefusedAccessToken
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PersistedMarketplaceRefreshStateStoreTest {

    private val storage = InMemoryDeviceKeyValueStore()
    private val store = PersistedMarketplaceRefreshStateStore(storage)

    @Test
    fun the_refused_access_marker_round_trips_and_null_removes_it() = runTest {
        val marker = RefusedAccessToken("abc123", Instant.fromEpochMilliseconds(1_781_611_200_000L))
        store.setRefusedAccess(marker)
        assertEquals(marker, PersistedMarketplaceRefreshStateStore(storage).refusedAccess())

        store.setRefusedAccess(null)
        assertNull(storage.get(DeviceVaultKeys.LOOP_MARKETPLACE_ACCESS_REFUSED))
        assertNull(store.refusedAccess())
    }

    @Test
    fun a_malformed_marker_reads_as_null() = runTest {
        for (bad in listOf("", "abc", "abc|", "|123", "abc|notanumber")) {
            storage.set(DeviceVaultKeys.LOOP_MARKETPLACE_ACCESS_REFUSED, bad)
            assertNull(store.refusedAccess(), "'$bad'")
        }
    }
}
