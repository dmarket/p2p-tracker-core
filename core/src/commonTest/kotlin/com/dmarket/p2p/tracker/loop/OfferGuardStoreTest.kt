package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.model.AssetId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.steam.SuspicionLevel
import com.dmarket.p2p.tracker.model.steam.SuspiciousOffer
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

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

    // ---- warnings whose partner has no name this scan ----

    private fun byName(id: String) = flagged(id).copy(byName = true)

    @Test
    fun a_name_made_warning_without_a_name_is_neither_cleared_nor_relevelled() = runTest {
        val store = PersistedOfferGuardStore()
        store.unreported(listOf(byName("o1")), ids("o1"))

        val unlinked = flagged("o1", SuspicionLevel.UNLINKED_GIVE_ONLY)
        assertEquals(GuardDelta(emptyList(), emptyList()), store.unreported(listOf(unlinked), ids("o1"), nameless = ids("o1")))
        assertEquals(GuardDelta(emptyList(), emptyList()), store.unreported(emptyList(), ids("o1"), nameless = ids("o1")))
        assertEquals(listOf(OfferId("o1")), store.unreported(emptyList(), ids("o1")).cleared, "a scan with the name judges it")
    }

    @Test
    fun a_list_made_warning_without_a_name_is_judged() = runTest {
        val store = PersistedOfferGuardStore()
        store.unreported(listOf(flagged("o1"), flagged("o2")), ids("o1", "o2"))

        val delta = store.unreported(listOf(flagged("o2", SuspicionLevel.UNLINKED_GIVE_ONLY)), ids("o1", "o2"), nameless = ids("o1", "o2"))
        assertEquals(listOf(OfferId("o1")), delta.cleared)
        assertEquals(listOf(flagged("o2", SuspicionLevel.UNLINKED_GIVE_ONLY)), delta.fresh)
    }

    @Test
    fun a_change_of_basis_is_reported_again() = runTest {
        val store = PersistedOfferGuardStore()
        store.unreported(listOf(flagged("o1")), ids("o1"))

        assertEquals(listOf(byName("o1")), store.unreported(listOf(byName("o1")), ids("o1")).fresh)
    }

    @Test
    fun the_basis_survives_a_respawn_and_an_older_record_reads_as_made_on_the_name() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        PersistedOfferGuardStore(storage).unreported(listOf(flagged("list")), ids("list"))
        assertEquals("""{"list":"IMPERSONATION_BY_LIST"}""", storage.get(DeviceVaultKeys.GUARD_NOTIFIED))
        assertEquals(
            listOf(OfferId("list")),
            PersistedOfferGuardStore(storage).unreported(emptyList(), ids("list"), nameless = ids("list")).cleared,
        )

        storage.set(DeviceVaultKeys.GUARD_NOTIFIED, """{"old":"IMPERSONATION"}""")
        val delta = PersistedOfferGuardStore(storage).unreported(emptyList(), ids("old"), nameless = ids("old"))
        assertEquals(emptyList(), delta.cleared, "a record from before the basis was kept never clears on missing evidence")
    }

    @Test
    fun an_older_record_found_at_the_same_level_is_not_reported_again_and_gets_its_basis() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        storage.set(DeviceVaultKeys.GUARD_NOTIFIED, """{"list":"IMPERSONATION","name":"IMPERSONATION"}""")
        val store = PersistedOfferGuardStore(storage)

        assertEquals(GuardDelta(emptyList(), emptyList()), store.unreported(listOf(flagged("list"), byName("name")), ids("list", "name")))
        assertEquals(
            """{"list":"IMPERSONATION_BY_LIST","name":"IMPERSONATION_BY_NAME"}""",
            storage.get(DeviceVaultKeys.GUARD_NOTIFIED),
        )
        assertEquals(
            emptyList(),
            PersistedOfferGuardStore(storage).unreported(listOf(byName("name")), ids("name")).fresh,
            "after a respawn",
        )
        assertEquals(listOf(byName("list")), store.unreported(listOf(byName("list")), ids("list")).fresh, "a known basis that changes is")
    }

    // ---- "not ours" stamps ----

    private val t0 = Instant.parse("2026-10-30T12:00:00Z")

    private fun notOurs(vararg ids: String) = ids.associate { OfferId(it) to false }

    private fun at(time: Instant, vararg ids: String) = ids.associate { OfferId(it) to time }

    @Test
    fun the_first_not_ours_is_stamped_with_its_server_time_and_a_later_one_keeps_that_stamp() = runTest {
        val store = PersistedOfferGuardStore()
        assertEquals(mapOf(OfferId("o1") to t0), store.recordNotOurs(notOurs("o1"), ids("o1"), at(t0, "o1")))
        assertEquals(mapOf(OfferId("o1") to t0), store.recordNotOurs(notOurs("o1"), ids("o1"), at(t0 + 3.minutes, "o1")))
    }

    @Test
    fun a_not_ours_with_no_server_time_creates_no_stamp() = runTest {
        val store = PersistedOfferGuardStore()
        assertEquals(emptyMap(), store.recordNotOurs(notOurs("o1"), ids("o1"), emptyMap()))
    }

    @Test
    fun an_ours_drops_the_stamp_and_no_answer_keeps_it() = runTest {
        val store = PersistedOfferGuardStore()
        store.recordNotOurs(notOurs("o1", "o2"), ids("o1", "o2"), at(t0, "o1", "o2"))

        val next = store.recordNotOurs(mapOf(OfferId("o1") to true), ids("o1", "o2"), emptyMap())
        assertEquals(mapOf(OfferId("o2") to t0), next)
    }

    @Test
    fun a_stamp_for_an_offer_the_read_no_longer_lists_is_dropped() = runTest {
        val store = PersistedOfferGuardStore()
        store.recordNotOurs(notOurs("o1"), ids("o1"), at(t0, "o1"))

        assertEquals(emptyMap(), store.recordNotOurs(emptyMap(), ids("o2"), emptyMap()))
    }

    @Test
    fun the_stamps_survive_a_respawn() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        PersistedOfferGuardStore(storage).recordNotOurs(notOurs("o1"), ids("o1"), at(t0, "o1"))

        assertEquals(mapOf(OfferId("o1") to t0), PersistedOfferGuardStore(storage).recordNotOurs(emptyMap(), ids("o1"), emptyMap()))
    }

    @Test
    fun only_the_most_recent_stamps_are_kept() = runTest {
        val store = PersistedOfferGuardStore()
        val all = (0..MAX_NOT_OURS_SINCE).map { OfferId("o$it") }
        all.forEachIndexed { i, id -> store.recordNotOurs(mapOf(id to false), all.toSet(), mapOf(id to t0 + i.seconds)) }

        val kept = store.recordNotOurs(emptyMap(), all.toSet(), emptyMap())
        assertEquals(MAX_NOT_OURS_SINCE, kept.size)
        assertEquals(false, OfferId("o0") in kept, "the oldest stamp goes first")
    }
}
