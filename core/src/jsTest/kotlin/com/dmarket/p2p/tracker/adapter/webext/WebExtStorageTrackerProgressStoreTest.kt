package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.FreshProofProgress
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Tests for [WebExtStorageTrackerProgressStore]'s persisted directive outcomes, against the same
 * in-memory `chrome.storage.local` shim as [WebExtStorageCredentialVaultTest].
 */
class WebExtStorageTrackerProgressStoreTest {

    @BeforeTest
    fun installChromeMock() {
        js(
            """
            (function () {
                var store = {};
                globalThis.chrome = {
                    storage: {
                        local: {
                            get: function (key) {
                                var result = {};
                                if (store[key] !== undefined) { result[key] = store[key]; }
                                return Promise.resolve(result);
                            },
                            set: function (items) {
                                Object.keys(items).forEach(function (k) { store[k] = items[k]; });
                                return Promise.resolve(undefined);
                            },
                            remove: function (key) {
                                delete store[key];
                                return Promise.resolve(undefined);
                            }
                        }
                    }
                };
            })()
            """,
        )
    }

    private fun outcome(
        id: String = "dir-1",
        action: DirectiveAction = DirectiveAction.CREATE_OFFER,
        status: DirectiveStatus = DirectiveStatus.NEEDS_CONFIRMATION,
        dealId: String? = "deal-1",
        steamOfferId: String? = "offer-1",
        error: String? = null,
    ) = DirectiveOutcome(
        directiveId = DirectiveId(id),
        action = action,
        status = status,
        dealId = dealId?.let(::DealId),
        steamOfferId = steamOfferId?.let(::OfferId),
        error = error,
    )

    @Test
    fun directive_outcomes_survive_write_read_round_trip() = runTest {
        val store = WebExtStorageTrackerProgressStore()
        val create = outcome()
        val cancel = outcome(id = "dir-2", action = DirectiveAction.CANCEL_OFFER, status = DirectiveStatus.SUCCESS, steamOfferId = null)

        store.recordDirectiveOutcome(create)
        store.recordDirectiveOutcome(cancel)
        val loaded = store.loadDirectiveOutcomes()

        assertEquals(create, loaded[DirectiveId("dir-1")])
        assertEquals(cancel, loaded[DirectiveId("dir-2")])
    }

    @Test
    fun clear_removes_only_the_given_ids() = runTest {
        val store = WebExtStorageTrackerProgressStore()
        store.recordDirectiveOutcome(outcome(id = "dir-1"))
        store.recordDirectiveOutcome(outcome(id = "dir-2"))

        store.clearDirectiveOutcomes(setOf(DirectiveId("dir-1")))
        val loaded = store.loadDirectiveOutcomes()

        assertNull(loaded[DirectiveId("dir-1")])
        assertEquals(1, loaded.size)
    }

    @Test
    fun undecodable_json_falls_back_to_empty_map() = runTest {
        webExtStorageSet(DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES, "not json at all")
        assertTrue(WebExtStorageTrackerProgressStore().loadDirectiveOutcomes().isEmpty())
    }

    @Test
    fun entry_with_unknown_action_or_status_is_dropped_not_fatal() = runTest {
        webExtStorageSet(
            DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES,
            """
            {
              "dir-future": {"action": "teleport_offer", "status": "success"},
              "dir-odd": {"action": "create_offer", "status": "half_done"},
              "dir-ok": {"action": "create_offer", "status": "success", "steamOfferId": "offer-1"}
            }
            """.trimIndent(),
        )
        val loaded = WebExtStorageTrackerProgressStore().loadDirectiveOutcomes()
        assertEquals(setOf(DirectiveId("dir-ok")), loaded.keys)
    }

    @Test
    fun directive_outcomes_key_constant_is_stable() = runTest {
        // Stored on disk in chrome.storage.local; renaming is a breaking migration.
        assertEquals("tracker_directive_outcomes", DeviceVaultKeys.TRACKER_DIRECTIVE_OUTCOMES)
    }

    @Test
    fun concurrent_handled_directive_writes_do_not_lose_each_other() = runTest {
        // Each recorder loads, merges and writes back, with a storage await in the middle — so without a lock
        // two overlapping calls both read the pre-merge value and the second `set` drops the first's id. That
        // used to be unreachable (writers ran sequentially); the loop now runs its per-partner create chains
        // concurrently, and a lost handled id means the directive is re-executed on the next re-lease.
        val store = WebExtStorageTrackerProgressStore()
        coroutineScope {
            launch { store.recordHandledDirectives(setOf(DirectiveId("dir-a"))) }
            launch { store.recordHandledDirectives(setOf(DirectiveId("dir-b"))) }
        }
        assertEquals(setOf(DirectiveId("dir-a"), DirectiveId("dir-b")), store.loadHandledDirectives())
    }

