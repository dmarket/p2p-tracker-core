# client-wasm-transport — transport + pass-through prover façade for the wasm prover

The TS companion to the `client-wasm` browser-extension prover. Two layers,
both **wasm-free at runtime** (the emitted `dist/` contains no import of the
wasm pkg — a load-bearing invariant, see below):

- **Transport** — the two WebSocket `IoChannel`s that [`prove()`](../pkg)
  consumes, mirroring the native `connect_notary` / `connect_proxy` in
  `test-full-e2e/src/ws.rs` (the wasm build can't open raw TCP or use
  tungstenite, so the transport lives here and is injected across the wasm
  boundary).
- **Prover façade** — `createProver(wasm)`: a pass-through mirror of the wasm
  API (`initialize` / `prove` / `present`, same call shapes) over a
  **caller-injected** pkg module. It owns the setup (the pkg's default
  `init()`, then the rayon-pool `initialize()`, both memoized) and absorbs the
  wire conversions so callers never see the generated bindings' warts.

## What it exports

| export | kind | purpose |
| --- | --- | --- |
| `createProver(wasm)` | façade | wraps an injected pkg module in the `Prover` surface below |
| `Prover` | type | `initialize(logging?, threads?)` / `prove(notaryIo, serverIo, config, request, onProgress?)` / `present(attestation, secrets, policy?)` |
| `ClientWasmModule` | type | the injected-module contract (`import * as wasm from ".../pkg/client_wasm.js"` satisfies it) |
| `connectNotary(url, token, opts?)` | transport | the notary MPC channel, authenticated by the `Sec-WebSocket-Protocol` bearer subprotocol |
| `connectProxy(proxyBase, host, port, opts?)` | transport | the target byte pipe, tunnelled `WS → p2p-wss-proxy → TCP` |
| `WsIoChannel` | transport | the `WebSocket`→`IoChannel` adapter both helpers return |
| `NOTARY_SUBPROTOCOL` | transport | `"tlsn.notary.v2"` — the notary subprotocol marker |
| DTO types | types | `IssuanceConfig`, `HttpRequest`/`HttpResponse`, `RevealPolicy`, `BodyReveal`, `RootStore`, `LoggingConfig`, `ProveOutput`, `ProveProgress`/`ProgressCallback`, `IoChannel` |

Deliberately **not** exported: the pkg's web-spawn glue (`startSpawner`,
`web_spawn_*`) and instantiation lifecycle (`default` init / `initSync`) —
those are called through the pkg's own module identity and are internal to the
façade's setup.

## Usage

```ts
import * as wasm from "./pkg/client_wasm.js"; // the loose wasm-pack output — keep EXTERNAL in your bundle
import { createProver, connectNotary, connectProxy } from "client-wasm-transport";

const prover = createProver(wasm);
// Optional — prove() runs it with defaults if you skip it. Call it yourself to
// pick logging / thread count (defaults: null logging, hardwareConcurrency ≤ 8).
await prover.initialize({ level: "Info" }, navigator.hardwareConcurrency);

const notaryIo = await connectNotary("wss://notary.example/", bearerToken);
const serverIo = await connectProxy("wss://proxy.example", "api.example.com", 443);

const out = await prover.prove(
  notaryIo,
  serverIo,
  { serverName: "api.example.com" },        // IssuanceConfig (rootStore defaults to Mozilla roots)
  { method: "GET", uri: "/v1/resource" },   // HttpRequest (body may be a Uint8Array)
  (ev) => console.log(`${ev.step} ${Math.round(ev.progress * 100)}% — ${ev.message}`),
);
// out.attestation, out.secrets: Uint8Array — persist together
// out.response: { status, headers, body: Uint8Array } — convenience only

// Omitting the policy applies the core default: it redacts the authorization/
// cookie/user-agent header values but REVEALS the request target (path+query).
// Pass an explicit policy to withhold a credential-bearing query string:
const presentation = prover.present(out.attestation, out.secrets, {
  redactRequestHeaderValues: ["authorization", "cookie", "user-agent"],
  revealRequestTarget: false,
  revealResponseHeaders: true,
  revealResponseBody: "all",
});
// POST `presentation` (Uint8Array) to your validating backend.
```

## Why injection instead of `import ... from "client-wasm"` (load-bearing)

This package is **inlined** by its consumer's bundle (the extension's
offscreen bundle inlines `client-wasm-transport`), while the wasm-pack `pkg/`
must stay **loose** next to the bundle: its rayon spawner does
`new Worker(new URL("./spawn.js", import.meta.url))` and fetches the `.wasm`
via `import.meta.url`, so inlining it 404s the Worker and the `.wasm` — and a
*bare* `client-wasm` specifier is invisible to the consumer's
`--external:./pkg/*` glob. Worse, with `"sideEffects": false` a value
re-export lets tree-shaking silently drop the instantiation chain: a green
build whose exports throw on first call. Both failure modes were measured in
the branch journal (entries 20/21).

