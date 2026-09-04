package com.dmarket.p2p.tracker.notary

import com.dmarket.p2p.tracker.config.NotaryConfig
import com.dmarket.p2p.tracker.game.Cs2GameAdapter
import com.dmarket.p2p.tracker.model.DealId
import com.dmarket.p2p.tracker.model.OfferId
import com.dmarket.p2p.tracker.model.SteamId
import com.dmarket.p2p.tracker.model.marketplace.TradeStatusSource
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Asserts the reveal policy this library builds is one the **real, vendored** wasm prover accepts.
 *
 * This exists because the integration once shipped completely dead: upstream made
 * `revealRequestTarget` a required `RevealPolicy` field and nothing here noticed, because every test
 * asserted our objects against our own expectations. Only the wasm knows its wire format, so the wasm
 * is what we ask.
 *
 * It runs in plain Node with no notary, no network and no browser:
 * - `present()` is documented offline and needs no `initialize()` — hence no rayon pool, no Workers,
 *   and no cross-origin isolation (the `SharedArrayBuffer` gate is a browser rule, not a Node one).
 * - The policy is deserialized at the wasm-bindgen boundary **before** the attestation is decoded, so
 *   dummy attestation bytes are enough to exercise it.
 *
 * A policy the wasm accepts therefore fails later, on the dummy bytes, with a `codec:` error. A policy
 * it rejects fails earlier with a serde error naming the offending field. That difference is the test.
 *
 * Limits, so nobody over-trusts this: serde ignores unknown fields, so a renamed field is caught only
 * if the old name was required; and `prove()` cannot be exercised this way (without a rayon pool it
 * aborts the whole wasm instance).
 */
class WasmRevealPolicyContractTest {

    private fun spec() = SteamProofReadMapper(NotaryConfig()).readSpec(
        source = TradeStatusSource.OFFER,
        binding = ProvenReadBinding(dealId = DealId("d1"), steamOfferId = OfferId("off-1")),
        subjectSteamId = SteamId("76561198000000001"),
        adapter = Cs2GameAdapter(),
    )

    @Test
    fun the_policy_we_build_is_accepted_by_the_vendored_wasm() = runTest {
        val wasm = loadVendoredProver()

        val rejection = presentAndCaptureError(wasm, revealPolicy(spec()))

        assertTrue(
            "codec" in rejection,
            "the wasm rejected our reveal policy before it ever looked at the attestation: $rejection",
        )
    }

    @Test
    fun the_wasm_also_accepts_the_path_reveal_we_intend_to_narrow_back_to() = runTest {
        // `BodyReveal` is an untagged union (`"all" | "none" | { jsonPaths }`), and both axes ship the STRING
        // arm today — so the object arm has no live coverage at all. Untagged unions are exactly where a shape
        // drifts unnoticed, so ask the wasm about it, and about a LEAF path specifically: leaves are the open
        // narrowing (the vendored `f7d40de` reveals key paths for them, so they finally bind by name), and a
        // drift would otherwise land as a proof rejected inside MPC on the day we flip.
        val wasm = loadVendoredProver()
        val narrowed = spec().copy(responseBodyReveal = ResponseBodyReveal.JsonPaths(listOf("response.offer.tradeofferid")))

        val rejection = presentAndCaptureError(wasm, revealPolicy(narrowed))

        assertTrue("codec" in rejection, "the wasm rejected a jsonPaths reveal policy: $rejection")
    }

    @Test
    fun the_test_can_actually_detect_a_missing_required_field() = runTest {
        // Negative control. Without it the assertion above could pass for the wrong reason — e.g. if the
        // module silently stopped loading — and we would be back to a green suite over a dead prover.
        val wasm = loadVendoredProver()
        val incomplete = revealPolicy(spec())
        js("delete incomplete.revealRequestTarget")

        val rejection = presentAndCaptureError(wasm, incomplete)

        assertTrue(
            "revealRequestTarget" in rejection,
            "expected the wasm to name the missing required field, got: $rejection",
        )
    }
}

/** Call `present` with dummy blobs and return the error text; it always throws on bytes this fake. */
private fun presentAndCaptureError(wasm: dynamic, policy: dynamic): String {
    val bytes = js("new Uint8Array([1, 2, 3])")
    return try {
        wasm.present(bytes, bytes, policy)
        "present() unexpectedly succeeded on dummy attestation bytes"
    } catch (t: Throwable) {
        t.message ?: t.toString()
    }
}

/**
 * Instantiate `vendor/tlsn/pkg` from the source tree.
 *
 * The `self` shim is the one concession to running browser-targeted output under Node: the web-spawn
 * snippet registers a `message` listener on `self` at module scope. The spawner is never started here
 * (that is `initialize`'s job), so an inert `EventTarget` is enough.
 */
private suspend fun loadVendoredProver(): dynamic {
    js("globalThis.self = globalThis.self || Object.assign(new EventTarget(), { postMessage() {} })")
    val fs: dynamic = js("require('fs')")
    val path: dynamic = js("require('path')")
    val url: dynamic = js("require('url')")

    val pkgDir = findPkgDir(fs, path) ?: error("vendor/tlsn/pkg not found above ${js("process.cwd()")}")
    val glue = url.pathToFileURL(path.join(pkgDir, "client_wasm.js")).href.unsafeCast<String>()

    val wasm: dynamic = js("import(/* webpackIgnore: true */ glue)").unsafeCast<Promise<dynamic>>().await()
    val wasmBytes = fs.readFileSync(path.join(pkgDir, "client_wasm_bg.wasm"))
    wasm.initSync(js("({ module: wasmBytes })"))
    return wasm
}

/** Walk up from the working directory until `vendor/tlsn/pkg` appears — the test's cwd varies by task. */
private fun findPkgDir(fs: dynamic, path: dynamic): String? {
    var dir = js("process.cwd()").unsafeCast<String>()
    repeat(8) {
        val candidate = path.join(dir, "vendor", "tlsn", "pkg").unsafeCast<String>()
        if (fs.existsSync(path.join(candidate, "client_wasm.js")) as Boolean) return candidate
        dir = path.dirname(dir).unsafeCast<String>()
    }
    return null
}
