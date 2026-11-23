# The TLSN prover: where it comes from, how it ships, how it loads

The optional proof path runs a prebuilt WebAssembly prover from the `dmarket/steam-provenance` project.
This file is the integration contract on our side; the prover's own consumer contract is the `README.md`
inside its npm package.

## Choosing the issuance mode

`TrackerConfig.notary.mode` accepts `"mpc"` (default) or `"proxy"`. Pass that config to the browser's
`proveNotaryTransition` or to `runHostedProof`. For `createHeadlessTracker`, use
`prover: { mode: "proxy" }`; the Node SDK config file accepts `"prover": { "mode": "proxy" }`.
The mode travels to each child in `ProverSettings`. Omitted modes keep MPC, including old IPC messages.
Invalid modes fail configuration; there is no automatic protocol fallback.

Proxy uses `createProxyProver` / `proveProxy` and exactly one notary channel. The notary must enable
proxy and allow the request's TLS server name in its operator-owned routes. `proxyBaseUrl` is the
browser's **MPC target tunnel**, not a notary-proxy setting. Proxy never calls `connectTarget` or
`connectProxy`, and a child `targetAddress` override is rejected. The notary establishes the target TCP
connection; TLS identity and custom test roots still come from the proven read and `rootStorePem`.

Both modes use the same HTTP request, authentication, presentation policy and response-status hook.
Proxy passes the configured/per-read send and receive ceilings under `limits`, without MPC send-budget
sizing. MPC online byte/record preprocessing settings are not sent. Other proxy wire, record, handshake
and certificate ceilings retain the prover's finite defaults; the notary also applies its own limits.
Tracing identifies `mode=proxy target=notary`. Worker lifecycle, cross-origin isolation, child deadlines
and concurrency limits still apply.

**Assets:** the pinned 1.2.0 package supports MPC, proxy and Worker egress. Selecting proxy
with old assets (1.0.1 or earlier, for example through `assetsDir`) fails before WASM compilation, worker
initialization or the notary upgrade. Headless checks the actual JS exports when creating the tracker; the
SDK checks once at startup and disables proving with a logged reason on incompatible assets. The bounded
startup probe imports JS only and never initializes WASM. Proxy support is additive; the pin does not imply
that a proxy-enabled production notary is deployed.

Injected `WasmProverModule` implementations retain their one-argument `initialize` entry point.
To support proxy they must also implement `initialize(threadCount, mode: IssuanceMode)` and retain that
mode for subsequent proofs; the default overload rejects proxy so old modules cannot silently issue
MPC instead.

### Worker egress for proxy issuance

Select `NotaryConfig(mode = "proxy", transport = "egress", notaryUrl = workerUrl)` on the browser/hosted
path. Headless and SDK configuration use the same two choices under `prover`:

```json
{
  "prover": {
    "mode": "proxy",
    "transport": "egress",
    "notaryUrl": "wss://worker.example/notary/egress/v1",
    "assetsDir": "/absolute/path/to/egress-capable/package"
  }
}
```

`transport` defaults to `"direct"`, including old IPC. Egress requires proxy mode; invalid combinations
fail configuration. The Worker frontend goes in `notaryUrl`, not `proxyBaseUrl`. It must be enabled and
paired with a Worker-transport notary pool whose target policy allows the proven read. The client never
selects a target address or upstream notary, and never falls back to a direct route or MPC.

The package must export `connectEgressNotary` as well as `createProxyProver` / `proveProxy`. A package
that supports only direct proxy fails before WASM compilation, worker initialization or signing an
upgrade. The pinned 1.2.0 package exports all three; an `assetsDir` override must too.

Egress uses `tlsn.notary.egress.v1` and the existing proxy proof/disclosure API. Browser bearer tokens
and injected `WebSocketImpl` authentication work as before. A signed gateway must sign the actual
Worker-facing URL and mint the user Authorization credential before forwarding to the Worker. The
Worker service credential belongs only in Worker/notary configuration, never in tracker options.
WSS is required except loopback WS fixtures; credentials, query strings and fragments in the URL are
rejected by the connector. Its 240-second total session deadline may be shortened by the server;
existing host/child deadlines still apply and can end the operation earlier.

