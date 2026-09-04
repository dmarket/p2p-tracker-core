/* tslint:disable */
/* eslint-disable */

export interface IoChannel {
    read(): Promise<Uint8Array | null>;
    write(data: Uint8Array): Promise<void>;
    close(): Promise<void>;
}


/**
 * A per-crate (per-`target`) level override.
 */
export interface CrateLogFilter {
    level: LoggingLevel;
    name: string;
}

/**
 * How much of the response body to reveal.
 *
 * TS: `\"all\" | \"none\" | { jsonPaths: string[] }`.
 *
 * `jsonPaths` reveals each selected field **with the key path that names it**
 * (and the enclosing braces/commas), not just the value — a value on its own
 * proves the bytes were in the response but not the field they came from. Two
 * consequences: the revealed body is not directly parseable JSON (hidden
 * siblings are `\'#\'` sentinel runs, though the structure is recoverable), and a
 * path that indexes into an array reveals the **whole array** — upstream never
 * commits array elements individually. Full account:
 * [`client_core::BodyReveal::JsonPaths`].
 */
export type BodyReveal = "all" | "none" | { jsonPaths: string[] };

/**
 * Logging configuration passed from JS to [`crate::initialize`].
 */
export interface LoggingConfig {
    /**
     * Default level applied to all targets. Defaults to `Info`.
     */
    level: LoggingLevel | undefined;
    /**
     * Optional per-crate (target-prefix) overrides.
     */
    crate_filters: CrateLogFilter[] | undefined;
}

/**
 * One-variant helper so the untagged [`RootStore`] union renders the bare
 * string `\"mozilla\"`. serde `untagged` matches a unit variant against `null`,
 * not a string, so the standard workaround is a `rename_all = \"lowercase\"`
 * single-variant enum whose only value serializes as `\"mozilla\"`.
 */
export type RootStorePreset = "mozilla";

/**
 * The HTTP request to prove.
 *
 * TS: `{ method: string; uri: string; headers?: [string, string][];
 * body?: number[] }`. Protocol-load-bearing headers (`Host`,
 * `Accept-Encoding: identity`, `Connection: close`) are injected by
 * [`client_core::issue`]; caller `headers` merge on top.
 */
export interface HttpRequest {
    method: string;
    uri: string;
    headers?: [string, string][];
    body?: number[];
}

/**
 * The issuance configuration (the locked OQ-D surface).
 *
 * TS: `{ serverName: string; maxSentData?: number; maxSentRecords?: number;
 * maxRecvDataOnline?: number; maxRecvRecordsOnline?: number;
 * maxRecvData?: number; rootStore?: RootStore }`.
 * Omitted caps keep the [`client_core`] contract defaults; omitted `rootStore`
 * keeps the Mozilla roots.
 */
export interface IssuanceConfig {
    serverName: string;
    maxSentData?: number;
    maxSentRecords?: number;
    maxRecvDataOnline?: number;
    maxRecvRecordsOnline?: number;
    maxRecvData?: number;
    rootStore?: RootStore;
}

/**
 * The selective-disclosure policy for [`present`](client_core::present).
 *
 * TS: `{ redactRequestHeaderValues: string[]; revealRequestTarget: boolean;
 * revealResponseHeaders: boolean; revealResponseBody: BodyReveal }`. All
 * fields are **required**: a silently-empty `redactRequestHeaderValues` would
 * leak `authorization` / `cookie` / `user-agent` (which
 * [`client_core::HttpRevealPolicy::default`] redacts), so a caller who
 * supplies a policy supplies it fully. `revealRequestTarget: false` withholds
 * the request line\'s whole path+query span from the presentation — query
 * strings routinely carry credentials (e.g. `?access_token=…`); the core
 * default (and pre-existing behavior) is `true`. The `present()` binding makes
 * the *whole policy* optional and falls back to the safe default when omitted.
 */
export interface RevealPolicy {
    redactRequestHeaderValues: string[];
    revealRequestTarget: boolean;
    revealResponseHeaders: boolean;
    revealResponseBody: BodyReveal;
}

/**
 * The target\'s HTTP response, captured during issuance and returned to JS for
 * convenience (the cryptographic material is the attestation/secrets blobs, not
 * this).
 *
 * TS: `{ status: number; headers: [string, string][]; body: number[] }`.
 */
export interface HttpResponse {
    status: number;
    headers: [string, string][];
    body: number[];
}

/**
 * Verbosity level for a subscriber or a per-crate filter.
 */
export type LoggingLevel = "Off" | "Trace" | "Debug" | "Info" | "Warn" | "Error";

/**
 * Where to anchor the target server\'s certificate chain.
 *
 * TS: `\"mozilla\" | { pem: string }`. `\"mozilla\"` (and an absent `rootStore`)
 * keeps the [`client_core::IssuanceConfig::new`] default (the Mozilla
 * public-web-PKI set); `{ pem }` supplies a custom CA bundle for test fixtures
 * or private endpoints.
 */
