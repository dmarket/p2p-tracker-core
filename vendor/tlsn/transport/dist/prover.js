// The injection-based pass-through façade over the `client-wasm` prover.
//
// `createProver(wasm)` mirrors the wasm API surface — `initialize` / `prove` /
// `present`, same call shapes — while owning the setup the raw pkg leaves to
// the caller (module instantiation via the default-export `init()`, then the
// rayon-pool `initialize()`) and absorbing the wire conversions (tsify
// `number[]` bodies ↔ `Uint8Array`, the `crate_filters` snake_case wart, the
// disposable `ProveOutput` class → a plain object).
//
// **Why injection instead of `import ... from "client-wasm"`:** this package
// is inlined by the extension bundle while the wasm-pack `pkg/` must stay
// loose (its rayon spawner does `new Worker(new URL("./spawn.js",
// import.meta.url))` and fetches the `.wasm` via `import.meta.url` — inlining
// 404s both, and `--external:./pkg/*` cannot catch a bare specifier). A value
// re-export here would therefore either tree-shake away the instantiation
// chain (green build, dead API) or double-instantiate the module. Measured in
// branch journal entries 20/21; the caller passing in its own pkg import is
// the only shape that keeps `sideEffects: false` honest, the transport
// inlinable, and the wasm module single-instance.
//
// Because the *module namespace itself* is injected, its `import.meta.url`
// semantics are untouched: calling `wasm.default()` from here still resolves
// `client_wasm_bg.wasm` (and the worker `spawn.js`) relative to the loose
// `pkg/`, so the façade can safely own the init lifecycle.
/**
 * Wraps an injected `client-wasm` pkg module in the pass-through `Prover`
 * façade. The caller keeps ownership of the import — typically
 * `import * as wasm from "./pkg/client_wasm.js"` kept `--external` in the
 * bundle — and hands it in:
 *
 * ```ts
 * import * as wasm from "./pkg/client_wasm.js"; // loose, external
 * import { createProver } from "client-wasm-transport";
 *
 * const prover = createProver(wasm);
 * ```
 */
export function createProver(wasm) {
    return new WasmProver(wasm);
}
class WasmProver {
    #wasm;
    /** Memoized `wasm.default()` (module instantiation); cleared on failure. */
    #instantiation = null;
    /** True once instantiation has resolved — used only to enrich `present` errors. */
    #instantiated = false;
    /** Memoized full `initialize()` run; cleared on failure so a retry re-runs. */
    #initialized = null;
    constructor(wasm) {
        this.#wasm = wasm;
    }
    initialize(loggingConfig, threadCount) {
        this.#initialized ??= this.#runInitialize(loggingConfig, threadCount);
        return this.#initialized;
    }
    async prove(notaryIo, serverIo, config, request, progressCb) {
        await this.initialize();
        const out = await this.#wasm.prove(notaryIo, serverIo, config, toWireRequest(request), progressCb ?? null);
        try {
            return {
                attestation: out.attestation,
                secrets: out.secrets,
                response: fromWireResponse(out.response),
            };
        }
        finally {
            out.free();
        }
    }
    present(attestationBytes, secretsBytes, policy) {
        try {
            return this.#wasm.present(attestationBytes, secretsBytes, policy ?? null);
        }
        catch (cause) {
            // An un-instantiated module fails with a TypeError on the pkg's
            // module-level `wasm` binding — turn that into an actionable message.
            // (If the caller instantiated the pkg itself, #instantiated is false
            // but present() simply succeeds, so this never misfires on real
            // presentation errors — those are not TypeErrors.)
            if (!this.#instantiated && cause instanceof TypeError) {
                throw new Error("present() called before the wasm module was instantiated — " +
                    "await prover.initialize() (or a prove()) once first", { cause });
            }
            throw cause;
        }
    }
    async #runInitialize(loggingConfig, threadCount) {
        try {
            await this.#ensureInstantiated();
            await this.#wasm.initialize(toWireLogging(loggingConfig), threadCount ?? defaultThreadCount());
        }
        catch (err) {
            // Release the memo: wasm-side initialize releases its one-time claim on
            // failure precisely so a retry can proceed — mirror that here.
            this.#initialized = null;
            throw err;
        }
    }
    async #ensureInstantiated() {
        // `init()` is idempotent in the generated glue, but memoize anyway so an
        // injected module without the guard (or a hand-built one) is safe too.
        this.#instantiation ??= this.#wasm.default?.() ?? Promise.resolve();
        try {
            await this.#instantiation;
        }
        catch (err) {
            this.#instantiation = null; // e.g. the .wasm fetch failed — allow retry
            throw err;
        }
        this.#instantiated = true;
    }
}
// --- Conversions ----------------------------------------------------------------
function toWireLogging(config) {
    if (config == null)
        return null;
    return { level: config.level, crate_filters: config.crateFilters };
}
function toWireRequest(request) {
    const wire = { method: request.method, uri: request.uri };
    if (request.headers !== undefined)
        wire.headers = request.headers;
    // Always hand serde a plain number[] — tsify renders `Vec<u8>` as number[],
    // and a Uint8Array is not guaranteed to deserialize as one.
    if (request.body !== undefined)
        wire.body = Array.from(request.body);
    return wire;
}
function fromWireResponse(wire) {
    return { status: wire.status, headers: wire.headers, body: Uint8Array.from(wire.body) };
}
function defaultThreadCount() {
    const n = globalThis.navigator
        ?.hardwareConcurrency;
    // Cap at 8 — the extension harness default (prover-worker.js): each rayon
    // thread is a real web worker, and past ~8 the MPC gains nothing.
    return typeof n === "number" && n >= 1 ? Math.min(Math.floor(n), 8) : 8;
}
//# sourceMappingURL=prover.js.map