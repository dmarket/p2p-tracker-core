package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.PushSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

class PushEnvelopeDtoTest {
    private fun signal(json: String) = parsePushEnvelope(json)?.toSignalOrNull()

    @Test
    fun an_offers_page_request_asks_for_a_sent_offers_check() {
        assertEquals(PushSignal.CheckSentOffers, signal("""{"type":"wake_all","reason":"offers_page"}"""))
    }

    @Test
    fun a_plain_or_unknown_reason_stays_a_wake_all() {
        assertEquals(PushSignal.WakeAll, signal("""{"type":"wake_all"}"""))
        assertEquals(PushSignal.WakeAll, signal("""{"type":"wake_all","reason":"something_else"}"""))
    }

    private fun wake(json: String) = parsePushEnvelope(json)?.toWakeOrNull()

    @Test
    fun a_mobile_wake_deal_carries_its_deal_and_timestamps() {
        val wake = wake(
            """{"type":"wake_deal","dealId":"d-1","reason":"buyer_accepted","proveAfter":"2026-11-11T10:00:00Z","issuedAt":"2026-11-11T09:59:00Z"}""",
        )!!
        assertEquals(PushSignal.WakeForDeal(DealId("d-1")), wake.signal)
        assertEquals(DealId("d-1"), wake.dealId)
        assertEquals("buyer_accepted", wake.reason)
        assertEquals(Instant.parse("2026-11-11T10:00:00Z"), wake.proveAfter)
        assertEquals(Instant.parse("2026-11-11T09:59:00Z"), wake.issuedAt)
    }

    @Test
    fun an_unparseable_timestamp_reads_as_absent_not_as_a_refused_wake() {
        val wake = wake("""{"type":"wake_all","proveAfter":"soon","issuedAt":"yesterday"}""")!!
        assertEquals(PushSignal.WakeAll, wake.signal)
        assertNull(wake.proveAfter)
        assertNull(wake.issuedAt)
    }

    @Test
    fun an_unknown_type_or_a_deal_wake_without_a_deal_is_no_wake() {
        assertNull(wake("""{"type":"presence_ping","dealId":"d-1"}"""))
        assertNull(wake("""{"type":"wake_deal"}"""))
        assertNull(wake("not json"))
    }
}
