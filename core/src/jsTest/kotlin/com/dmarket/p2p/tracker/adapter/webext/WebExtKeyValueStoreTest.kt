package com.dmarket.p2p.tracker.adapter.webext

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.engine.ClaimVerdict
import com.dmarket.p2p.tracker.loop.PersistedDealWriteClaimStore
import com.dmarket.p2p.tracker.model.ClaimPhase
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DealWriteClaim
import com.dmarket.p2p.tracker.model.DealWriteKey
import com.dmarket.p2p.tracker.model.DirectiveId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.marketplace.DirectiveAction
import com.dmarket.p2p.tracker.model.marketplace.DirectiveOutcome
import com.dmarket.p2p.tracker.model.marketplace.DirectiveStatus
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * [WebExtKeyValueStore] against the same in-memory `storage.local` shim the other web-storage tests use.
 *
 * The shim is installed as `globalThis.chrome` with **no** `browser` global, i.e. the Chrome shape;
 * [webExtApi] prefers `browser` when a real Firefox provides it, so both browsers ride the same code path
 * and there is nothing browser-specific left to test here.
 */
class WebExtKeyValueStoreTest {

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

    @Test
    fun absent_key_reads_null() = runTest {
        assertNull(WebExtKeyValueStore().get("nothing-here"))
    }

    @Test
    fun a_written_value_round_trips() = runTest {
        val store = WebExtKeyValueStore()
        store.set("k", "v")
        assertEquals("v", store.get("k"))
    }

    @Test
    fun a_second_write_overwrites() = runTest {
        val store = WebExtKeyValueStore()
        store.set("k", "first")
        store.set("k", "second")
        assertEquals("second", store.get("k"))
    }

    @Test
    fun a_removed_key_reads_null_again() = runTest {
        val store = WebExtKeyValueStore()
        store.set("k", "v")
        store.remove("k")
        assertNull(store.get("k"))
    }

    /** Two instances share the one storage area, which is what makes an MV3 respawn see prior state. */
    @Test
    fun a_value_written_by_one_instance_is_visible_to_another() = runTest {
        WebExtKeyValueStore().set("k", "v")
        assertEquals("v", WebExtKeyValueStore().get("k"))
    }

    /**
     * The end-to-end web path of the duplicate guard: a claim written through `storage.local` still blocks
     * the duplicate after a service-worker respawn (a fresh store instance over the same storage).
     */
    @Test
    fun a_write_claim_survives_a_worker_respawn_through_storage_local() = runTest {
        val t0 = Instant.parse("2026-06-16T12:00:00Z")
        val ttl = 15.minutes
        val key = DealWriteKey(DealId("deal-1"), DirectiveAction.CREATE_OFFER)
        fun pending(directiveId: String) = DealWriteClaim(
            dealId = DealId("deal-1"),
            action = DirectiveAction.CREATE_OFFER,
            phase = ClaimPhase.IN_FLIGHT,
            claimedAt = t0,
            directiveId = DirectiveId(directiveId),
        )

        PersistedDealWriteClaimStore(WebExtKeyValueStore()).apply {
            assertEquals(ClaimVerdict.Proceed, claim(pending("dir-1"), t0, ttl))
            complete(
                key,
                DirectiveOutcome(
                    directiveId = DirectiveId("dir-1"),
                    action = DirectiveAction.CREATE_OFFER,
                    status = DirectiveStatus.NEEDS_CONFIRMATION,
                    dealId = DealId("deal-1"),
                    steamOfferId = OfferId("offer-1"),
                ),
            )
        }
        assertTrue(WebExtKeyValueStore().get(DeviceVaultKeys.DEAL_WRITE_CLAIMS) != null, "the claim was persisted")

        val respawned = PersistedDealWriteClaimStore(WebExtKeyValueStore())
        val verdict = assertIs<ClaimVerdict.AlreadyCompleted>(respawned.claim(pending("dir-2"), t0, ttl))
        assertEquals(OfferId("offer-1"), verdict.claim.outcome?.steamOfferId)
    }
}
