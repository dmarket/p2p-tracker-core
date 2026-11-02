package com.dmarket.p2p.tracker.wire

import com.dmarket.p2p.tracker.model.PushSignal
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
