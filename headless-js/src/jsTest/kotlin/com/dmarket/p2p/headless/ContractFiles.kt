package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.join
import com.dmarket.p2p.headless.node.pathToFileURL
import com.dmarket.p2p.headless.node.readFile
import kotlinx.coroutines.await
import kotlin.js.Promise

/** A directory the build hands the tests (see the jsNodeTest task). */
private fun buildDir(variable: String): String =
    js("process.env")[variable] as String? ?: error("$variable is not set: run the tests through Gradle")

/** A golden file under `src/jsTest/resources`. */
suspend fun golden(name: String): String = readFile(join(buildDir("HEADLESS_TEST_RESOURCES"), name), "utf8").await()

/** A file of the built package. */
suspend fun distributed(name: String): String = readFile(join(buildDir("HEADLESS_DIST_DIR"), name), "utf8").await()

/** The built package's entry, imported as a host would import it. */
suspend fun importEntry(): dynamic {
    val url = pathToFileURL(join(buildDir("HEADLESS_DIST_DIR"), "p2p-tracker-headless.mjs")).href as String
    return js("import(url)").unsafeCast<Promise<dynamic>>().await()
}
