package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.nodeChildProcess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.js.Promise
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
 * package's handwritten types (`index.d.ts`) describe them. Those are checked here for what can be read off the
 * runtime: the same names, and each constant's value as its declared literal.
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
            listOf(
                "noProver",
                "offerWritesDisabled",
                "proofTokenExpiring",
                "steamProxyUnreachable",
                "steamSessionMissing",
                "tokenExpiringDefer",
            ),
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
    fun the_package_types_declare_exactly_the_facade_and_each_constant_as_its_value() = runTest {
        val types = distributed("index.d.ts")
        val declared = Regex("^export declare function (\\w+)", RegexOption.MULTILINE).findAll(types).map { it.groupValues[1] }
        assertEquals(facade, declared.toList().sorted())
        assertEquals(0, Regex("\\bany\\b").findAll(types).count(), "index.d.ts leaves a type undescribed")
        val entry = importEntry()
        for (constant in listOf("reasons", "failedCancelKinds", "directiveActions")) {
            val value = entry[constant]()
            for (key in js("Object.keys")(value) as Array<String>) {
                val literal = "$key: ${JSON.stringify(value[key])};"
                assertTrue(literal in types, "index.d.ts does not declare $constant() with $literal")
            }
        }
        assertTrue("steamNotLinkedCode(): ${JSON.stringify(entry.steamNotLinkedCode())};" in types)
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

    /**
     * Importing the package changes nothing process-wide: `undici`, which installs an `Agent` of its own as Node's global
     * dispatcher when none is set yet, is not imported until a Steam call goes through a proxy. A CommonJS host's process
     * has none set when it imports the package; one taken over would move all of its `fetch` calls onto this package's
     * `undici`.
     */
    @Test
    fun importing_the_package_leaves_nodes_global_dispatcher_alone() = runTest {
        val script = """
            const keys = [Symbol.for('undici.globalDispatcher.1'), Symbol.for('undici.globalDispatcher.2')];
            const before = keys.map((key) => typeof globalThis[key]);
            import(process.argv[1]).then(() => {
              const after = keys.map((key) => typeof globalThis[key]);
              process.stdout.write(JSON.stringify({ before, after }));
            });
        """.trimIndent()
        val output = withContext(Dispatchers.Default) {
            Promise<String> { resolve, reject ->
                nodeChildProcess.execFile(js("process.execPath"), arrayOf("-e", script, entryUrl())) {
                        error: dynamic,
                        stdout: String,
                        _: String,
                    ->
                    if (error != null) reject(error.unsafeCast<Throwable>()) else resolve(stdout)
                }
            }.await()
        }
        val seen: dynamic = JSON.parse(output)
        assertEquals(JSON.stringify(seen.before), JSON.stringify(seen.after), output)
    }
}
