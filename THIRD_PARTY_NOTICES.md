# Third-party notices

This project is licensed under the [MIT License](LICENSE). It additionally **vendors and
redistributes** a prebuilt third-party artifact that is licensed separately from this repository.
That artifact, its provenance, and its license are listed below.

> Unlike most vendored assets, this one **is** redistributed: it lives in the source tree under
> [`vendor/tlsn/`](vendor/tlsn/) **and** is published inside the npm package
> (`@dmarket/p2p-tracker-core`), because the library loads it at runtime by relative path. Installing
> the package therefore also installs the artifact below.

---

## TLSN prover WASM — `vendor/tlsn/`

Prebuilt WebAssembly prover, plus its TypeScript WebSocket transport and prover façade, used by the
optional TLSNotary proof path.

| Directory | Upstream name | Contents |
|---|---|---|
| [`vendor/tlsn/pkg/`](vendor/tlsn/pkg/) | `client-wasm` | `wasm-pack` output: `client_wasm_bg.wasm` (~10 MB) + JS/TS glue + the rayon worker snippets |
| [`vendor/tlsn/transport/`](vendor/tlsn/transport/) | `client-wasm-transport` | compiled WebSocket transport + `createProver` façade (`tsc` output) |

**Provenance.** Produced by the `dmarket/steam-provenance` project, which embeds
[TLSNotary](https://github.com/tlsnotary/tlsn) (`dep/tlsn`). These bytes were **not** pulled from a
package registry: they are a CI artifact of that repository's `build_wasm_client` job, built from
`main` with the toolchain it pins. The prover must agree with the deployed notary, which is built from
the same branch.

| Field | Value |
|---|---|
| Artifact | `client-wasm-steam-provenance-main-99e090a-728.tgz` |
| `GIT_SHA` | `99e090a347a235ccaa5d1bc790d69a55283cca44` (`main`) |
| `BUILD_NUM` / `BUILD_URL` | `728` / `https://circleci.com/gh/dmarket/steam-provenance/728` |
| `TLSN_SUBMODULE_SHA` | `unknown` — the CI packaging does not record it; read the pinned `dep/tlsn` revision from that build |
| `CLIENT_WASM_VERSION` / `TRANSPORT_VERSION` | `0.1.0` / `0.2.0` |
| Notary subprotocol | `tlsn.notary.v2` |

The full manifest ships alongside them in [`vendor/tlsn/VERSION`](vendor/tlsn/VERSION), and the
artifact's own documentation in [`vendor/tlsn/README.md`](vendor/tlsn/README.md).

**Integrity.** Every file carries a digest in [`vendor/tlsn/SHA256SUMS`](vendor/tlsn/SHA256SUMS),
which must verify on a clean checkout:

```sh
cd vendor/tlsn && shasum -a 256 -c SHA256SUMS
```

The WASM binary itself:

```
SHA-256 (pkg/client_wasm_bg.wasm) = 2b8f09b69d7b7f9638381aebf4105e20d189a52092430877005ad31b5e390a35
```

The same value is stated in [`vendor/tlsn/SHA256SUMS`](vendor/tlsn/SHA256SUMS) and as `WASM_SHA256` in
[`VERSION`](vendor/tlsn/VERSION). All three must agree; if they do not, trust none of them.

**What that check does and does not prove.** A prebuilt binary is not auditable by reading the diff,
and `shasum -c` compares the vendored tree against a manifest committed beside it — it catches
corruption in transit or in the tree, not substitution by anyone who can commit both. Establishing
that these bytes are what the pinned upstream revision compiles to still requires a reproducible
`wasm-pack` rebuild from that revision, checked in CI. That check does not exist yet, and it is the
gap that matters here: a tampered artifact would run MPC code with access to the device Steam access
token and the notary bearer token.

**License — `Apache-2.0 OR MIT`.** The upstream TLSNotary project declares that all of its crates are
licensed under **either** the Apache License, Version 2.0 **or** the MIT license, at your option. The
corresponding license texts are reproduced here:

- [`licenses/tlsn/LICENSE-APACHE`](licenses/tlsn/LICENSE-APACHE)
- [`licenses/tlsn/LICENSE-MIT`](licenses/tlsn/LICENSE-MIT)

**Statically linked dependencies.** `client_wasm_bg.wasm` is a compiled Rust artifact that statically
links additional third-party crates beyond TLSNotary (its full transitive dependency tree). Those
dependencies are overwhelmingly permissively licensed (MIT / Apache-2.0), but this file does not
enumerate them individually; the authoritative, complete license manifest is the `Cargo.lock` of the
`dmarket/steam-provenance` build that produced the binary. Rebuild the WASM from that source to audit
the exact dependency set for a given revision.
