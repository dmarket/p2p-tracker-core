import type { HttpRequest, IoChannel, IssuanceConfig, LoggingConfig, LoggingLevel, CrateLogFilter, ProgressCallback, ProveOutput, RevealPolicy } from "./types.js";
/** Wire shape of the generated `LoggingConfig` (required-but-nullable, snake_case). */
export interface WasmLoggingConfig {
    level: LoggingLevel | undefined;
    crate_filters: CrateLogFilter[] | undefined;
}
/** Wire shape of the generated `HttpRequest` (`body` is tsify `number[]`). */
export interface WasmHttpRequest {
    method: string;
    uri: string;
    headers?: [string, string][];
    body?: number[];
}
/** Wire shape of the generated `HttpResponse` (`body` is tsify `number[]`). */
export interface WasmHttpResponse {
    status: number;
    headers: [string, string][];
    body: number[];
}
/**
 * The members of the generated `ProveOutput` class the façade reads. The
 * getters copy out of wasm memory, so after reading all three the handle is
 * `free()`d eagerly rather than left to `[Symbol.dispose]`.
 */
export interface WasmProveOutput {
    readonly attestation: Uint8Array;
    readonly secrets: Uint8Array;
    readonly response: WasmHttpResponse;
    free(): void;
}
/**
 * The subset of the generated wasm module `createProver` drives — exactly
 * what `import * as wasm from ".../pkg/client_wasm.js"` provides
 * (`wasm-compat.ts` asserts the generated module satisfies this). The
 * web-spawn glue (`startSpawner`, `web_spawn_*`) is deliberately not part of
 * the contract: it is called by the pkg's own worker snippet through the
 * pkg's module identity and is meaningless behind a façade (journal 21).
 */
export interface ClientWasmModule {
    /**
     * wasm-pack `--target web`'s default-export `init` (`__wbg_init`): fetches
     * and instantiates the `.wasm` relative to the pkg module. Optional so a
     * pre-instantiated or hand-assembled module can be injected; when present,
     * the façade calls it (once) before anything else.
     */
    default?: () => Promise<unknown>;
    initialize(loggingConfig: WasmLoggingConfig | null | undefined, threadCount: number): Promise<void>;
    prove(notaryIo: IoChannel, serverIo: IoChannel, config: IssuanceConfig, request: WasmHttpRequest, progressCb?: ProgressCallback | null): Promise<WasmProveOutput>;
    present(attestationBytes: Uint8Array, secretsBytes: Uint8Array, policy?: RevealPolicy | null): Uint8Array;
}
/**
 * The pass-through prover surface: the wasm API's three calls with the same
 * shapes, minus the setup and wire warts (see each method).
 */
export interface Prover {
    /**
     * One-time setup: instantiates the wasm module (the pkg's default `init()`,
     * if the injected module carries one) and starts logging + the rayon thread
     * pool (`wasm.initialize`). Idempotent — concurrent and repeat calls share
     * one underlying run; arguments of later calls are ignored. On failure the
     * memo is released so a genuine retry (e.g. after fixing COOP/COEP) re-runs.
     *
     * `threadCount` defaults to `navigator.hardwareConcurrency` clamped to
     * [1, 8] (8 is the extension harness default).
     */
    initialize(loggingConfig?: LoggingConfig | null, threadCount?: number): Promise<void>;
    /**
     * One full issuance over the two injected IO channels — MPC-TLS with the
     * notary over `notaryIo`, the single HTTP exchange with the target over
     * `serverIo` — yielding the notary-signed attestation. Pass-through to
     * `wasm.prove` with the conversions applied (request/response `body` bytes,
     * typed progress callback) and the returned wasm handle copied out and
     * freed. Runs `initialize()` with defaults first if it was never called.
     */
    prove(notaryIo: IoChannel, serverIo: IoChannel, config: IssuanceConfig, request: HttpRequest, progressCb?: ProgressCallback | null): Promise<ProveOutput>;
    /**
     * Builds a verifiable `Presentation` from a persisted
     * `(attestation, secrets)` pair, revealing exactly what `policy` permits —
     * pass-through to `wasm.present`. Offline (no notary, no network, no rayon
     * pool), but the wasm module must be *instantiated*: await `initialize()`
     * (or a `prove()`) once beforehand, or instantiate the pkg yourself before
     * injecting it. When `policy` is omitted the wasm side applies
     * `client_core::HttpRevealPolicy::default` — it redacts the
     * `authorization` / `cookie` / `user-agent` header values but **reveals the
     * request target (path+query)**; supply an explicit policy to withhold it.
     */
    present(attestationBytes: Uint8Array, secretsBytes: Uint8Array, policy?: RevealPolicy | null): Uint8Array;
}
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
export declare function createProver(wasm: ClientWasmModule): Prover;
//# sourceMappingURL=prover.d.ts.map