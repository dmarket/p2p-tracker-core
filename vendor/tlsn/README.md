# client-wasm — browser-extension prover artifact

This tarball is built and published by the **steam-provenance** CI pipeline (job
`build_wasm_client`). It is the TLSNotary **prover** for the browser extension:
a multi-threaded wasm module plus a TypeScript WebSocket transport, built against
a specific `dep/tlsn` pin and a specific notary contract.

It replaces hand-vendoring a locally built `pkg/`. If you are reading this inside
a repo that also has a checked-in `pkg/` built on somebody's laptop, that copy is
what this artifact exists to retire.

> This file ships **inside** the tarball, so it always describes the bytes next to
> it. In the source repo it lives at `client-wasm/ARTIFACT_README.md`.

## Contents

```
client-wasm-<DOCKER_VERSION>/
├── pkg/            wasm-pack output — STAYS LOOSE at runtime, do NOT inline
│   ├── client_wasm.js          JS glue (the module you import)
│   ├── client_wasm_bg.wasm     ~10 MB, multi-threaded
│   ├── client_wasm.d.ts        TypeScript surface: initialize/prove/present + IoChannel
│   ├── client_wasm_bg.wasm.d.ts
│   ├── spawn.js                rayon worker entry (import-patched copy)
│   ├── snippets/               web-spawn glue, also patched
│   ├── package.json
│   └── README.md               the crate's own README
├── transport/      WebSocket transport + prover façade — MEANT to be inlined
│   ├── dist/                   *.js, *.d.ts and source maps
│   ├── package.json            client-wasm-transport
│   └── README.md
├── VERSION         provenance manifest, shell-sourceable
├── SHA256SUMS      over every file above
└── README.md       this file
```

`pkg/` deliberately carries **no `.gitignore`**. `wasm-pack` writes one containing
a single `*`; it is stripped at packaging time because this directory is meant to
be vendored into a git repo, and a `*` ignore file inside a vendored directory
makes git silently ignore every file in it — the blob looks committed and isn't.

## Download

Published as three separate artifacts, so integrity and provenance are readable
without pulling ~2.6 MB:

| Artifact | Purpose |
| --- | --- |
| `client-wasm-<DOCKER_VERSION>.tgz` | the payload |
| `client-wasm-<DOCKER_VERSION>.tgz.sha256` | transfer integrity |
| `VERSION` | provenance, byte-identical to the copy inside |

**Prerequisite:** a CircleCI API token with read access to
`dmarket/steam-provenance`, available in your CI as `$CIRCLE_TOKEN`.

### Latest successful build on a branch

There is **no mutable "latest" path** in CircleCI artifact storage — nothing to
overwrite, so `latest` is not a stored alias. It is a *resolution step you perform
at download time*:

```bash
BASE=https://circleci.com/api/v1.1/project/github/dmarket/steam-provenance

# Resolve, then download preserving the published filename (see the note below).
URL=$(curl -sS -H "Circle-Token: $CIRCLE_TOKEN" \
  "$BASE/latest/artifacts?branch=main&filter=successful" \
  | jq -r '.[] | select(.path | endswith(".tgz")) | .url')

curl -sSL -H "Circle-Token: $CIRCLE_TOKEN" -O "$URL"
curl -sSL -H "Circle-Token: $CIRCLE_TOKEN" -O "$URL.sha256"
```

`filter=successful` filters on **job** status. Because `build_wasm_client` gates
`build_images`, the latest successful one is the build the deployed notary was cut
alongside.

> **Keep the published filename.** `-O`, not `-o client-wasm.tgz`. The `.sha256`
> file names the tarball as published, so `sha256sum -c` fails on a renamed file
> with a confusing "No such file or directory".

API v1.1 is long-deprecated but has no v2 equivalent for "latest". If your org has
it disabled, walk v2 in three calls instead:

