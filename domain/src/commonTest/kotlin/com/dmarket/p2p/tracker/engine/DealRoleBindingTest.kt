package com.dmarket.p2p.tracker.engine

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.marketplace.DealRole
import com.dmarket.p2p.tracker.model.marketplace.TrackedDeal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DealRoleBindingTest {

    private fun tracked(dealId: String, role: DealRole) = TrackedDeal(dealId = DealId(dealId), role = role)

    private val tracking = listOf(
        tracked("sale-1", DealRole.SELLER),
        tracked("purchase-1", DealRole.BUYER),
        tracked("legacy-1", DealRole.UNKNOWN),
    )

    // ---- roleOf ------------------------------------------------------------------------------------

    @Test
    fun role_is_read_per_entry_so_one_list_can_mix_sides() {
        assertEquals(DealRole.SELLER, DealRoleBinding.roleOf(tracking, DealId("sale-1")))
        assertEquals(DealRole.BUYER, DealRoleBinding.roleOf(tracking, DealId("purchase-1")))
        assertEquals(DealRole.UNKNOWN, DealRoleBinding.roleOf(tracking, DealId("legacy-1")))
    }

    @Test
    fun a_deal_absent_from_tracking_has_no_role() {
        assertEquals(DealRole.UNKNOWN, DealRoleBinding.roleOf(tracking, DealId("never-heard-of-it")))
    }

    @Test
    fun no_heartbeat_yet_or_no_deal_id_has_no_role() {
        assertEquals(DealRole.UNKNOWN, DealRoleBinding.roleOf(null, DealId("sale-1")))
        assertEquals(DealRole.UNKNOWN, DealRoleBinding.roleOf(tracking, null))
    }

    // ---- allowsWrite -------------------------------------------------------------------------------

    @Test
    fun a_seller_role_deal_may_write() {
        assertTrue(DealRoleBinding.allowsWrite(tracking, DealId("sale-1")))
    }

    @Test
    fun a_buyer_role_deal_may_never_write() {
        assertFalse(DealRoleBinding.allowsWrite(tracking, DealId("purchase-1")))
    }

    /**
     * The fail-open rule, and the reason it is not timidity: `role` is not in the frozen contract yet, so
     * refusing on its absence would block every legitimate sale against a backend that does not send it —
     * whereas failing open only allows a write the backend already declines to lease.
     */
    @Test
    fun every_unknown_shape_fails_open() {
        assertTrue(DealRoleBinding.allowsWrite(tracking, DealId("legacy-1")), "explicit UNKNOWN role")
        assertTrue(DealRoleBinding.allowsWrite(tracking, DealId("absent")), "deal not in active_tracking")
        assertTrue(DealRoleBinding.allowsWrite(null, DealId("sale-1")), "no heartbeat has landed yet")
        assertTrue(DealRoleBinding.allowsWrite(emptyList(), DealId("sale-1")), "watching nothing")
        assertTrue(DealRoleBinding.allowsWrite(tracking, null), "deal-less directive")
    }

    // ---- wire parsing ------------------------------------------------------------------------------

    @Test
    fun wire_values_map_to_roles() {
        assertEquals(DealRole.SELLER, DealRole.fromWire("seller"))
        assertEquals(DealRole.BUYER, DealRole.fromWire("buyer"))
    }

    /**
     * Tolerance where it counts: mis-reading a `buyer` is the one direction that fails open on a Steam
     * write, so casing and a proto-enum-style prefix must not be able to cause it.
     */
    @Test
    fun buyer_is_recognised_whatever_the_casing_or_enum_prefix() {
        for (wire in listOf("buyer", "BUYER", "Buyer", "ROLE_BUYER", "role_buyer", "DEAL_ROLE_BUYER")) {
            assertEquals(DealRole.BUYER, DealRole.fromWire(wire), wire)
            assertFalse(DealRoleBinding.allowsWrite(listOf(TrackedDeal(DealId("d"), role = DealRole.fromWire(wire))), DealId("d")), wire)
        }
    }

    @Test
    fun an_absent_or_unrecognised_value_is_unknown() {
        assertEquals(DealRole.UNKNOWN, DealRole.fromWire(null))
        assertEquals(DealRole.UNKNOWN, DealRole.fromWire(""))
        assertEquals(DealRole.UNKNOWN, DealRole.fromWire("ROLE_UNSPECIFIED"))
        assertEquals(DealRole.UNKNOWN, DealRole.fromWire("arbitrator"))
    }
}