Traces identify `mode=proxy transport=egress target=worker`. The tracker aborts the channel on success,
failure or cancellation; a directional FIN alone would not release a session blocked on credits. It
also closes the owned WebSocket if cancelled before the egress HELLO arrives. The host target connector
is unused. Injected prover modules must implement `initialize(threadCount, mode, transport)` to opt in;
the default overload rejects egress so older modules cannot silently use direct routing.

## Where it comes from

One exact release of the npm package
[`@dmarket/steam-provenance`](https://www.npmjs.com/package/@dmarket/steam-provenance), declared as a
Kotlin/JS dependency of `:core`'s `jsMain` in [`build.gradle.kts`](build.gradle.kts). Two values pin it,
side by side in `TlsnProverPin`
([`build-logic/src/main/kotlin/TlsnProver.kt`](../build-logic/src/main/kotlin/TlsnProver.kt)), the one pin
for both npm packages:

| Value | What it pins |
|---|---|
| `TlsnProverPin.VERSION` | the exact npm version, never a range or a dist-tag (`^1.0.1` would also match every later release) |
| `TlsnProverPin.INTEGRITY` | the registry's `dist.integrity` for that version: a sha512 over the whole npm tarball |

The package is installed, never imported. No Kotlin code names it, so no bundler, ours or a host's, ever
sees it; `core/npm/prepare-package.sh` replaces the generated `dependencies` of each published
`package.json` with the ones its partial lists, and neither lists the prover, so installing either
package does not install a second copy.

The `copyTlsnProver` task takes exactly three paths out of the installed package and puts them at the
root of our JS distribution, and therefore of our npm tarball:

```
pkg/            wasm-pack output — the prover. STAYS LOOSE at runtime, never inlined.
transport/      WebSocket transport + `createProver` façade (dist/ + its package.json and README.md).
VERSION         provenance manifest (GIT_SHA, WASM_SHA256, …), shell-sourceable
```

`:headless-js` copies the same three paths (`headlessPackageFiles`) into its own distribution, so
`@dmarket/p2p-tracker-headless` ships the same prover byte for byte, and a bump changes both packages.

Everything else in the package stays behind. Its `package.json`, `README.md`, `CHANGELOG.md`, `LICENSE`
and `THIRD_PARTY_NOTICES.md` would collide with ours or describe the wrong package, and `SHA256SUMS` also
covers that `README.md`, so a `-c` run over our copy would report a spurious miss.

Earlier releases took the prover from a copy committed to this repository under `vendor/tlsn/`,
because the only channel then was a CircleCI artifact and its retention is finite. npm versions are
permanent, so that reason is gone, and the committed copy only proved itself against a manifest
committed beside it.

## What the build checks

`verifyTlsnProver` checks the installed package and fails the build on any miss. CI runs no separate step.
Nothing reads the package before this task has passed: `copyTlsnProver`, `:headless-js`'s
`headlessPackageFiles` and every JS test task of `:core` depend on it. The tests matter as much as the copy, because `WasmRevealPolicyContractTest` loads and runs
the real `pkg/`, and CI runs `check` before the distribution task. Each run logs the result:

```
TLSN prover @dmarket/steam-provenance@<version> verified (<n> files): GIT_SHA=… WASM_SHA256=… TRANSPORT_VERSION=…
```

1. **Integrity.** For the default registry build, the lockfile entry the npm install used must carry `TlsnProverPin.INTEGRITY`. yarn checks
   the download against the registry's integrity and records it there, so this ties the whole npm tarball
   to a value committed in this repository.
2. **Version and commit.** The installed `package.json` must be `TlsnProverPin.VERSION`, and its `gitHead`
   must equal `GIT_SHA` in `VERSION`.
3. **Checksums.** The files we ship must be exactly the `pkg/`, `transport/` and `VERSION` entries of the
   package's `SHA256SUMS`, and each must match it. A shipped file the manifest does not list fails, and so
   does a listed file that is missing (a missing `spawn.js` snippet would otherwise build green and fail
   only when `initialize()` starts its workers).
4. **The binary.** `WASM_SHA256` in `VERSION` must be the digest of the `.wasm` actually installed.
5. **Channel.** A stable `VERSION_NAME` (no `-SNAPSHOT`) must not pin a `SNAPSHOT` prover. `development`
   may pin one; a `development` -> `main` merge brings that pin along, and this check stops it before a
   stable release reaches npm. Checked first, before anything is read from the install.

Checks 2–4 compare the package with itself and catch a damaged or half-replaced install; only check 1
compares it with this repository. None of them proves the `.wasm` is what the pinned upstream revision
compiles to; that needs a reproducible rebuild, which does not exist yet (see
[`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)).

The integrity pin lives in build-logic rather than in a committed lockfile because `kotlin-js-store/`
is not tracked, and tracking it would freeze every transitive npm dependency of the Kotlin/JS toolchain
as well. The pin covers the one npm input that ships, and nothing else.

To inspect an installed copy by hand:

```bash
cd build/js/node_modules/@dmarket/steam-provenance && shasum -a 256 -c SHA256SUMS && cat VERSION
npm view @dmarket/steam-provenance@<version> gitHead dist.integrity
```

## How the library loads it

`notary/WasmProverModule.kt` resolves both modules **at runtime against the extension root**, with
`runtime.getURL("pkg/client_wasm.js")` and `runtime.getURL("transport/dist/index.js")`, and `import()`s
them lazily on the first proof. A literal relative specifier would not survive the host's bundler: it
rewrites relative paths against the emitted chunk, and the result points into a `node_modules` path that
does not exist in a packed extension. A runtime URL is invisible to static analysis, so the host needs no
`external` rule, alias or stub for it.

The host's one duty is to ship `pkg/` and `transport/` **at the extension root** as web-accessible
resources, copied out of the installed `@dmarket/p2p-tracker-core` package (they sit at its root, next to
`p2p-tracker-core.mjs`). An extension can only load what it packages.

## Hosting outside the browser: the four seams

`BundledWasmProverModule(onProgress, host)` takes a `WasmProverHost` (`notary/WasmProverHost.kt`). Each of its
four members is `null` by default, and `null` is what the extension runs, so `WasmProverHost()` changes
nothing on web. A host outside the browser replaces the ones it cannot use:

| Seam | Default (the extension) | Why a host replaces it |
|---|---|---|
| `assetUrl(path)` | `runtime.getURL(path)`: `pkg/` and `transport/` at the extension root | there is no extension root; return the files' location on disk as `file:` URLs |
| `wasmModule()` | none: the façade calls the wasm-pack init with no argument and it fetches `client_wasm_bg.wasm` next to `client_wasm.js` | Node's `fetch` refuses `file:` URLs; compile the `.wasm` yourself and return the `WebAssembly.Module`, which goes to the init as `module_or_path`. Called once |
| `notaryWebSocket()` | the global `WebSocket`; the transport's `connectNotary(url, token)` offers `tlsn.notary.v2, bearer.<token>` as subprotocols | return a `WebSocket` constructor that sends its own upgrade headers and drops the `bearer.` entry. It is passed as `connectNotary`'s `WebSocketImpl`, and asked for **before every connect**, so headers signed for one upgrade are never reused |
| `connectTarget(serverName, port)` | the transport's `connectProxy` through `proxyBaseUrl` (`p2p-wss-proxy`), because a browser cannot open TCP | resolve an open `IoChannel` (`read()` → bytes or `null` at EOF, `write(bytes)`, idempotent `close()`) over a TCP socket. It is still dialled lazily, on the prover's first write, and `proxyBaseUrl` is then unused; the issuance trace line says `target=host` |

A fifth member, `onTargetResponse(status)`, is handed the target's HTTP status once the issuance is done and before
the presentation is built; throwing refuses the proof, so a host can decline to present an error page. `null` (the
extension) does not read the status.

This source set ships to the browser as well, so the library contains no Node code for any of these: the host
builds the socket, the WebSocket and the compiled module and hands them in. For the target leg the transport
ships the pieces: `connectTcp(host, port, { connect })` and `TcpIoChannel` adapt a host's socket, and
`transport/dist/node.js` (its `/node` export) is a ready Node connector with `TCP_NODELAY`, which the SDK's
prover process uses. Dial timeouts and why the leg failed stay the host's to track. The prover's threads are the
other half of a Node host and are entirely its own: `pkg/`'s spawner starts browser `Worker`s, which Node does
not have.

## Proving from a headless host

`HeadlessTracker` proves only when its platform supplies `HeadlessPlatform.proving`: a `ProofHost` that runs each
proof where the prover can run (a Node host forks a process per proof), at most one at a time by default and two at
most. The tracker's prover (`HostedNotaryProver`) hands the host:

- a `HostedProofRequest`: the credential-free `NotaryProofRequest`, and beside it the Steam token the proven read
  needs and the `sessionid` a cookie-authenticated read sends;
- a `NotaryUpgradeSigner`. There is no DMarket session token to offer the notary: the gateway authenticates the
  upgrade by the Trading API signature (`X-Api-Key`, `X-Sign-Date`, `X-Request-Sign` over `GET` of the notary
  URL's path, trailing slash included, with an empty body). The host asks for headers right before its prover
  connects, so start-up does not use up the signature's ±2 minute window, and a retried connect gets a fresh one.
  The key itself never leaves the tracker.

No proof starts on a Steam token with less than 30 minutes left. The host answers a presentation or a
`ProofFailure`; `ProofFailures.classify` turns what it observed (the upgrade's status, the close code, Steam's
status or dial error, the prover's message) into one, and the loop sees the failure as any prover's.

The prover side is `runHostedProof(proof, config, host, now)`: the extension's wasm prover over the four seams,
with `notaryWebSocket` required. It fills the transport's `bearer.` entry with a placeholder, which a
header-authenticated socket drops; nothing in it reaches the notary.

## Bundling — the part that is easy to get silently wrong

Getting this wrong produces a green build and a dead prover, with no error at either end.

- **`pkg/` must stay loose.** Its rayon spawner does
  `new Worker(new URL("./spawn.js", import.meta.url))` and fetches the `.wasm` through
  `import.meta.url`. Inlining rewrites exactly those paths. Copy the directory as it is; never hand it to a
  bundler, and never reach it through a bare specifier — `@dmarket/steam-provenance` deliberately exports
  no `./pkg/*` subpath for the same reason.
- **`transport/dist/` holds no runtime edge to `pkg/`.** The façade takes the wasm module namespace as an
  argument and every specifier inside it is relative, so copying it next to `pkg/` is enough.

You do **not** need `--alias:client-wasm=…`. That was the mitigation for a rejected design; nothing here
emits a bare specifier.

The prover's context must be cross-origin isolated (COOP `same-origin` + COEP `require-corp`) at **any**
`NotaryConfig.threadCount`, 1 included: the shipped module is built with shared memory and `initialize`
always starts the worker spawner, so it needs `SharedArrayBuffer`. See the KDoc on `threadCount`.

## Hosting a cookie-authenticated proven read

Proving a `steamcommunity.com` endpoint needs the Steam web session, and the library reads it in
whatever context runs the prover rather than accepting it as an argument — see
`SteamProofCookieSource`. Two consequences for the host:

- the prover's context (the offscreen document on web) needs the **`"cookies"` permission** and
  **`host_permissions` for `https://steamcommunity.com`**, in addition to the isolation above. Without
  them the cookie reads back `null` and the proof fails before any MPC starts, which is the intended
  failure — a cookie-less request would be answered logged-out and that answer would be attested;
- nothing about the `notaryProofDelegate` signature changes. The session never crosses the message
  boundary, so a host that already relays `(requestJson, notaryToken, steamAccessToken)` needs no edit.

Token-authenticated reads — both trade axes — are unaffected and require neither permission.

## Open ask: request-body disclosure

`RevealPolicy` has no request-body field (`redactRequestHeaderValues`, `revealRequestTarget`,
`revealResponseHeaders`, `revealResponseBody` only), so a proven **write** discloses its form body to
the verifier, `sessionid` included. `steamLoginSecure` stays protected because it rides a `cookie`
header, which *is* redactable, so the exposure is one CSRF token that is useless without that cookie —
but it is a real disclosure, and it is why `ProvenRead.acknowledgeRequestBodyDisclosure` exists and why
no write kind is enabled by default.

What would close it: a `revealRequestBody: BodyReveal` on `RevealPolicy`, mirroring
`revealResponseBody`. Raise it against `dmarket/steam-provenance` at the pinned release's `gitHead`; when
it lands, wire it through `revealPolicy()` in `WasmProverModule.kt` and drop the acknowledgement flag.

## The native prover on Android

The same pinned package carries the prover's C ABI for Android under `native/android/`: for `arm64-v8a`,
`armeabi-v7a` and `x86_64`, `libprovenance_client_cffi.so` (and a `.a` we do not use), the header
`include/provenance_client.h`, `BUILD.json` (the source commit and the SHA-256 of every file there),
`toolchain.txt` (NDK r30, API 24) and the notices. It is a blocking C library with no JNI and no transport: the
JNI layer and the connections are ours. Its contract is `client-cffi/README.md` in steam-provenance at the
package's `gitHead`.

### Where it ships

The module `:prover-android` (Maven `com.dmarket.p2p:trade-tracker-prover-android`, same `GROUP` and
`VERSION_NAME`) is a plain `com.android.library`: the Kotlin Multiplatform Android library plugin of AGP 9.3.1 has
no `externalNativeBuild` and no `ndkVersion`. `:core`'s `androidMain` depends on it, so the app gets it through
`trade-tracker-core`; the jvm and js targets never see it. Its AAR carries, per ABI,
`jni/<abi>/libprovenance_client_cffi.so` (the package's bytes, unstripped) and `jni/<abi>/libprovenance_jni.so`
(ours). AGP's built-in Kotlin needs KGP and AGP in one class loader, so the root build loads
`com.android.library` with `apply false` beside the Kotlin plugins.

### How the `.so` are checked

`:prover-android:verifyProverNativeLibs` (`VerifyProverNativeLibs` in
[`build-logic/src/main/kotlin/TlsnProver.kt`](../build-logic/src/main/kotlin/TlsnProver.kt)) runs after
`:core:verifyTlsnProver` and before anything in the module builds. The package's `SHA256SUMS` does not cover
`native/`, so the chain is: the lockfile integrity ties the whole tarball to `TlsnProverPin.INTEGRITY`
(check 1 above), then

1. `BUILD.json`'s `gitHead` equals the installed `package.json`'s `gitHead` and `VERSION`'s `GIT_SHA`, and its
   platform is `android`;
2. each `.so`, the header and both notices match the SHA-256 `BUILD.json` lists, and `BUILD.json` lists the
   library for exactly the three ABIs;
3. the committed copies under [`../licenses/steam-provenance-android/`](../licenses/steam-provenance-android/)
   are the package's notices byte for byte.

Any miss fails the build; one changed byte in a `.so` does. The task then stages the libraries as the module's
generated `jniLibs` and the header for CMake. `packaging.jniLibs.keepDebugSymbols` keeps AGP from stripping the
package's `.so`, so the AAR holds the bytes `BUILD.json` hashes.

### The JNI layer

`prover-android/src/main/cpp/provenance_jni.c`, built by CMake 4.1.2 with NDK 30.0.16248370 for API 24 and
`-Wl,-z,max-page-size=16384` (LOAD alignment 0x4000 on every ABI), linked dynamically by name against the
package's library, which has no SONAME (`DT_NEEDED libprovenance_client_cffi.so`). The NDK and CMake must be
in the Android SDK (`sdkmanager "ndk;30.0.16248370" "cmake;4.1.2"`; CI installs them). Without them the module
configures without its native build, so JVM/JS tasks, lint and `check` still run, and `requireProverNdk` fails
every task that packages native libraries: no AAR builds without `libprovenance_jni.so`.

`NativeProverBridge` (package `com.dmarket.p2p.tracker.adapter.notary`) loads `provenance_client_cffi`, then
`provenance_jni`, and declares the ABI as `external fun`s over `Long` handles: context, cancel, stream, MPC and
proxy issuance with a deadline and progress, response and headers, material export/import, presentation and
`wake`. Every owned buffer is copied into a Java array and freed with `pc_buffer_free` before the call
returns; a failing call throws `NativeProverException` with a `NativeProverStatus`. Issuance blocks and throws
`IllegalStateException` on the main thread. `consumer-rules.pro` keeps the names JNI binds.

Streams follow the README's transport contract without entering the JVM from a callback. Each stream's C
userdata holds a bounded read ring and write ring (64 KiB by default) and one pending token each for read,
write, flush and close; the five callbacks only copy between the rings and the borrowed buffers, park a token
when they must wait, and wake an earlier token when a retry succeeds. `NativeProverStream` runs two daemon
threads over a host `NativeProverTransport` (blocking `read`, `write`, `shutdownOutput`, `close`): the reader
fills the read ring, the writer drains the write ring to the connection. Both call into C, which calls `pc_wake`
outside its lock. Flush answers OK only once the write ring is written out; close shuts the write side after
that. The prover's release (end of issuance, cancellation, timeout, failure, or freeing an unused stream) stops
both threads and closes the transport; the ring memory is freed when both the prover and the worker have let
go. The transports and the `NotaryProver` over this are in `:core` (next section).

### The native `NotaryProver`

`NativeNotaryProver` (commonMain, `id = "tlsn"`) holds every rule of a mobile proof; the platform gives only a
`NativeProverEngine` (connect, issue, present, report what the connections saw). Per proof, in order:

1. a kind outside `NotaryConfig.enabledReads`, `transport = "egress"` (no Worker egress natively) or an engine
   without its library (`unavailableReason()`: no `.so` for the device's ABI, a `LinkageError` caught once) end at
   once as `ProofFailure.NotStarted`;
2. at most `NotaryConfig.maxConcurrency` proofs run (`Semaphore`, one per process); a further one waits;
3. holding the permit, a Steam token with less than 30 min left ends as `NotStarted` with
   `PROOF_TOKEN_EXPIRING_REASON`, as `HostedNotaryProver` does for headless;
4. `prepareProveRequest` (shared with `WasmNotaryProver`) gets the DMarket token for the notary
   (`MarketplaceNotaryTokenProvider`), the read's spec and the send budget; a failure there is `NotStarted`;
5. the request becomes the C ABI's JSON in common code (`nativeConfigJson`: `abi_version` 1, `server_name`, the
   request, `mpc` or `proxy` limits, `roots_der_hex` from `rootStorePem`; `nativePolicyJson`). The policy is
   always passed: the ABI's default reveals the request target, which carries the Steam token on both trade axes;
6. the engine issues within 180 s (the extension's `NOTARY_PROOF_TIMEOUT_MS`): past it the coroutine cancels the
   issuance and the proof is `ProverKilled(SESSION_DEADLINE)`, as is the ABI's own `PC_TIMEOUT`;
7. the outcome goes through `ProofFailures.classify` (refused upgrade with its status, unreachable notary, version
   mismatch, notary close code, Steam dial failure or non-2xx response, limits), so the summary keeps the prover's
   text and the online-budget lesson still reads it.

`cancel(dealId)` ends a running proof as `ProofFailure.Other(PROOF_CANCELLED_REASON)`: a failure, so the loop
reports the transition unproven and retries later. A cancelled caller cancels the issuance and propagates.
`progress` (`StateFlow<Map<DealId, ProofProgress>>`: stage 0–6, name, fraction, bytes up/down while it runs) and
`traffic` (bytes since the prover was built) are for the progress screen and the data counter.

`OkHttpNativeProverEngine` (androidMain) runs each proof on its own context, cancel handle and worker thread. A
coroutine cancel closes the connections and calls `NativeProverBridge.cancel`, then waits for the worker to free
every handle.

- **Notary** (`OkHttpNotaryTransport`): a WebSocket on the host's `OkHttpClient` (`AndroidTracker.install`'s
  `hostOkHttp`, else a plain one; its read timeout lifted), offering `Sec-WebSocket-Protocol: tlsn.notary.v2,
  bearer.<DMarket token>` (CLIENT_CONTRACT §2). The `101` is awaited before the stream exists, so a 400/401/503
  keeps its status and no answer reads as unreachable. Binary frames go into the stream's bounded rings; `write`
  returns only once OkHttp's send queue is empty, so the stream's flush means "on the socket" (OkHttp's queue would
  also fail the socket above 16 MiB of a ~40–60 MB MPC upload). EOF is only the notary's close: the phase-two
  attestation runs on the same socket. `shutdownOutput` sends the close frame and keeps reading; the release
  cancels the socket.
- **Target** (`LazyTcpTransport`, MPC only): TCP to `serverName:443` through the client's socket factory,
  `TCP_NODELAY`, dialled on the first write like the web's `lazyTargetChannel` (MPC setup outlasts Steam's idle
  timeout). Proxy mode opens the notary socket only; the notary dials Steam.

The mode is `NotaryConfig.mode` (`"mpc"` default, `"proxy"`), as on the web. Prover and notary must be on one pin
(CLIENT_CONTRACT §0). The proxy route is off in the notary's shipped config (`configs/notary.toml`: `proxy = false`);
steam-provenance's `deployments/proxy-rollout.md` describes a separate dev-02 proxy candidate pool with a
placeholder image, and nothing for stage-01. So a phone's first proof runs MPC.

It is wired but off: `AndroidTracker.install(nativeProofs = true)` (`MobileTracker(proofEngine = …)`) makes the
mobile loop prove through it; `AndroidTracker.prover` exposes it.

Host tests cover the common prover with a fake engine (`NativeNotaryProverTest`), the JSON (`NativeProverJsonTest`)
and both transports against local servers (`NotaryTransportsTest`). A live proof needs a device and a notary.

### Proofs kept on the device (D2)

After an end-of-deal proof reaches `/notary` (sites `offer_declined`, `offer_cancelled`, `trade_completed`,
`trade_reversed`), the loop keeps its payload (`ProofArchiveStore`, one key of the account store) with the cycle's
time as the deal's final-status moment: the proof is the read of that status, so its first send is when the device
saw it (a retried proof starts later by its retry delay). A later final status of the same deal (a reversal after
completion) moves the expiry. Kept 30 days from that moment, pruned by expiry before each transition proof pass.
Logout and a Steam account switch erase the account store; an unlink (`linkedAccount()` answers `null`) erases the
proofs; `clearStoredProofs()` on `P2pTracker`, `MobileTracker` and `AndroidTracker` erases them on demand. The device
never hands them out: dispute evidence is the backend's. A presentation is a few KB plus the revealed bytes, so 30
days of deals stay well inside the prefs file. Web and headless keep nothing.

### Testing on a device

Host tests cannot load an Android `.so`. `prover-android/src/androidTest` runs on an emulator or device:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew :prover-android:connectedDebugAndroidTest
```

It loads both libraries, creates and frees handles, releases an unused stream, holds a proxy issuance on a
pending read over an in-memory loopback until its deadline (`TIMEOUT`), wakes it with served bytes (it then
fails on them well before its deadline), cancels one, checks the main-thread refusal, and reads malformed
material as `CODEC`. It does not present real material: the package has no stored material to restore offline
(steam-provenance's `release/tests/native-fixture.mjs` writes placeholder bytes for packaging tests, and its
`client-cffi` tests issue material against a live local notary), so a positive `pc_present` needs a fixture
saved from such a run.

## Bumping the prover

1. Pick the release: `npm view @dmarket/steam-provenance dist-tags` to find it, then
   `npm view @dmarket/steam-provenance@<version> gitHead dist.integrity`. A `SNAPSHOT` release builds only
   while `VERSION_NAME` ends in `-SNAPSHOT` (check 5).
2. **Check `gitHead` against the notary.** It must equal the `GIT_SHA` of the notary build the target
   environment runs. The prover and the notary must agree on the `dep/tlsn` pin, and a mismatch surfaces as
   an **opaque MPC/deserialization failure**, not a version error. Correlate by commit only — never by the
   npm version and never by `DOCKER_VERSION`, whose build numbers differ for the same commit.
3. Set `TlsnProverPin.VERSION` and `TlsnProverPin.INTEGRITY` in
   [`build-logic/src/main/kotlin/TlsnProver.kt`](../build-logic/src/main/kotlin/TlsnProver.kt), together.
   Both npm packages ship this prover, so the bump changes `@dmarket/p2p-tracker-core` and
   `@dmarket/p2p-tracker-headless` alike.
4. `./gradlew kotlinUpgradeYarnLock` — refreshes your local `kotlin-js-store/yarn.lock`. It is not tracked,
   but when it exists the Kotlin/JS lockfile check fails the build until it matches the new dependency.
   If `verifyTlsnProver` then reports two package entries in `build/js/yarn.lock`, some generated
   `package.json` still names the old version (their tasks can stay up to date across the bump): run
   `./gradlew jsPackageJson jsTestPackageJson --rerun kotlinUpgradeYarnLock` once.
5. `./gradlew check :core:jsBrowserProductionLibraryDistribution :headless-js:jsNodeProductionLibraryDistribution`
   — the checks above run here; confirm the logged `GIT_SHA` is the one from step 2.
6. Re-check the package's `pkg/client_wasm.d.ts` against what `WasmProverModule.kt` sends. The
   `RevealPolicy` fields in particular are required-not-defaulted, so an added field is a silent runtime
   break rather than a compile error; `WasmRevealPolicyContractTest` asks the real wasm, but only about
   the fields it knows.
7. Update every pinned value in [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md): the provenance
   table, the `SHA-256 (pkg/client_wasm_bg.wasm)` line and the `npm pack` command under *Statically linked
   dependencies*. Then `git grep -n -e '<old version>' -e '<old GIT_SHA>' -e '<old WASM_SHA256>'` must find
   nothing outside `CHANGELOG.md` history.
8. Copy `native/android/THIRD_PARTY_NOTICES.md` and `RUST_STDLIB_LICENSE.html` of the new package over
   [`../licenses/steam-provenance-android/`](../licenses/steam-provenance-android/) (`verifyProverNativeLibs`
   fails until they match) and update the `gitHead` in the Android section of `THIRD_PARTY_NOTICES.md`.
   `./gradlew :prover-android:assembleRelease` must pass, and the device test (below) should.
9. Add a `CHANGELOG.md` entry naming the new `GIT_SHA`.

## License

`Apache-2.0 OR MIT` for TLSNotary — texts in [`../licenses/tlsn/`](../licenses/tlsn/), notice in
[`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md). The complete per-crate notices for everything
the `.wasm` links statically ship inside `@dmarket/steam-provenance` as its `THIRD_PARTY_NOTICES.md`.
