package com.dmarket.p2p.tracker.runtime

import com.dmarket.p2p.tracker.model.SteamId
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [startTrackerWithEvents] against an inert `chrome` shim: the alarm never fires and the boot cycle is stopped unrun. */
class StartKnownScamPartnersTest {
    private var previousChrome: dynamic = null

    @BeforeTest
    fun installChromeMock() {
        previousChrome = js("globalThis.chrome")
        js(
            """
            (function () {
                var listeners = { addListener: function () {}, removeListener: function () {} };
                globalThis.chrome = {
                    alarms: { onAlarm: listeners, get: function () {}, create: function () {}, clear: function () {} },
                    runtime: { onStartup: listeners, onInstalled: listeners }
                };
            })()
            """,
        )
    }

    @AfterTest
    fun restoreChrome() {
        val previous = previousChrome
        js("globalThis.chrome = previous")
    }

    @Test
    fun a_list_given_at_start_is_in_place_before_the_boot_cycle() {
        val handle = startTrackerWithEvents(onEvent = {}, knownScamPartners = arrayOf("76561198000000555"))
        // The boot cycle is launched, not run: what the loop holds when start returns is what that cycle checks.
        val partners = (handle as JsTrackerHandle).loop.knownScamPartners
        stopTracker(handle)

        assertEquals(setOf(SteamId("76561198000000555")), partners)
    }
}
