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
package registry. Unlike every earlier refresh, they are **not** a CI artifact either: this one was
built locally (`make wasm-artifact`) from a clean checkout of `main`, with the toolchain that
repository pins, because the CI artifact then in use had been built from an unmerged branch that had
diverged from `main` — and the prover must agree with the deployed notary, which is built from `main`.
`BUILD_NUM` / `BUILD_URL` therefore read `local`, and `DOCKER_VERSION` carries the `dev-` prefix the
packaging script uses off-CI so a manual build can never be mistaken for a pipeline one.

| Field | Value |
|---|---|
| Artifact | `client-wasm-dev-99e090a.tgz` |
| `GIT_SHA` | `99e090a347a235ccaa5d1bc790d69a55283cca44` (`main`) |
| `TLSN_SUBMODULE_SHA` | `8bb356ad3a657096cbfad50baa46ec28c7ce2d62` |
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
SHA-256 (pkg/client_wasm_bg.wasm) = b51b8ec6994d95bb01b18efe0822ee2c215f3ce688716766cdb8cc403a54443e
```

A prebuilt binary is not auditable by reading the diff. Before the notary path is enabled for
production, this digest should be checked in CI against a reproducible `wasm-pack`
rebuild from the pinned upstream revision — a tampered artifact would run MPC code with access to the
device `steamLoginSecure` cookie and the notary bearer token.

That check is now within reach, and this refresh is a step towards it rather than the check itself:
`RUSTC_VERSION` in [`VERSION`](vendor/tlsn/VERSION) came out byte-identical to the CI artifact's
(`rustc 1.98.0-nightly (13f1859f2 2026-06-27)`), which is what the upstream toolchain pin exists to
guarantee. It does **not** establish reproducibility, because this build is of a *different* commit
than the artifact it replaces — proving that would take rebuilding one same commit twice.

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