    @Test
    fun concurrent_outcome_writes_do_not_lose_each_other() = runTest {
        val store = WebExtStorageTrackerProgressStore()
        coroutineScope {
            launch { store.recordDirectiveOutcome(outcome(id = "dir-a")) }
            launch { store.recordDirectiveOutcome(outcome(id = "dir-b")) }
        }
        assertEquals(setOf(DirectiveId("dir-a"), DirectiveId("dir-b")), store.loadDirectiveOutcomes().keys)
    }

    // ---- DMA-280: the freshness standing ------------------------------------------------------

    private fun standing(satisfiedMs: Long) = FreshProofProgress(satisfied = Instant.fromEpochMilliseconds(satisfiedMs))

    @Test
    fun fresh_proof_progress_survives_a_write_read_round_trip() = runTest {
        // The whole reason this row exists: an in-memory satisfaction would be re-armed on nearly every MV3
        // respawn, at one full MPC session each.
        val store = WebExtStorageTrackerProgressStore()
        val record = FreshProofProgress(
            satisfied = Instant.fromEpochMilliseconds(1_788_343_213_435),
            attempting = Instant.fromEpochMilliseconds(1_788_343_299_000),
            attempts = 3,
            retryAt = Instant.fromEpochMilliseconds(1_788_343_400_000),
        )
        store.recordFreshProofProgress(DealId("deal-1"), record)

        assertEquals(record, WebExtStorageTrackerProgressStore().loadFreshProofProgress()[DealId("deal-1")])
    }

    @Test
    fun a_satisfied_mark_round_trips_without_losing_a_millisecond() = runTest {
        // The stored value IS the latch key, compared with a strict `>` against the same mark re-parsed from
        // the next heartbeat. A codec that rounded, truncated to seconds or went through a double would make
        // `incoming > satisfied` true forever — one MPC session per wake for the life of the deal.
        val store = WebExtStorageTrackerProgressStore()
        val mark = Instant.fromEpochMilliseconds(1_788_343_213_435)
        store.recordFreshProofProgress(DealId("deal-1"), FreshProofProgress(satisfied = mark))

        val readBack = store.loadFreshProofProgress().getValue(DealId("deal-1")).satisfied
        assertEquals(mark, readBack)
        assertTrue(readBack != null && !(mark > readBack), "the round-tripped mark must not read as older than itself")
    }

    @Test
    fun clearing_removes_only_the_named_deals_standing() = runTest {
        val store = WebExtStorageTrackerProgressStore()
        store.recordFreshProofProgress(DealId("deal-1"), standing(1_000))
        store.recordFreshProofProgress(DealId("deal-2"), standing(2_000))

        store.clearFreshProofProgress(setOf(DealId("deal-1")))

        assertEquals(setOf(DealId("deal-2")), store.loadFreshProofProgress().keys)
    }

    @Test
    fun an_undecodable_freshness_row_falls_back_to_empty_rather_than_throwing() = runTest {
        // Same policy as every other row here: dropping one costs a re-proof, while failing the read would
        // abort the pass that was about to answer a mark.
        webExtStorageSet(DeviceVaultKeys.TRACKER_PROVE_AFTER, "not json at all")
        assertTrue(WebExtStorageTrackerProgressStore().loadFreshProofProgress().isEmpty())
    }

    @Test
    fun a_partially_written_freshness_row_reads_as_no_standing() = runTest {
        // Every field defaults, so a row from a build that predates the ladder reads as "nothing satisfied,
        // no ladder" — one re-proof, rather than a parked deal.
        webExtStorageSet(DeviceVaultKeys.TRACKER_PROVE_AFTER, """{"deal-1":{}}""")

        assertEquals(FreshProofProgress(), WebExtStorageTrackerProgressStore().loadFreshProofProgress()[DealId("deal-1")])
    }

    @Test
    fun freshness_key_constant_is_stable() = runTest {
        // Stored on disk in chrome.storage.local; renaming it silently re-demands every live mark once.
        assertEquals("tracker_prove_after", DeviceVaultKeys.TRACKER_PROVE_AFTER)
    }

    @Test
    fun concurrent_freshness_writes_do_not_lose_each_other() = runTest {
        // Read-modify-write with a storage await in the middle, like every recorder here. Two demands can be
        // answered in one pass, so this is reachable rather than theoretical.
        val store = WebExtStorageTrackerProgressStore()
        coroutineScope {
            launch { store.recordFreshProofProgress(DealId("deal-a"), standing(1_000)) }
            launch { store.recordFreshProofProgress(DealId("deal-b"), standing(2_000)) }
        }
        assertEquals(setOf(DealId("deal-a"), DealId("deal-b")), store.loadFreshProofProgress().keys)
    }
}
