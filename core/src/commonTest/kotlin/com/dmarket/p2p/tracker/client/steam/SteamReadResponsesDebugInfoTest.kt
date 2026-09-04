package com.dmarket.p2p.tracker.client.steam

import kotlinx.serialization.SerializationException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * The `Json` behind the Steam read path. This is the instance on the highest-frequency leak route: a Steam
 * 403/429 HTML page or an `IEconService` shape change reaches it, and the resulting message ends up in
 * `LifecycleEvent.SteamReadFailed.reason` → the host.
 */
class SteamReadResponsesDebugInfoTest {
    @Test
    fun a_malformed_steam_body_is_not_echoed_into_the_exception() {
        // `response` must be an object; a string there is the shape a Steam error page / API change
        // produces. Under 200 chars, so pre-fix the whole body was appended to the message verbatim.
        val body = """{"response":"SENTINEL-ASSET-8891"}"""

        val e = assertFailsWith<SerializationException> { SteamReadResponses.bulkOfferSnapshots(body) }

        val rendered = e.message.orEmpty() + "\n" + e.stackTraceToString()
        assertFalse("SENTINEL-ASSET-8891" in rendered, "Steam body leaked into: $rendered")
        assertFalse("JSON input:" in rendered)
    }
}
