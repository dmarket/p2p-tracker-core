package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SuspicionLevel
import com.dmarket.p2p.tracker.model.steam.SuspiciousOffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OfferGuardStoreTest {
    private fun flagged(id: String, level: SuspicionLevel = SuspicionLevel.IMPERSONATION) =
        SuspiciousOffer(OfferId(id), SteamId("76561198781084366"), setOf(AssetId("a1")), level)

    private fun ids(vararg ids: String) = ids.mapTo(mutableSetOf(), ::OfferId)

    @Test
    fun a_reported_offer_is_fresh_once_and_again_only_at_a_new_level() = runTest {
        val store = PersistedOfferGuardStore()
        assertEquals(listOf(flagged("o1")), store.unreported(listOf(flagged("o1")), ids("o1")).fresh)
        assertEquals(emptyList(), store.unreported(listOf(flagged("o1")), ids("o1")).fresh)
        val relevelled = flagged("o1", SuspicionLevel.UNLINKED_GIVE_ONLY)
        assertEquals(listOf(relevelled), store.unreported(listOf(relevelled), ids("o1")).fresh)
    }

    @Test
    fun a_reported_offer_the_read_lists_unflagged_is_cleared_once() = runTest {
        val store = PersistedOfferGuardStore()
        store.unreported(listOf(flagged("o1"), flagged("o2")), ids("o1", "o2"))

        val delta = store.unreported(listOf(flagged("o2")), ids("o1", "o2"))

        assertEquals(GuardDelta(fresh = emptyList(), cleared = listOf(OfferId("o1"))), delta)
        assertEquals(emptyList(), store.unreported(listOf(flagged("o2")), ids("o1", "o2")).cleared, "said once")
    }

    @Test
    fun a_reported_offer_the_read_does_not_list_is_neither_cleared_nor_forgotten() = runTest {
        val store = PersistedOfferGuardStore()
        store.unreported(listOf(flagged("o1")), ids("o1"))

        assertEquals(emptyList(), store.unreported(emptyList(), ids()).cleared, "absence proves nothing")
        // Still on record: listed again unflagged, it is cleared; listed again flagged, it is not re-reported.
        assertEquals(emptyList(), store.unreported(listOf(flagged("o1")), ids("o1")).fresh)
        assertEquals(listOf(OfferId("o1")), store.unreported(emptyList(), ids("o1")).cleared)
    }

    @Test
    fun the_record_a_clear_is_measured_against_survives_a_respawn() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        PersistedOfferGuardStore(storage).unreported(listOf(flagged("o1")), ids("o1"))
        PersistedOfferGuardStore(storage).unreported(emptyList(), ids())

        assertEquals(listOf(OfferId("o1")), PersistedOfferGuardStore(storage).unreported(emptyList(), ids("o1")).cleared)
    }

    @Test
    fun only_the_most_recently_listed_unlisted_reports_stay_on_record() = runTest {
        val store = PersistedOfferGuardStore()
        store.unreported(listOf(flagged("old")), ids("old"))
        // Each scan reports a new offer and stops listing the one before, so "old" falls out after the cap.
        repeat(MAX_UNLISTED_NOTIFIED) { i ->
            assertEquals(emptyList(), store.unreported(listOf(flagged("o$i")), ids("o$i")).cleared)
        }
        assertEquals(emptyList(), store.unreported(emptyList(), ids()).cleared, "falling out is not a clear")

        // Forgotten: listed unflagged it is not cleared, and flagged again it is new.
        assertEquals(emptyList(), store.unreported(emptyList(), ids("old")).cleared)
        assertEquals(listOf(flagged("old")), store.unreported(listOf(flagged("old")), ids("old")).fresh)
        // The newest unlisted one is still on record.
        val last = "o${MAX_UNLISTED_NOTIFIED - 1}"
        assertEquals(listOf(OfferId(last)), store.unreported(emptyList(), ids(last)).cleared)
    }
}
