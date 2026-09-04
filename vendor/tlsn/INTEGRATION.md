# How this library uses the vendored prover

[`README.md`](README.md) in this directory is the artifact's **own** documentation — it ships inside
the tarball, describes the bytes next to it, and is covered by [`SHA256SUMS`](SHA256SUMS). Do not edit
it. This file is ours.

## What is committed here and why

The whole published artifact, unpacked verbatim:

```
vendor/tlsn/
├── pkg/            wasm-pack output — the prover. STAYS LOOSE at runtime, never inlined.
├── transport/dist/ WebSocket transport + `createProver` façade. Safe to inline.
├── VERSION         provenance manifest (GIT_SHA, WASM_SHA256, …), shell-sourceable
├── SHA256SUMS      covers every file above
└── README.md       the artifact's own docs
```

It is committed rather than downloaded per build because **CircleCI artifact retention is finite**
(org-configurable, default 30 days). A build that resolves the prover at CI time stops being
reproducible the moment retention lapses; a committed, digest-verified copy does not.

`:core` loads it at runtime through **relative** specifiers — `./pkg/client_wasm.js` and
`./transport/dist/index.js` — resolved next to the emitted `.mjs`. Both directories are copied into
the JS distribution by the `copyTlsnProver` Gradle task, so they are present in the npm package and a
consuming host writes no import of its own.

## Verify

```bash
cd vendor/tlsn && shasum -a 256 -c SHA256SUMS
```

This is the audit anchor: it must pass on a clean checkout. `VERSION` records which upstream build
these bytes came from — correlate a prover to a notary deployment by `GIT_SHA`, never by
`DOCKER_VERSION` (their build numbers differ for the same commit).

## Bundling — the part that is easy to get silently wrong

The two directories have **opposite** roles. Getting this backwards produces a green build and a dead
prover, with no error at either end.

- **`pkg/` must stay loose and external.** Its rayon spawner does
  `new Worker(new URL("./spawn.js", import.meta.url))` and fetches the `.wasm` through
  `import.meta.url`. Inlining rewrites exactly those paths. Mark it `--external:./pkg/*` and copy the
  directory next to your bundle.
- **`transport/dist/` is meant to be inlined.** It holds no runtime edge to `pkg/` — the façade takes
  the wasm module namespace as an argument — and every specifier inside it is relative.

You do **not** need `--alias:client-wasm=…`. That was the mitigation for a rejected design; nothing
here emits a bare specifier.

The embedding page must be cross-origin isolated (COOP `same-origin` + COEP `require-corp`) for the
multi-threaded build. `NotaryConfig.threadCount = 1` avoids that requirement.

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
`revealResponseBody`. Raise it against the artifact pinned in [`VERSION`](VERSION); when it lands, wire
it through `revealPolicy()` in `WasmProverModule.kt` and drop the acknowledgement flag.

## Refreshing to a newer prover

1. Download the new artifact and verify it (`sha256sum -c` the detached `.sha256`, then `SHA256SUMS`
   inside). The recipe is in [`README.md`](README.md).
2. Replace this directory wholesale — `pkg/`, `transport/`, `VERSION`, `SHA256SUMS`, `README.md`.
   Never mix halves from different builds: the prover and the notary must agree on the `dep/tlsn` pin,
   and a mismatch surfaces as an **opaque MPC/deserialization failure**, not a clean version error.
3. Re-run `shasum -a 256 -c SHA256SUMS` here, then update the digest and `GIT_SHA` recorded in
   [`../../THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md).
4. Re-check the generated `pkg/client_wasm.d.ts` against what `WasmProverModule.kt` sends. The
   `RevealPolicy` fields in particular are required-not-defaulted, so an added field is a silent
   runtime break rather than a compile error.

## License

`Apache-2.0 OR MIT` — texts in [`../../licenses/tlsn/`](../../licenses/tlsn/), full notice in
[`../../THIRD_PARTY_NOTICES.md`](../../THIRD_PARTY_NOTICES.md).
