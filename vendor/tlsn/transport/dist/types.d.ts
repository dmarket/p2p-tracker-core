/**
 * The duplex byte channel `prove()` drives. Mirrors the `IoChannel`
 * `typescript_custom_section` in `client-wasm/src/io.rs` (asserted identical
 * in `wasm-compat.ts`); implemented by `WsIoChannel`.
 */
export interface IoChannel {
    /** Next inbound chunk, or `null` on EOF (Rust maps it to `Ok(0)`). */
    read(): Promise<Uint8Array | null>;
    write(data: Uint8Array): Promise<void>;
    close(): Promise<void>;
}
/** Verbosity level for a subscriber or a per-crate filter. */
export type LoggingLevel = "Off" | "Trace" | "Debug" | "Info" | "Warn" | "Error";
/** A per-crate (per-`target`) level override. */
export interface CrateLogFilter {
    level: LoggingLevel;
    name: string;
}
/**
 * Logging options for `Prover.initialize`. Façade shape: both fields are
 * truly optional (the generated `LoggingConfig` marks them required-but-
 * nullable), and `crateFilters` is camelCase — the wasm wire field is
 * `crate_filters`, the one snake_case field in the whole generated surface
 * (`client-wasm/src/log.rs` lacks `rename_all = "camelCase"`; journal 21).
 * `createProver` converts.
 */
export interface LoggingConfig {
    /** Default level applied to all targets. Defaults to `Info`. */
    level?: LoggingLevel;
    /** Optional per-crate (target-prefix) overrides. */
    crateFilters?: CrateLogFilter[];
}
/**
 * One-variant helper so the untagged `RootStore` union renders the bare
 * string `"mozilla"` (see `client-wasm/src/types.rs`).
 */
export type RootStorePreset = "mozilla";
/**
 * Where to anchor the target server's certificate chain. `"mozilla"` (and an
 * absent `rootStore`) keeps the `client_core` default (the Mozilla public-
 * web-PKI set); `{ pem }` supplies a custom CA bundle for test fixtures or
 * private endpoints.
 */
export type RootStore = RootStorePreset | {
    pem: string;
};
/**
 * The issuance configuration (the locked OQ-D surface). Omitted caps keep the
 * `client_core` contract defaults; omitted `rootStore` keeps the Mozilla
 * roots. Identical to the generated `IssuanceConfig` — passed through as-is.
 */
export interface IssuanceConfig {
    serverName: string;
    maxSentData?: number;
    /** Optional sent application-data record budget. */
    maxSentRecords?: number;
    /** Response bytes preprocessed for online decryption (default: 2 KiB). */
    maxRecvDataOnline?: number;
    /** Optional received application-data record budget for online decryption. */
    maxRecvRecordsOnline?: number;
    maxRecvData?: number;
    rootStore?: RootStore;
}
/**
 * The HTTP request to prove. Protocol-load-bearing headers (`Host`,
 * `Accept-Encoding: identity`, `Connection: close`) are injected by
 * `client_core::issue`; caller `headers` merge on top.
 *
 * Façade shape: `body` also accepts a `Uint8Array` — the wasm wire type is
 * `number[]` (tsify's `Vec<u8>` rendering); `createProver` converts.
 */
export interface HttpRequest {
    method: string;
    uri: string;
    headers?: [string, string][];
    body?: Uint8Array | number[];
}
/**
 * The target's HTTP response, captured during issuance and returned for
 * convenience (the cryptographic material is the attestation/secrets blobs,
 * not this).
 *
 * Façade shape: `body` is a `Uint8Array` — the wasm wire type is `number[]`
 * (tsify's `Vec<u8>` rendering); `createProver` converts.
 */
export interface HttpResponse {
    status: number;
    headers: [string, string][];
    body: Uint8Array;
}
/**
 * How much of the response body to reveal in a presentation.
 * `"all" | "none" | { jsonPaths: [...] }`. Identical to the generated type.
 *
 * `jsonPaths` reveals each selected field **with the key path that names it**
 * (plus the enclosing braces and commas), not just the value — a value on its
 * own proves those bytes were in the authenticated response, but nothing ties
 * them to the field they came from. Two things to plan for: the revealed body
 * is **not** directly `JSON.parse`-able, because hidden siblings come back as
 * `'#'` sentinel runs (the structure is recoverable — commas and braces are
 * revealed — but the bytes are not JSON); and a path that indexes into an
 * array, e.g. `"favoriteColors.0"`, reveals the **whole array**, because the
 * notary never commits array elements individually so an element cannot be
 * proven on its own.
 */
export type BodyReveal = "all" | "none" | {
    jsonPaths: string[];
};
/**
 * The selective-disclosure policy for `present`. Identical to the generated
 * type, **all fields required** by design: a silently-empty
 * `redactRequestHeaderValues` would leak `authorization` / `cookie` /
 * `user-agent`, so a caller who supplies a policy supplies it fully. Omitting
 * the whole policy falls back to `client_core::HttpRevealPolicy::default`,
 * which redacts those three header values — but note it also keeps
 * `revealRequestTarget: true`, i.e. **the default reveals the request
 * path+query**; pass an explicit policy with `revealRequestTarget: false` to
 * withhold a credential-bearing query string.
 */
export interface RevealPolicy {
    redactRequestHeaderValues: string[];
    revealRequestTarget: boolean;
    revealResponseHeaders: boolean;
    revealResponseBody: BodyReveal;
}
/**
 * The successful result of `Prover.prove`: the two opaque `postcard` blobs to
 * persist **together** — later handed to `present` — plus the target's HTTP
 * response.
 *
 * Façade shape: a plain object, not the wasm `ProveOutput` class. The façade
 * copies the fields out and `free()`s the wasm-side handle, so callers get no
 * `free()` / `[Symbol.dispose]()` obligation (and no `ESNext.Disposable` lib
 * requirement — journal 21).
 */
export interface ProveOutput {
    /** The notary-signed attestation, `postcard`-encoded. */
    attestation: Uint8Array;
    /** The connection secrets, `postcard`-encoded. Persist alongside `attestation`. */
    secrets: Uint8Array;
    /** The target's HTTP response observed during issuance (convenience only). */
    response: HttpResponse;
}
/**
 * The progress event delivered to `prove`'s progress callback. Shape mirrors
 * the Rust `emit_progress` in `client-wasm/src/lib.rs` — `step` / `progress`
 * come from `client_core::ProgressStage` (stable across bindings); `message`
 * is wasm-side UI copy; `source` is always `"wasm"`.
 */
export interface ProveProgress {
    /** Stable stage id, e.g. `"mpc_setup"`, `"reveal"`, `"finalized"`. */
    step: string;
    /** Nominal completion in `[0, 1]`. */
    progress: number;
    /** Human-readable status line for the current stage. */
    message: string;
    /** Always `"wasm"` (distinguishes wasm-emitted events from host-side ones). */
    source: "wasm";
}
/** A `prove()` progress callback (the generated signature types it `Function`). */
export type ProgressCallback = (event: ProveProgress) => void;
//# sourceMappingURL=types.d.ts.map