package com.dmarket.p2p.tracker.model.marketplace

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.DirectiveId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class DealDeadlinesTest {
    private val early = Instant.parse("2026-10-16T12:00:00Z")
    private val late = Instant.parse("2026-10-16T14:00:00Z")

    private fun create(deal: String, deadline: Instant?) =
        Directive(DirectiveId("dir-$deal"), DirectiveAction.CREATE_OFFER, dealId = DealId(deal), deadline = deadline)

    @Test
    fun a_deal_lapses_at_the_earlier_of_its_phase_and_create_deadlines() {
        val heartbeat = HeartbeatResponse(
            activeTracking = listOf(
                TrackedDeal(DealId("d1"), deadline = late),
                TrackedDeal(DealId("d2"), deadline = early),
                TrackedDeal(DealId("d3")),
            ),
            directives = listOf(create("d1", early), create("d2", late), create("d4", late)),
        )

        assertEquals(mapOf(DealId("d1") to early, DealId("d2") to early, DealId("d4") to late), heartbeat.dealDeadlines())
    }

    @Test
    fun only_a_create_offer_contributes_its_deadline() {
        val heartbeat = HeartbeatResponse(
            directives = listOf(
                Directive(DirectiveId("dir-1"), DirectiveAction.CANCEL_OFFER, dealId = DealId("d1"), deadline = early),
                Directive(DirectiveId("dir-2"), DirectiveAction.REPORT_INVENTORY, deadline = early),
                create("d2", null),
            ),
        )

        assertEquals(emptyMap(), heartbeat.dealDeadlines())
    }
}
