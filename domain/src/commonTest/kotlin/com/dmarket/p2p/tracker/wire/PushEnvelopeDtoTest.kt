package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.PushSignal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
    fun an_unknown_type_without_a_wake_id_or_unreadable_json_is_no_wake() {
        assertNull(wake("""{"type":"presence_ping","dealId":"d-1"}"""))
        assertNull(wake("not json"))
    }

    @Test
    fun a_contract_wake_deal_names_a_transaction_and_syncs_everything() {
        val wake = wake(
            """{"v":"1","type":"wake_deal","wakeId":"01JB","transactionId":"5f0c","reason":"prove_release",""" +
                """"proveAfter":"2026-10-28T12:00:00Z","deadline":"2026-10-28T18:00:00Z","issuedAt":"2026-10-28T12:00:03Z"}""",
        )!!
        assertEquals(PushSignal.WakeAll, wake.signal)
        assertNull(wake.dealId)
        assertEquals("01JB", wake.wakeId)
        assertEquals("5f0c", wake.transactionId)
        assertEquals("prove_release", wake.reason)
        assertEquals(Instant.parse("2026-10-28T18:00:00Z"), wake.deadline)
        assertEquals(Instant.parse("2026-10-28T12:00:00Z"), wake.proveAfter)
    }

    @Test
    fun an_unknown_version_or_type_with_a_wake_id_reads_as_wake_all() {
        assertEquals(PushSignal.WakeAll, wake("""{"v":"2","type":"wake_all","reason":"offers_page","wakeId":"w"}""")!!.signal)
        assertEquals(PushSignal.WakeAll, wake("""{"v":"1","type":"wake_later","wakeId":"w"}""")!!.signal)
        assertEquals(PushSignal.WakeAll, wake("""{"type":"wake_deal"}""")!!.signal)
    }

    @Test
    fun only_a_directive_wake_is_a_directive() {
        assertTrue(wake("""{"v":"1","type":"wake_deal","wakeId":"w","reason":"directive"}""")!!.isDirective)
        assertFalse(wake("""{"v":"1","type":"wake_deal","wakeId":"w","reason":"prove_offer"}""")!!.isDirective)
    }

    @Test
    fun the_extension_reading_is_unchanged_by_the_mobile_keys() {
        // `toSignalOrNull` is what the extension's `deliverPush` reads: a `wake_deal` with no deal stays no signal.
        assertNull(signal("""{"v":"1","type":"wake_deal","wakeId":"w","transactionId":"t"}"""))
        assertEquals(PushSignal.CheckSentOffers, signal("""{"v":"1","type":"wake_all","reason":"offers_page","wakeId":"w"}"""))
    }
}
