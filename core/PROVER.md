# The TLSN prover: where it comes from, how it ships, how it loads

The optional proof path runs a prebuilt WebAssembly prover from the `dmarket/steam-provenance` project.
This file is the integration contract on our side; the prover's own consumer contract is the `README.md`
inside its npm package.

## Where it comes from

One exact release of the npm package
[`@dmarket/steam-provenance`](https://www.npmjs.com/package/@dmarket/steam-provenance), declared as a
Kotlin/JS dependency of `:core`'s `jsMain` in [`build.gradle.kts`](build.gradle.kts). Two values pin it,
and they live next to each other at the top of that file:

| Value | What it pins |
|---|---|
| `tlsnProverVersion` | the exact npm version, never a range or a dist-tag (`^0.1.0-SNAPSHOT.30` would also match every later snapshot) |
| `tlsnProverIntegrity` | the registry's `dist.integrity` for that version: a sha512 over the whole npm tarball |

The package is installed, never imported. No Kotlin code names it, so no bundler, ours or a host's, ever
sees it; `core/npm/prepare-package.sh` blanks `dependencies` in our published `package.json`, so
installing `@dmarket/p2p-tracker-core` does not install a second copy.

The `copyTlsnProver` task takes exactly three paths out of the installed package and puts them at the
root of our JS distribution, and therefore of our npm tarball:

```
pkg/            wasm-pack output — the prover. STAYS LOOSE at runtime, never inlined.
transport/      WebSocket transport + `createProver` façade (dist/ + its package.json and README.md).
VERSION         provenance manifest (GIT_SHA, WASM_SHA256, …), shell-sourceable
```

Everything else in the package stays behind. Its `package.json`, `README.md`, `CHANGELOG.md`, `LICENSE`
and `THIRD_PARTY_NOTICES.md` would collide with ours or describe the wrong package, and `SHA256SUMS` also
covers that `README.md`, so a `-c` run over our copy would report a spurious miss.

Earlier releases took the prover from a copy committed to this repository under `vendor/tlsn/`,
because the only channel then was a CircleCI artifact and its retention is finite. npm versions are
permanent, so that reason is gone, and the committed copy only proved itself against a manifest
committed beside it.

## What the build checks

`verifyTlsnProver` checks the installed package and fails the build on any miss. CI runs no separate step.
Nothing reads the package before this task has passed: `copyTlsnProver` and every JS test task of `:core`
depend on it. The tests matter as much as the copy, because `WasmRevealPolicyContractTest` loads and runs
the real `pkg/`, and CI runs `check` before the distribution task. Each run logs the result:

```
TLSN prover @dmarket/steam-provenance@<version> verified (<n> files): GIT_SHA=… WASM_SHA256=… TRANSPORT_VERSION=…
```

1. **Integrity.** The lockfile entry the npm install used must carry `tlsnProverIntegrity`. yarn checks
   the download against the registry's integrity and records it there, so this ties the whole npm tarball
   to a value committed in this repository.
2. **Version and commit.** The installed `package.json` must be `tlsnProverVersion`, and its `gitHead`
   must equal `GIT_SHA` in `VERSION`.
3. **Checksums.** The files we ship must be exactly the `pkg/`, `transport/` and `VERSION` entries of the
   package's `SHA256SUMS`, and each must match it. A shipped file the manifest does not list fails, and so
   does a listed file that is missing (a missing `spawn.js` snippet would otherwise build green and fail
   only when `initialize()` starts its workers).
4. **The binary.** `WASM_SHA256` in `VERSION` must be the digest of the `.wasm` actually installed.

Checks 2–4 compare the package with itself and catch a damaged or half-replaced install; only check 1
compares it with this repository. None of them proves the `.wasm` is what the pinned upstream revision
compiles to; that needs a reproducible rebuild, which does not exist yet (see
[`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md)).

The integrity pin lives in the build script rather than in a committed lockfile because `kotlin-js-store/`
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
builds the socket, the WebSocket and the compiled module and hands them in. The prover's threads are the other
half of a Node host and are entirely its own: `pkg/`'s spawner starts browser `Worker`s, which Node does not
have.

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

## Bumping the prover

1. Pick the release: `npm view @dmarket/steam-provenance dist-tags` to find it, then
   `npm view @dmarket/steam-provenance@<version> gitHead dist.integrity`.
2. **Check `gitHead` against the notary.** It must equal the `GIT_SHA` of the notary build the target
   environment runs. The prover and the notary must agree on the `dep/tlsn` pin, and a mismatch surfaces as
   an **opaque MPC/deserialization failure**, not a version error. Correlate by commit only — never by the
   npm version and never by `DOCKER_VERSION`, whose build numbers differ for the same commit.
3. Set `tlsnProverVersion` and `tlsnProverIntegrity` in [`build.gradle.kts`](build.gradle.kts), together.
4. `./gradlew kotlinUpgradeYarnLock` — refreshes your local `kotlin-js-store/yarn.lock`. It is not tracked,
   but when it exists the Kotlin/JS lockfile check fails the build until it matches the new dependency.
5. `./gradlew check :core:jsBrowserProductionLibraryDistribution` — the checks above run here; confirm the
   logged `GIT_SHA` is the one from step 2.
6. Re-check the package's `pkg/client_wasm.d.ts` against what `WasmProverModule.kt` sends. The
   `RevealPolicy` fields in particular are required-not-defaulted, so an added field is a silent runtime
   break rather than a compile error; `WasmRevealPolicyContractTest` asks the real wasm, but only about
   the fields it knows.
7. Update every pinned value in [`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md): the provenance
   table, the `SHA-256 (pkg/client_wasm_bg.wasm)` line and the `npm pack` command under *Statically linked
   dependencies*. Then `git grep -n -e '<old version>' -e '<old GIT_SHA>' -e '<old WASM_SHA256>'` must find
   nothing outside `CHANGELOG.md` history.
8. Add a `CHANGELOG.md` entry naming the new `GIT_SHA`.

## License

`Apache-2.0 OR MIT` for TLSNotary — texts in [`../licenses/tlsn/`](../licenses/tlsn/), notice in
[`../THIRD_PARTY_NOTICES.md`](../THIRD_PARTY_NOTICES.md). The complete per-crate notices for everything
the `.wasm` links statically ship inside `@dmarket/steam-provenance` as its `THIRD_PARTY_NOTICES.md`.