export type RootStore = RootStorePreset | { pem: string };


/**
 * The successful result of [`prove`]: the two opaque `postcard` blobs the
 * extension persists — `attestation` and `secrets` — plus the target's HTTP
 * `response`.
 *
 * `attestation` and `secrets` are `Uint8Array` (wasm-bindgen's native `Vec<u8>`
 * mapping), **not** tsify `number[]`: tsify renders `Vec<u8>` as `number[]`,
 * which would bloat these large blobs, so they are returned as byte arrays via
 * this `#[wasm_bindgen]` class rather than as fields of a tsify struct (branch
 * journal 08). Persist `attestation` + `secrets` together and later hand them
 * to [`present`].
 */
export class ProveOutput {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    /**
     * The notary-signed attestation, `postcard`-encoded (`Uint8Array`).
     */
    readonly attestation: Uint8Array;
    /**
     * The target's HTTP response observed during issuance (convenience only —
     * the cryptographic material is `attestation` + `secrets`, not this).
     */
    readonly response: HttpResponse;
    /**
     * The connection secrets, `postcard`-encoded (`Uint8Array`). Required by
     * [`present`]; persist alongside `attestation`.
     */
    readonly secrets: Uint8Array;
}

/**
 * Global spawner which spawns closures into web workers.
 */
export class Spawner {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
    intoRaw(): number;
    /**
     * Runs the spawner.
     */
    run(url: string): Promise<void>;
}

export class WorkerData {
    private constructor();
    free(): void;
    [Symbol.dispose](): void;
}

/**
 * Initializes the module: installs logging + the panic hook, starts the
 * `web-spawn` spawner, and builds the rayon global thread pool used by the MPC
 * backend. Call (and await) it before [`prove`](crate)ing.
 *
 * **Idempotent.** The tracing subscriber and rayon's *global* thread pool can
 * each be installed only once — a second attempt panics / throws — and
 * `web-spawn`'s spawner would start a second worker if re-run. Extension
 * lifecycle code can easily call `initialize` more than once, so a duplicate
 * call here is a no-op that returns `Ok(())`. On failure the one-time claim is
 * released, so a genuine retry (e.g. after the page adds the COOP/COEP headers
 * `start_spawner` needs) can proceed.
 *
 * A near-verbatim port of upstream `dep/tlsn/crates/wasm/src/lib.rs`, with the
 * idempotency guard added and logging configured locally rather than via
 * `tlsn-sdk-core` (see [`log`]).
 */
export function initialize(logging_config: LoggingConfig | null | undefined, thread_count: number): Promise<void>;

/**
 * Builds a verifiable `Presentation` from a persisted `(attestation, secrets)`
 * pair, revealing exactly what `policy` permits. Offline — no notary, no
 * network — so this needs no IO channel and no prior [`initialize`].
 *
 * `attestation_bytes` / `secrets_bytes` are the `postcard` blobs from a prior
 * [`prove`] (its [`ProveOutput`] `attestation` / `secrets`). `policy` is
 * optional: when omitted it falls back to the **safe**
 * `client_core::HttpRevealPolicy::default`, which redacts the `authorization` /
 * `cookie` / `user-agent` request-header values. Returns the `postcard`-encoded
 * `Presentation` (`Uint8Array`) to ship to a verifying backend.
 */
export function present(attestation_bytes: Uint8Array, secrets_bytes: Uint8Array, policy?: RevealPolicy | null): Uint8Array;

/**
 * Runs one full issuance over the two JS-supplied IO channels: MPC-TLS commit
 * with the notary over `notary_io`, the single HTTP exchange with the target
 * over `server_io`, the selective-disclosure prove step, then the
 * length-prefixed `postcard(AttestationRequest)` → `Attestation` phase-2
 * exchange — yielding a notary-signed attestation validated against the
 * prover's own view.
 *
 * - `config` — the locked issuance surface ([`IssuanceConfig`]).
 * - `request` — the single HTTP request to prove ([`HttpRequest`]).
 * - `progress_cb` — optional; invoked at each `ProgressStage` boundary with
 *   `{ step, progress, message, source: "wasm" }` (shape mirrors upstream
 *   tlsn-wasm's progress events).
 *
 * [`initialize`] must have been called (and awaited) first — it installs the
 * rayon pool the MPC backend needs. The returned `Promise` rejects with the
 * stringified error on any failure.
 */
export function prove(notary_io: IoChannel, server_io: IoChannel, config: IssuanceConfig, request: HttpRequest, progress_cb?: Function | null): Promise<ProveOutput>;

/**
 * Starts the thread spawner on a dedicated worker thread.
 */
export function startSpawner(): Promise<any>;

