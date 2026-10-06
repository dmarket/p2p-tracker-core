# Third-party notices

This project is licensed under the [MIT License](LICENSE). Its npm package additionally
**redistributes** a prebuilt third-party artifact that is licensed separately from this repository.
That artifact, its provenance, and its license are listed below.

> The artifact is **not** in this source tree. The build takes it from an exact-pinned npm release of
> [`@dmarket/steam-provenance`](https://www.npmjs.com/package/@dmarket/steam-provenance) and copies it
> into the published package (`@dmarket/p2p-tracker-core`), because the library loads it at runtime from
> the host extension's root. Installing the package therefore also installs the artifact below.

---

## TLSN prover WASM — from `@dmarket/steam-provenance`

Prebuilt WebAssembly prover, plus its TypeScript WebSocket transport and prover façade, used by the
optional TLSNotary proof path.

| Path in our package | Upstream name | Contents |
|---|---|---|
| `pkg/` | `client-wasm` | `wasm-pack` output: `client_wasm_bg.wasm` (~10 MB) + JS/TS glue + the rayon worker snippets |
| `transport/` | `client-wasm-transport` | compiled WebSocket transport + `createProver` façade (`tsc` output) |
| `VERSION` | — | the upstream provenance manifest, unchanged |

**Provenance.** Produced by the `dmarket/steam-provenance` project, which embeds
[TLSNotary](https://github.com/tlsnotary/tlsn) (`dep/tlsn`). The npm package is not a second build: its
`pkg/`, `transport/` and `VERSION` are byte-for-byte the CI artifact of that repository's
`build_wasm_client` job for the commit named below, built with the toolchain it pins. The prover must
agree with the deployed notary, so correlate the two by `GIT_SHA`.

| Field | Value |
|---|---|
| npm package | `@dmarket/steam-provenance@0.1.0-SNAPSHOT.30` (pinned in [`core/build.gradle.kts`](core/build.gradle.kts)) |
| `GIT_SHA` / npm `gitHead` | `ac583a490dda20768381beb2b8a9f4a23b0e19fc` |
| `BUILD_NUM` / `BUILD_URL` | `933` / `https://circleci.com/gh/dmarket/steam-provenance/933` |
| `TLSN_SUBMODULE_SHA` | `unknown` — the CI packaging does not record it; read the pinned `dep/tlsn` revision from that commit |
| `CLIENT_WASM_VERSION` / `TRANSPORT_VERSION` | `0.1.0` / `0.2.0` |
| Notary subprotocol | `tlsn.notary.v2` |

The full manifest ships in our package as `VERSION`, and the artifact's own documentation in the
`@dmarket/steam-provenance` package as its `README.md`.

**Integrity.** The pin is the npm version plus its registry `dist.integrity`, committed in
[`core/build.gradle.kts`](core/build.gradle.kts). Every build checks, before it copies a byte, that the
installed tarball carries that integrity, that every shipped file matches the package's `SHA256SUMS`,
and that `VERSION`'s `WASM_SHA256` is the binary actually installed; a miss fails the build
([`core/PROVER.md`](core/PROVER.md)). To check an installed copy by hand:

```sh
cd build/js/node_modules/@dmarket/steam-provenance && shasum -a 256 -c SHA256SUMS
```

The WASM binary itself:

```
SHA-256 (pkg/client_wasm_bg.wasm) = a2752694bfd72a7bf6636cea247e2d67e623d7066d69cf9d8325aaef764e12e2
```

The same value is stated in the package's `SHA256SUMS` and as `WASM_SHA256` in `VERSION`. All three must
agree; if they do not, trust none of them.

**What those checks do and do not prove.** A prebuilt binary is not auditable by reading the diff. The
integrity pin proves the bytes are the ones published to npm under that version when it was pinned, and
`SHA256SUMS` proves the package is internally consistent — neither proves the bytes are what the pinned
upstream revision compiles to. That still requires a reproducible `wasm-pack` rebuild from that revision,
checked in CI. That check does not exist yet, and it is the gap that matters here: a tampered artifact
would run MPC code with access to the device Steam access token and the notary bearer token.

**License — `Apache-2.0 OR MIT`.** The upstream TLSNotary project declares that all of its crates are
licensed under **either** the Apache License, Version 2.0 **or** the MIT license, at your option. The
corresponding license texts are reproduced here:

- [`licenses/tlsn/LICENSE-APACHE`](licenses/tlsn/LICENSE-APACHE)
- [`licenses/tlsn/LICENSE-MIT`](licenses/tlsn/LICENSE-MIT)

**Statically linked dependencies.** `client_wasm_bg.wasm` is a compiled Rust artifact that statically
links additional third-party crates beyond TLSNotary (its full transitive dependency tree). This file
does not enumerate them. The `@dmarket/steam-provenance` package does: its `THIRD_PARTY_NOTICES.md` is
generated from the crate graph the `.wasm` is compiled from and reproduces each crate's license notice.
Read it from the pinned version (`npm pack @dmarket/steam-provenance@0.1.0-SNAPSHOT.30`).
