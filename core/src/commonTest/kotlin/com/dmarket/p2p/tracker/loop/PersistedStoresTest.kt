package com.dmarket.p2p.tracker.loop

import com.dmarket.p2p.tracker.adapter.host.InMemoryDeviceKeyValueStore
import com.dmarket.p2p.tracker.adapter.host.PersistedDeviceIdStore
import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.ReportedStatus
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** The stores a host builds over its own [com.dmarket.p2p.tracker.port.host.DeviceKeyValueStore] survive a new instance. */
class PersistedStoresTest {

    @Test
    fun the_device_id_is_generated_once_and_read_back_by_a_new_instance() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        var generated = 0
        val first = PersistedDeviceIdStore(storage) { "device-${++generated}" }

        val ids = List(5) { async { first.current() } }.awaitAll().toSet()
        assertEquals(1, ids.size, "concurrent first calls must agree on one id")
        assertEquals(1, generated)
        assertEquals(ids.single(), PersistedDeviceIdStore(storage) { error("must not generate again") }.current())
    }

    @Test
    fun the_loop_state_survives_a_new_instance() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        val at = Instant.fromEpochMilliseconds(1_786_356_000_000)
        PersistedLoopStateStore(storage).apply {
            setNextHeartbeatAt(at)
            setServerErrorCount(3)
            setSteamMismatchTokenId("76561198000000001")
        }

        val reread = PersistedLoopStateStore(storage)
        assertEquals(at, reread.nextHeartbeatAt())
        assertEquals(3, reread.serverErrorCount())
        assertEquals("76561198000000001", reread.steamMismatchTokenId())

        reread.setSteamMismatchTokenId(null)
        assertNull(PersistedLoopStateStore(storage).steamMismatchTokenId(), "a cleared block is the absence of the row")
    }

    @Test
    fun tracker_progress_survives_a_new_instance() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        PersistedTrackerProgressStore(storage).apply {
            recordReported(mapOf(DealId("deal-1") to ReportedStatus(lastOfferCode = 3, lastHistoryCode = 12)))
            recordHandledDirectives(setOf(DirectiveId("dir-1")))
        }

        val reread = PersistedTrackerProgressStore(storage)
        assertEquals(ReportedStatus(lastOfferCode = 3, lastHistoryCode = 12), reread.loadReported()[DealId("deal-1")])
        assertEquals(setOf(DirectiveId("dir-1")), reread.loadHandledDirectives())
    }

    @Test
    fun every_directive_status_survives_a_new_instance() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        val outcomes = DirectiveStatus.entries.mapIndexed { i, status ->
            DirectiveOutcome(DirectiveId("dir-$i"), DirectiveAction.CANCEL_OFFER, status, DealId("deal-$i"), OfferId("offer-$i"), "e$i")
        }
        PersistedTrackerProgressStore(storage).apply { outcomes.forEach { recordDirectiveOutcome(it) } }

        val reread = PersistedTrackerProgressStore(storage).loadDirectiveOutcomes()
        assertEquals(outcomes.associateBy { it.directiveId }, reread)
        assertEquals("not_actionable", DirectiveStatus.NOT_ACTIONABLE.wireName)
    }

    @Test
    fun an_outcome_whose_status_this_build_cannot_name_is_skipped_but_kept_in_storage() = runTest {
        val storage = InMemoryDeviceKeyValueStore()
        // What a newer build leaves behind for an older one: a status this enum has no entry for.
        storage.set(
            DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES,
            """{"dir-new":{"action":"cancel_offer","status":"some_future_status","dealId":"deal-1"},""" +
                """"dir-old":{"action":"cancel_offer","status":"failed","dealId":"deal-2","error":"x"}}""",
        )
        val store = PersistedTrackerProgressStore(storage)
        assertEquals(setOf(DirectiveId("dir-old")), store.loadDirectiveOutcomes().keys, "the rest still loads")

        // Writing and pruning other rows leaves the unreadable one where it was, for a build that can read it.
        store.recordDirectiveOutcome(DirectiveOutcome(DirectiveId("dir-3"), DirectiveAction.CANCEL_OFFER, DirectiveStatus.SUCCESS))
        store.clearDirectiveOutcomes(setOf(DirectiveId("dir-old")))
        val raw = storage.get(DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES).orEmpty()
        assertTrue(""""some_future_status"""" in raw, raw)
        assertEquals(setOf(DirectiveId("dir-3")), store.loadDirectiveOutcomes().keys)
    }
}
