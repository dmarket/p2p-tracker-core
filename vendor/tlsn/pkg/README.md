# client-wasm — browser-extension prover (wasm `cdylib`)

> **Status — bindings + packaging shipped; live e2e deferred to its own scope.**
> The `#[wasm_bindgen]` surface (`initialize` / `prove` / `present` +
> `ProveOutput`), the tsify DTOs, and the JS-injected IO adapter (`src/io.rs`
> `JsIo`/`JsIoAdapter`) are all in place and the crate links for
> `wasm32-unknown-unknown`. `build.sh` packages it with `wasm-pack` into `pkg/`,
> and the WebSocket transport lives in [`ts/`](ts/README.md). The **automated
> end-to-end run is deferred to a later, separately-scoped effort** — both host
> paths carry real cost (Node needs a single-threaded rebuild; headless Chrome
> pulls in Chromium). The manual recipe is kept in [`RUN_E2E.md`](RUN_E2E.md).
> (Branch journal `feature/DMA-61/wasm-client`, entries 10–12.)

This crate is the wasm `cdylib` half of the prover: it injects a JS-backed
futures-io transport and calls `client-core`'s issuance + presentation flow. It
is **excluded from the root Cargo workspace** because it carries its own wasm
build target (nightly + `build-std` + a crate-local `.cargo/config.toml`), which
would break a host `cargo build` if it were a member — the same reason `dep/tlsn`
is excluded. It is reached only via path-deps. The whole crate is
`#![cfg(target_arch = "wasm32")]`, so on any other target it compiles to nothing.

## Exported bindings (over `client-core`, diverging from upstream `tlsn` wasm)

Upstream `dep/tlsn/crates/wasm` is the *interactive-verifier* prover and ends at
`reveal(reveal, commit)` over `tlsn-sdk-core` — no signed attestation. We instead
expose the **attestation** flow over `client-core` (which binds `tlsn` +
`tlsn-attestation`). The generated `pkg/client_wasm.d.ts` is the authoritative
signature source; in brief:

| export | shape | notes |
| --- | --- | --- |
| `initialize(logging?, threadCount)` | `Promise<void>` | installs logging + the panic hook, starts the `web-spawn` spawner, builds the rayon MPC pool. **Await once** before `prove`. |
| `prove(notaryIo, serverIo, config, request, progressCb?)` | `Promise<ProveOutput>` | runs MPC-TLS + the length-prefixed phase-2 exchange → `client_core::issue_with_progress`. |
| `ProveOutput` | `{ attestation, secrets: Uint8Array; response: HttpResponse }` | the two opaque `postcard` blobs to persist together, plus the target's HTTP response. `Uint8Array` (not tsify `number[]`) so the large blobs don't bloat — journal 08. |
| `present(attestation, secrets, policy?)` | `Uint8Array` | offline (no notary/network, no `initialize`) → `client_core::present`; omit `policy` for the safe default redactions. |

DTOs (`src/types.rs`, tsify): `IssuanceConfig`, `HttpRequest`, `HttpResponse`,
`RevealPolicy`, `RootStore` (`"mozilla" | { pem }`), `BodyReveal` (`"all" |
"none" | { jsonPaths }` — `jsonPaths` reveals each field **with its key path**,
is not directly `JSON.parse`-able, and widens to the whole array for any path
that indexes into one). Logging config: `LoggingConfig` / `LoggingLevel` /
`CrateLogFilter`. `progressCb` receives `{ step, progress, message, source:
"wasm" }` at each `ProgressStage` boundary.

The IO channels are **not** opened here — a browser extension can't open raw TCP
and can't set an `Authorization` header on a WebSocket. The transport that
performs the `Sec-WebSocket-Protocol` bearer handshake and supplies the two
channels lives in [`ts/`](ts/README.md); the client just consumes the injected
`IoChannel` (the interface is exported into the `.d.ts` from a
`typescript_custom_section` in `src/io.rs` — the single source of truth).

## Build & packaging

`./build.sh` runs `wasm-pack build --profile wasm --target web` and emits `pkg/`
(`client_wasm.js`, `client_wasm_bg.wasm`, `client_wasm.d.ts`, `package.json`, and
the patched `spawn.js` + `snippets/`). Pass args through — `./build.sh --dev` for
a fast unoptimized build.

- **Prerequisite: an LLVM `clang` with the wasm backend** (Apple clang won't
  compile `ring`'s C). `build.sh` sets `CC_wasm32_unknown_unknown` /
  `AR_wasm32_unknown_unknown` automatically on macOS; no-op on Linux.
- **`wasm-pack ≥ 0.14.0`** (for the custom `--profile`). `build.sh` guards the
  version.
- **Toolchain** (`rust-toolchain`, `.cargo/config.toml`): nightly + `rust-src`,
  `build-std=["panic_abort","std"]`, and the multi-threaded rustflags
  (`+atomics,+bulk-memory,+mutable-globals,+simd128`, `--shared-memory`,
  `--import-memory`, TLS/heap exports, `getrandom_backend="wasm_js"`).
- `[profile.wasm]` in `Cargo.toml` (lto, `panic="abort"`, `codegen-units=1`) —
  needed because the crate is its own build root (excluded from the workspace, so
  it can't inherit a workspace profile). Optimized `pkg/` is ~10 MB (dev ~51 MB).

## The JS/TS transport → [`ts/`](ts/README.md)

`client-wasm-transport`: the two browser-`WebSocket` `IoChannel`s that `prove()`
consumes (`connectNotary` / `connectProxy`), the browser mirror of the native
`test-full-e2e/src/ws.rs`. Type-only coupling to `pkg/` (no runtime import); see
its README for the contract, semantics, and a usage example.

## Running end-to-end → [`RUN_E2E.md`](RUN_E2E.md) (deferred scope)

The live run — wasm `prove()`/`present()` against the in-repo notary + echo
server through the ws→tcp proxy, verified by the validator — is **deferred to its
own future effort**. `RUN_E2E.md` holds the manual recipe, the two host options
(A: Node single-threaded — needs a rebuild dropping the threading rustflags +
`tlsn default-features=false`; B: headless Chrome + COOP/COEP), and the
`wrangler`/proxy prerequisite, as the starting point when that scope opens.

## Integrator note (cross-origin isolation)

The multi-threaded wasm build uses `SharedArrayBuffer`, so the embedding
page/extension **must be cross-origin isolated** (COOP `same-origin` + COEP
`require-corp`). Plan the extension/page headers accordingly.