export function web_spawn_recover_spawner(spawner: number): Spawner;

export function web_spawn_start_worker(worker: number): void;

export type InitInput = RequestInfo | URL | Response | BufferSource | WebAssembly.Module;

export interface InitOutput {
    readonly __wbg_proveoutput_free: (a: number, b: number) => void;
    readonly initialize: (a: number, b: number) => any;
    readonly present: (a: number, b: number, c: number, d: number, e: number) => [number, number, number, number];
    readonly prove: (a: any, b: any, c: any, d: any, e: number) => any;
    readonly proveoutput_attestation: (a: number) => [number, number];
    readonly proveoutput_response: (a: number) => any;
    readonly proveoutput_secrets: (a: number) => [number, number];
    readonly __wbg_spawner_free: (a: number, b: number) => void;
    readonly __wbg_workerdata_free: (a: number, b: number) => void;
    readonly spawner_intoRaw: (a: number) => number;
    readonly spawner_run: (a: number, b: number, c: number) => any;
    readonly startSpawner: () => any;
    readonly web_spawn_recover_spawner: (a: number) => number;
    readonly web_spawn_start_worker: (a: number) => void;
    readonly ring_core_0_17_14__bn_mul_mont: (a: number, b: number, c: number, d: number, e: number, f: number) => void;
    readonly wasm_bindgen_1fa5c626ec3a32f9___convert__closures_____invoke___wasm_bindgen_1fa5c626ec3a32f9___JsValue__core_b7b33017ae0f8bb5___result__Result_____wasm_bindgen_1fa5c626ec3a32f9___JsError___true_: (a: number, b: number, c: any) => [number, number];
    readonly wasm_bindgen_1fa5c626ec3a32f9___convert__closures_____invoke___js_sys_27d0ccca2911ffcd___Function_fn_wasm_bindgen_1fa5c626ec3a32f9___JsValue_____wasm_bindgen_1fa5c626ec3a32f9___sys__Undefined___js_sys_27d0ccca2911ffcd___Function_fn_wasm_bindgen_1fa5c626ec3a32f9___JsValue_____wasm_bindgen_1fa5c626ec3a32f9___sys__Undefined_______true_: (a: number, b: number, c: any, d: any) => void;
    readonly wasm_bindgen_1fa5c626ec3a32f9___convert__closures_____invoke___wasm_bindgen_1fa5c626ec3a32f9___JsValue______true_: (a: number, b: number, c: any) => void;
    readonly wasm_bindgen_1fa5c626ec3a32f9___convert__closures_____invoke___js_sys_27d0ccca2911ffcd___futures__task__wait_async_polyfill__MessageEvent______true_: (a: number, b: number, c: any) => void;
    readonly memory: WebAssembly.Memory;
    readonly __wbindgen_malloc: (a: number, b: number) => number;
    readonly __wbindgen_realloc: (a: number, b: number, c: number, d: number) => number;
    readonly __wbindgen_exn_store: (a: number) => void;
    readonly __externref_table_alloc: () => number;
    readonly __wbindgen_externrefs: WebAssembly.Table;
    readonly __wbindgen_free: (a: number, b: number, c: number) => void;
    readonly __wbindgen_destroy_closure: (a: number, b: number) => void;
    readonly __externref_table_dealloc: (a: number) => void;
    readonly __wbindgen_thread_destroy: (a?: number, b?: number, c?: number) => void;
    readonly __wbindgen_start: (a: number) => void;
}

export type SyncInitInput = BufferSource | WebAssembly.Module;

/**
 * Instantiates the given `module`, which can either be bytes or
 * a precompiled `WebAssembly.Module`.
 *
 * @param {{ module: SyncInitInput, memory?: WebAssembly.Memory, thread_stack_size?: number }} module - Passing `SyncInitInput` directly is deprecated.
 * @param {WebAssembly.Memory} memory - Deprecated.
 *
 * @returns {InitOutput}
 */
export function initSync(module: { module: SyncInitInput, memory?: WebAssembly.Memory, thread_stack_size?: number } | SyncInitInput, memory?: WebAssembly.Memory): InitOutput;

/**
 * If `module_or_path` is {RequestInfo} or {URL}, makes a request and
 * for everything else, calls `WebAssembly.instantiate` directly.
 *
 * @param {{ module_or_path: InitInput | Promise<InitInput>, memory?: WebAssembly.Memory, thread_stack_size?: number }} module_or_path - Passing `InitInput` directly is deprecated.
 * @param {WebAssembly.Memory} memory - Deprecated.
 *
 * @returns {Promise<InitOutput>}
 */
export default function __wbg_init (module_or_path?: { module_or_path: InitInput | Promise<InitInput>, memory?: WebAssembly.Memory, thread_stack_size?: number } | InitInput | Promise<InitInput>, memory?: WebAssembly.Memory): Promise<InitOutput>;
