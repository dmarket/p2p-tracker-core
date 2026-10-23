package com.dmarket.p2p.headless

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The package's surface, read off the built package itself: every name the facade exports is importable from its
 * entry at runtime, nothing else is, and the generated declarations of those names match the golden file.
 *
 * The generated `.d.mts` also carries the browser functions and config classes of the library underneath, which
 * the entry does not export, and their order there is not stable. So the golden file holds only the facade's own
 * declarations, picked by name. It guards names and signatures; the result types there are `any`, and the
 * package's handwritten types describe them.
 */
class SurfaceContractTest {
    private val facade = listOf(
        "createHeadlessTracker",
        "cycle",
        "defaultBaseUrl",
        "defaultNotaryUrl",
        "directiveActions",
        "failedCancelKinds",
        "headlessVersion",
        "maxClockSkewMs",
        "minWriteTokenLifetimeMs",
        "pushSteamToken",
        "pushTradingApiKey",
        "reasons",
        "runProverChild",
        "start",
        "steamNotLinkedCode",
        "stop",
        "trackerState",
    )

    @Test
    fun the_entry_exports_exactly_the_facade() = runTest {
        val entry = importEntry()
        assertEquals(facade, (js("Object.keys")(entry) as Array<String>).sorted())
        for (name in facade) assertEquals("function", jsTypeOf(entry[name]), name)
    }

    @Test
    fun the_constants_from_the_entry_are_frozen_and_complete() = runTest {
        val entry = importEntry()
        val reasons = entry.reasons()
        assertEquals(
            listOf("noProver", "offerWritesDisabled", "proofTokenExpiring", "steamSessionMissing", "tokenExpiringDefer"),
            shapeOf(reasons).keys.sorted(),
        )
        assertTrue(shapeOf(reasons).values.all { it == "string" })
        assertEquals(listOf("alreadyInactive", "alreadyTraded", "stillOpen", "unread"), shapeOf(entry.failedCancelKinds()).keys.sorted())
        assertEquals(listOf("cancelOffer", "createOffer", "reportInventory", "unknown"), shapeOf(entry.directiveActions()).keys.sorted())
        for (constant in listOf(reasons, entry.failedCancelKinds(), entry.directiveActions())) {
            assertTrue(js("Object.isFrozen")(constant) as Boolean)
        }
        assertEquals("already_traded", entry.failedCancelKinds().alreadyTraded)
        assertEquals("cancel_offer", entry.directiveActions().cancelOffer)
        assertEquals("P2P_STEAM_NOT_LINKED", entry.steamNotLinkedCode())
        assertEquals(1_800_000.0, entry.minWriteTokenLifetimeMs())
        assertEquals(60_000.0, entry.maxClockSkewMs())
        assertEquals("wss://api.dmarket.com/provenance/v1/", entry.defaultNotaryUrl())
        assertEquals("https://api.dmarket.com", entry.defaultBaseUrl())
        assertEquals(headlessVersion(), entry.headlessVersion())
    }

    @Test
    fun the_facade_declarations_match_the_golden_file() = runTest {
        val generated = distributed("p2p-tracker-headless.d.mts").lines()
        val declarations = facade.map { name ->
            generated.filter { Regex("^export declare (function|const) $name\\b").containsMatchIn(it) }
                .let { found -> found.singleOrNull() ?: error("$name is declared ${found.size} times") }
        }
        assertEquals(golden("p2p-tracker-headless.d.mts.golden").trimEnd('\n'), declarations.joinToString("\n"))
    }
}
