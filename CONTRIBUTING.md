# Contributing

Thanks for your interest in improving the DMarket P2P Trade-Tracker Core. This document covers how to
build the project, the conventions we follow, and how changes get released.

By participating you agree to abide by our [Code of Conduct](CODE_OF_CONDUCT.md). For security issues,
**do not open a public issue** — follow the [Security Policy](SECURITY.md) instead.

## Prerequisites

- **JDK 17–21** to run Gradle. Newer JDKs (e.g. 25) are not yet supported by the Kotlin toolchain, so
  the build will fail on them. Android Studio's bundled JBR 21 works; on macOS you can point Gradle at
  a specific JDK with:

  ```bash
  export JAVA_HOME=$(/usr/libexec/java_home -v 21)
  ```

  The build's own compile/test toolchain is pinned to JDK 17 and auto-provisioned, independent of the
  JDK running Gradle.
- No Android SDK or Xcode is required for the default (JVM + JS) targets.

## Build & test

```bash
./gradlew check                  # everything CI runs: JVM + JS tests, spotlessCheck, koverVerify
./gradlew :domain:jvmTest        # fast pure-core unit tests (JVM)
./gradlew :domain:jsNodeTest     # the same suite cross-compiled to JS (Node)
./gradlew spotlessApply          # auto-format (ktlint via Spotless) — run before committing
./gradlew :domain:koverHtmlReport                          # coverage report
./gradlew :core:jsBrowserProductionLibraryDistribution     # the ESM + .d.ts npm distribution
```

Please make sure `./gradlew check` is green before opening a pull request.

## Project layout

The library is split into modules whose dependencies point **inward only**:

| Module | What lives there |
|---|---|
| `:domain` | Pure, zero-IO core: immutable models, the decision engine, policies, ports (interfaces), wire DTOs. No platform or networking APIs. |
| `:core` | IO + platform glue: HTTP clients, the runtime loop driver, `expect`/`actual` adapters, and the `@JsExport` JS facade. Depends on `:domain`. |
| `:debug-harness` | Dev-only, never published, and nothing depends on it. Platform-free contract conformance probes (`commonMain`, unit-tested) plus the Chrome debug console that drives them and the live browser paths. Diagnostics redact secrets unless the caller opts in — see the debug-harness section of [README.md](README.md). |

## Conventions

- **Kotlin official style**, enforced by Spotless + ktlint 1.3.1. Run `./gradlew spotlessApply` before
  committing; CI fails on unformatted code.
- **`:domain` stays IO-free.** New decision logic belongs there, with table-driven tests in
  `commonTest`. The coverage gate is **≥70%** on `:domain` (`koverVerify`).
- **Use strongly-typed ids** (e.g. `SteamId`, `OfferId`, `DealId`) rather than bare strings.
- **Keep the `@JsExport` surface thin**, and prefer `suspend` functions over callbacks.
- If you touch the wire DTOs, make sure the round-trip serialization tests still pass.

## Commit messages

We use [Conventional Commits](https://www.conventionalcommits.org/). The type prefix drives the next
version number when we release:

- `feat!:` or a `BREAKING CHANGE:` footer → **major**
- `feat:` → **minor**
- `fix:`, `perf:`, `refactor:`, `docs:`, `ci:`, `test:`, `chore:` → **patch**

Example: `fix(engine): dedupe status codes across heartbeat cycles`.

## Pull requests

1. Branch off `main`.
2. Keep changes focused; add or update tests for anything you change in `:domain`.
3. Run `./gradlew check` locally.
4. Open a PR and fill out the template. CI must be green before review.

## The CI badge token

The CircleCI badge URL in [`README.md`](README.md) carries a `circle-token` query parameter. That is
deliberate and not a leaked secret: the CircleCI project is private, so an unauthenticated badge cannot
render, and CircleCI's documented answer for that is a **status-scoped** project token (Project
Settings → API Permissions), which can read nothing but the badge status. Do not "fix" it by deleting
the parameter while the project is private — the badge simply breaks.

## How releases work

Releases are **driven by a version bump merged to `main`** — never create git tags by hand. The version
lives in a single place: `VERSION_NAME` in [`gradle.properties`](gradle.properties) (SemVer).

To cut a release:

1. Move the `## [Unreleased]` entries in [`CHANGELOG.md`](CHANGELOG.md) into a new
   `## [X.Y.Z] - <date>` section (Keep a Changelog format), and refresh the compare links.
2. Set `VERSION_NAME=X.Y.Z` in `gradle.properties` (drop `-SNAPSHOT` for a stable release).
3. Open a PR. Merging it to `main` publishes to npm and, for a stable version, tags `vX.Y.Z` and
   creates a GitHub Release from the changelog section.

`X.Y.Z-SNAPSHOT` versions publish as npm pre-releases under the `snapshot` dist-tag; plain `X.Y.Z`
versions publish under `latest`. A stable release whose `## [X.Y.Z]` section is missing from
`CHANGELOG.md` fails CI by design.

### Pushing without publishing — `[skip publish]`

Because a snapshot version is stamped with the commit count, every commit landing on a publish branch
otherwise mints a new npm snapshot. To push without releasing an artifact, put `[skip publish]` in the
commit message:

```bash
git commit -m "docs: clarify the heartbeat cadence [skip publish]"
```

- Case-insensitive; `[skip-publish]` and `[publish skip]` work too.
- Build, tests and the JS distribution build still run — only the publish is skipped: no npm publish,
  no git tag, no GitHub Release. The job goes green rather than failing.
- It applies to stable versions as well, so a version bump merged with the flag publishes nothing; drop
  the flag on a later commit to release it.
- The flag is read from the merge/squash commit on the publish branch (a squash-merge carries the PR
  title and body), and for a plain merge commit the merged-in commits are scanned too.
- Not the same as `[skip ci]`, which skips the entire pipeline including the tests.