Injection is the shape that avoids all of it: the caller keeps the one pkg
import (external, at the pkg's real path), so the module's `import.meta.url`
semantics and single-instance identity are untouched — and because the module
namespace itself is handed in, the façade can still own `init()` +
`initialize()` on the caller's behalf.

## What the façade converts (vs. the raw pkg bindings)

| raw `pkg/client_wasm.d.ts` | façade |
| --- | --- |
| caller must run default `init()` before anything, `initialize()` before `prove()` | `initialize()` does both, memoized; `prove()` auto-initializes with defaults |
| `HttpRequest.body` / `HttpResponse.body` are `number[]` (tsify's `Vec<u8>`) | `Uint8Array` both ways |
| `LoggingConfig` fields required-but-nullable; `crate_filters` snake_case | truly optional; camelCase `crateFilters` |
| `progress_cb?: Function \| null` | typed `(event: ProveProgress) => void` |
| `prove()` returns a `ProveOutput` class the caller must `free()` / dispose | plain object; the wasm handle is freed by the façade |

Passed through **unchanged**: `IssuanceConfig`, `RevealPolicy` (all fields
required by design — a partial policy is refused rather than silently
completed), `BodyReveal`, `RootStore`, and `present`'s omitted-policy
fallback.

## The `IoChannel` contract

`WsIoChannel` implements the interface the wasm side expects. The shape is
declared locally in `src/types.ts` (so this package's `.d.ts` never references
the unpublished pkg) and **compile-time asserted identical** to the generated
`../pkg/client_wasm.d.ts` — which is itself generated from the Rust
`typescript_custom_section` in `../src/io.rs`, the single source of truth —
by `src/wasm-compat.ts`:

```ts
interface IoChannel {
  read(): Promise<Uint8Array | null>;   // next inbound frame, or null on EOF
  write(data: Uint8Array): Promise<void>;
  close(): Promise<void>;
}
```

Semantics (mirroring the native `WsByteStream`): `read()` returns one inbound
binary frame per call (the Rust adapter buffers leftovers, so frame boundaries
needn't align with reads); a socket close/error resolves `read()` with `null`
(EOF → `Ok(0)` in Rust); empty frames are skipped, not treated as EOF;
`write()` resolves once bytes are queued in the browser send buffer (the
browser flushes to the wire on its own — the reason the Rust write-completion
queue in `io.rs` is satisfied immediately here).

### Why two different channels

- **Notary** — browsers can't set an `Authorization` header on a WebSocket
  handshake, only subprotocols, so the bearer token rides in
  `Sec-WebSocket-Protocol` as `tlsn.notary.v2, bearer.<token>` (CLIENT_CONTRACT
  §2). `connectNotary` asserts the notary negotiated `tlsn.notary.v2` back (it
  echoes only the marker, never the token) before resolving.
- **Server (via proxy)** — a browser extension can't open raw TCP, so the target
  connection tunnels through the `p2p-wss-proxy` worker (`dep/cf-workers`; see
  JOURNAL toplevel/06): `connectProxy` opens
  `ws://<proxyBase>/?host=<host>&port=<port>` (no subprotocol/auth) and the
  worker relays bytes to a raw TCP socket. MPC-TLS runs over this pipe.

## Building & typechecking

The generated wasm package (`../pkg`) must exist first — `src/wasm-compat.ts`
asserts this package's locally-declared types against the generated
`client_wasm.d.ts` (resolved via the `paths` mapping in `tsconfig.json`).
There is **no runtime dependency** on `../pkg`: every import of it is
type-only and erased, so the emitted `dist/` imports nothing from it (verify
with `grep -rE 'from "client-wasm"' dist/` → nothing).

```sh
../build.sh            # 1. build the wasm pkg (produces ../pkg/client_wasm.d.ts)
npm install            # 2. dev-only (TypeScript); no runtime deps
npm run typecheck      # tsc --noEmit — includes the wasm-compat drift check
npm run build          # emit dist/ (ESM + .d.ts)
```

## Running outside a browser (Node)

`connect*` use the global `WebSocket` by default. To run under Node (e.g. the
deferred single-threaded e2e — see [`../RUN_E2E.md`](../RUN_E2E.md)), inject an
implementation: `connectNotary(url, token, { WebSocketImpl: WsFromNodePackage })`.

## Integrator note (cross-origin isolation)

The multi-threaded wasm build uses `SharedArrayBuffer`, so the embedding
page/extension **must be cross-origin isolated** (COOP: `same-origin` + COEP:
`require-corp`). Plan the extension/page headers accordingly. A failed
`initialize()` (e.g. missing those headers) releases the façade's memo, so
calling it again after fixing the environment retries for real.