```bash
# 1. newest pipeline on the branch -> its workflow -> the job's number
GET /api/v2/project/gh/dmarket/steam-provenance/pipeline?branch=main
GET /api/v2/workflow/{workflow_id}/job          # find name == build_wasm_client
GET /api/v2/project/gh/dmarket/steam-provenance/{job_number}/artifacts
```

To pin an exact commit rather than tracking a branch, start the same walk from the
pipeline for that SHA.

> **Retention is finite** (org-configurable; CircleCI's default is 30 days). You
> can pin reproducibly only for as long as retention lasts. Long-term pinning
> needs a durable store — raise it with the steam-provenance maintainers rather
> than working around it.

### Verify, then unpack

```bash
sha256sum -c client-wasm-*.tgz.sha256
tar -xzf client-wasm-*.tgz
( cd client-wasm-*/ && sha256sum -c SHA256SUMS )
```

## Bundling — the part that is easy to get silently wrong

The two directories have **opposite** roles. Getting this backwards produces a
green build and a dead prover, with no error at either end.

### `pkg/` — copy out loose, mark external

```
esbuild … --external:./pkg/*
```

It must **not** be inlined. Its rayon spawner does
`new Worker(new URL("./spawn.js", import.meta.url))` and fetches the `.wasm` via
`import.meta.url`, and its worker threads load the JS glue **by relative path**.
Inlining rewrites exactly those paths. Copy `pkg/` next to the bundle (the
extension vendors it at `vendor/pkg/`) and keep the single
`import * as wasm from "./pkg/client_wasm.js"` in your own code.

### `transport/dist/` — inline it

Safe to inline *because* it holds no runtime edge to `pkg/`: the façade is
injection-based — `createProver(wasm)` **takes the wasm module namespace as an
argument**. Every module specifier in `dist/` is relative; the build asserts that
on every push (guard G4), so it cannot silently regress.

### You do **not** need `--alias:client-wasm=./pkg/client_wasm.js`

That flag was the mitigation for a *rejected* literal-pass-through façade, which
emitted a bare `client-wasm` specifier that `--external:./pkg/*` cannot match.
The shipped façade emits no such specifier. Adding the alias back is cargo-cult;
if you find yourself reaching for it, something else is wrong.

### The embedding page must be cross-origin isolated

The module is multi-threaded and uses `SharedArrayBuffer`, so the page needs:

```
Cross-Origin-Opener-Policy: same-origin
Cross-Origin-Embedder-Policy: require-corp
```

Without both, `initialize()` fails to bring up its thread pool.

## `VERSION`

```bash
source VERSION && echo "$GIT_SHA / $TLSN_SUBMODULE_SHA"
```

| Field | Notes |
| --- | --- |
| `DOCKER_VERSION` | the artifact's tag |
| `GIT_SHA`, `GIT_BRANCH` | **the correlation key** — see below |
| `BUILD_NUM`, `BUILD_URL` | the CircleCI job that produced it |
| `CLIENT_WASM_VERSION`, `TRANSPORT_VERSION` | crate / npm package versions |
| `TLSN_SUBMODULE_SHA` | the `dep/tlsn` pin the prover was built against |
| `RUSTC_VERSION`, `WASM_PACK_VERSION` | the pinned nightly and packager |
| `WASM_SHA256` | identity of the `.wasm` itself |

**Correlate an artifact to a notary image by `GIT_SHA`, never by
`DOCKER_VERSION`.** Both strings end in a CircleCI build number, and they are
*different* build numbers for the same commit: `build_wasm_client` runs before
`build_images` (it gates it), so it cannot know the image's number.

`TLSN_SUBMODULE_SHA` is the field to check first when debugging an MPC failure
that looks like corruption. TLSNotary is pre-1.0 and ships regular breaking
changes; a prover built against one pin talking to a notary built against another
fails with **opaque MPC/deserialization errors**, not a clean version mismatch. If
that sum doesn't match the notary's, stop debugging and re-sync the pin.
