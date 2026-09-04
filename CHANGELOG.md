# Changelog

All notable changes to `@dmarket/p2p-tracker-core` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html), and commit messages follow
[Conventional Commits](https://www.conventionalcommits.org/).

Releases are automated: the section matching `VERSION_NAME` (in `gradle.properties`) is published to
npm and used as the GitHub Release notes when the version bump is merged to `main`.

## [Unreleased]

## [1.0.0-beta.1] - 2026-09-04

First public release of the trade-tracker core. It is the **seller plugin core** of DMarket's P2P
flow, where skins stay in users' Steam inventories rather than in DMarket custody: it heartbeats to
the backend, executes device-leased directives, watches both Steam trade axes, and reports raw Steam
status codes. The buyer has no plugin, and this library contains **no UI** — each client links it and
provides its own.

This is a **beta line**. The tracking path is complete and functional. TLSN proof generation ships
behind a host-supplied proving context and stays inert until the backend flips per-trade
`proof_required` — see *Current limitations* in the [README](README.md).

### Added

**The loop and the pure engine**

- A directive-driven runtime cycle. `TradeTrackerLoop` heartbeats, executes the directives the
  backend leased to this device, watches the deals it was told to watch, reports every outcome, and
  arms its own next wake. Three entry shapes: `runOnce()` for OS-driven wakes, `start(scope)` for a
  continuous loop, and `wakeFromPush(signal)` for a one-shot push callback. Cycles are
  single-flighted by a mutex, so a scheduled wake and a delivered push can never run one
  concurrently.
- **Every decision is a pure function.** `DirectivePlanner.plan(heartbeat, handled)` filters the
  leased directives down to what this tick should execute, enforcing single-flight per directive id
  and dropping malformed or unknown actions so a new backend action is forward-compatible.
  `TrackerTick.reduce(now, tracking, observed, reported)` compares the freshly observed raw Steam
  codes against the last-reported baseline and emits one report per changed axis, plus a proof
  intent where one is due. Neither reads a clock nor performs IO, so the whole decision surface is
  unit-testable with **zero mocks**.
- **The backend decides; the client executes.** The client never chooses what to create or cancel and
  never decodes a Steam status — it forwards raw codes and the backend owns the deal state machine.
  Its only local classification is change detection plus a fixed decisive set for proof routing.
- `:domain` is pure by construction — no HTTP client, no platform API, no clock — enforced by the
  module graph, and it is the audited surface. `:core` keeps every side effect behind a port, with a
  web implementation and a safe default per port so unimplemented platform seams still compile.

**The two Steam write surfaces**

- **Exactly two writes — create and cancel, never confirm.** A leased `create_offer` POSTs a new
  trade offer and **stops at `CreatedNeedsConfirmation`**; the user confirms in the official Steam
  app. A leased `cancel_offer` cancels a sent offer. Enforcement is by encapsulation: each write
  builds only its one fixed URL, so no confirm, Guard-code or `mobileconf` endpoint is reachable
  through these ports — and those suffixes are deliberately not host-tunable, because a
  remotely-tunable write path could reach a forbidden endpoint.
- A cancel's outcome is read from Steam's resulting offer state, not from the HTTP status alone.
- A refused create names its cause in the outcome it reports back.

**Create back-pressure**

- **Per-counterparty create chains.** Steam caps outstanding offers per partner and blocks the whole
  POST surface if you keep pushing past that cap, while the backend leases every create it wants
  done — often dozens, often all for one partner. So creates are grouped by counterparty and run as
  one chain per partner: concurrent across partners, strictly sequential within one, stopping at the
  first failure. The grouping and capping is a pure function (`CreateChainPlanner`).
- **A jittered, escalating cooldown.** `SteamWriteThrottle` parks a refused counterparty, and parks
  the whole create surface once a global failure threshold is crossed, so a re-leased directive does
  not re-hit Steam on every heartbeat. The throttle state persists and survives a service-worker
  respawn. Cancels are never throttled — a cancel *frees* the partner's quota.

**Watching both trade axes**

- Both Steam axes are watched — per-offer state and account-wide trade history — as account-wide
  batch reads, covering both trade directions.
- **Reports batch.** Changed raw codes go out as one trade-events call, and directive outcomes as one
  trade-actions call at the end of the cycle's create work rather than one call per write. An outcome
  missing from the response counts as unaccepted, so it stays stored for a resend.
- The Steam trade-protection window is reported.
- **Rollback correlation.** A trade-protection reversal is two history records rather than one, and
  the resolved initiator of the reversal travels to the backend instead of being dropped at the wire.
- Present-inventory snapshots on a leased inventory scan, following Steam's paging cursor. A
  truncated enumeration reports itself as incomplete instead of looking like an empty inventory.

**Role awareness**

- A watched deal carries which side of it this account is on, and both writes respect it. The offer
  axis is direction-agnostic; the history axis is not, so the role is what makes history attribution
  correct.

**Idempotency**

- Three independent guards, all persisted across worker respawns: **single-flight per directive id**,
  **per-deal write dedup** (at most one create and one cancel per deal per device, whatever the host
  or a re-lease asks for — a duplicate replays the first result, and this is the only guard that
  catches a *fresh* directive id for a deal already written), and **per-deal status dedup** so an
  unchanged code is never re-reported.

**TLSN proofs**

- **The prover ships inside the package** and is `import()`ed lazily on the first proof, so a build
  that never enables it never loads the ~10 MB module. CI verifies the vendored artifact against its
  published upstream checksums before anything builds — a tampered prover would run multi-party
  computation with access to the device Steam session and a live DMarket token.
- **The notary is off unless the host supplies a proving context, enforced in code rather than by
  convention.** The real prover is selected on one condition — a host proof delegate. Pass none and
  the loop gets a no-op prover: no WASM module loads, no notary socket opens, and the proxy default
  is inert. That is the state of every caller that supplies no prover today.
- Proof routing is driven by the backend's per-deal `proof_required` plus a **fixed decisive set** of
  status transitions, not by a client capability.
- **A freshness re-attestation path.** The backend stamps a deal when its protection hold expires and
  releases the payout only against a proof attested at or after that mark. This is a second,
  independent reason to mint a proof, and it exists precisely when *nothing changed* — so it runs
  above the change detector and above the dedup baseline, and it is answered regardless of role. It
  spends no extra Steam poll: the account-wide history read it rides on is already bounded and
  hourly-sparse once a deal is baselined. A per-deal ledger records the greatest mark a verified
  proof satisfied, alongside a backoff ladder for a refused one.
- **Budgets are sized to the request being proven** rather than paid flat, with a configurable
  margin, and the online-decryption budget is learned from the prover instead of guessed. Record
  count and data caps are exposed as tunables.
- **A wedged prover cannot starve the loop.** Proofs are minted one at a time behind a breaker with
  equal-jitter cooldowns, so a reliably-failing prover neither blocks the heartbeat (and presence
  with it) nor parks healthy deals behind it, and it is not retried on every wake forever. A proof
  the backend already accepted is not re-minted; one it rejected is not re-submitted every tick.
- **Proofs run before the reports they corroborate,** and a report whose proof has not verified is
  withheld rather than sent unbacked.
- A seam for running proofs off the service worker, since the loop itself lives in one.

**Credentials and sessions**

- Two credentials flow through the library and are kept strictly separate: the **device-only Steam
  session** and the **DMarket bearer**.
- **Steam.** `SteamCredentialProvider` composes three ports — device-local secure storage, a scraper
  that re-acquires a fresh session token from an already-logged-in browser session, and a background
  keep-alive for the web session. Freshness keys on the token's own `exp` claim with a configurable
  skew. The vault defaults to the **library-owned** platform vault (extension storage on web,
  Keychain / Android Keystore on native), so a host never handles the plaintext credential. A session
  already past its expiry can be recovered rather than requiring a fresh sign-in.
- **DMarket.** Token rotation is implemented once, in common code, over two thin ports — so a
  platform supplies a token store and gets the whole algorithm: single-flight, a rotation rate limit,
  a persisted refused-token latch, a three-way compare-and-swap against the competing writer, and a
  status policy in which **only** a 401 or a token-less 200 may mean "sign in again". The access
  token's authority is its `exp` claim, not its cookie's, because the web frontend deliberately gives
  both cookies the refresh token's much longer expiry.
- **A host that already refreshes DMarket tokens replaces the provider wholesale.** Two authorities
  rotating one shared credential is strictly worse than either alone, so that seam is a single port
  rather than a set of overrides. Auth attachment is likewise pluggable, letting each platform supply
  the one path that fits it.
- **One blocking state a host renders.** A logged-out session resolves into the single
  highest-priority reason the tracker is blocked, most actionable first — DMarket session missing,
  then Steam session missing, then a Steam account mismatch, then a DMarket connection error, then
  none. A failed Steam scrape on its own stays **signal-only**: it is equally what a rate-limit, a
  5xx or a page change looks like, and must not be shown to the user as "you are signed out". Hosts
  should treat an unrecognised value as blocked rather than as all-clear.
- Steam user profiles — nickname, avatars and account level — behind a reader that batches summaries,
  fetches levels in parallel under a concurrency cap, retries rate limits with jittered backoff, and
  caches per id on a TTL. Authorised, like every Steam read here, by the device session token; no Web
  API key, and nothing leaves the device.

**Scheduling and observability**

- **Cadence is client-owned.** `CadencePolicy` drives the deal-watch poll on caller-chosen poll
  classes clamped to per-surface and per-mode floors (asking a suspended phone to wake every 60s is a
  lie the OS will not honour, so the floor wins). The heartbeat cadence follows the TTL the backend
  returned, clamped to that floor and a safety ceiling. The loop arms the next wake to whichever is
  sooner, and aims slightly *ahead* of the advertised cadence so it does not land after it every
  single period.
- On web the loop **self-drives**: a single named alarm that survives service-worker teardown, re-armed
  each cycle (replace, never stack), with a thrown cycle surfaced rather than silently costing the
  next wake.
- **Push delivery is the host's job.** The library parses a backend push payload and nudges one
  cycle; it owns no push transport, and is poll-only by default.
- **A lifecycle event firehose** as secret-free JSON: heartbeats, cycle completions, Steam read
  failures, directive outcomes, proofs submitted / failed / suppressed, reports deliberately
  deferred, and a Steam account mismatch. Plus the live count of actively-watched trades — a
  `StateFlow` on native, a subscription and a synchronous read on JS.
- Retries use full-jitter exponential backoff, honouring `Retry-After`, applied in-call by the HTTP
  clients and bounded by tunables.

**Distribution and configuration**

- **Kotlin/JS library distribution** consumable as `@dmarket/p2p-tracker-core`: an ES module with
  bundled TypeScript types and a deliberately thin exported facade — start, stop, deliver-push,
  subscribe-to-count, plus lower-level factories for a host that wants to drive cycles itself. All
  Steam wiring is internal; JS only ever holds an opaque handle and never sees the Steam credential.
- An iOS XCFramework and an Android AAR build from the same source, enabled on request.
- **`TrackerConfig`** — one typed, host-suppliable bundle of every operationally tunable or
  third-party-dependent value the library would otherwise hardcode: cadence intervals and floors,
  retry bounds, credential-freshness skews, HTTP timeouts, notary budgets and concurrency, Steam base
  URLs and read endpoints, the fragile scraping patterns, cookie names, and the inventory context id.
  `TrackerConfig.defaults()` reproduces the in-code baseline exactly, so omitting it changes nothing.
  Durations are integer milliseconds to stay JS-friendly across the export boundary.
- **A multi-game seam.** The CS2 app id is never hardcoded; everything Steam encodes per title lives
  behind `GameAdapter`, and the shipped registry enables CS2 only. Adding a title is registering an
  adapter and flipping the enabled set — a config change, never an engine edit.
- **Strongly-typed ids throughout** — deal, Steam account, offer, trade, asset, directive and device
  — never bare strings. The deal id is the canonical tracking key.
- A coverage gate of ≥70% on `:domain` (currently ~90%+), with every domain suite running on **both**
  JVM and JS Node from the same Kotlin source.
- A dev-only conformance harness carrying one callable probe per report and write request, so a
  backend can be verified against the real client and the real deserializer. It is never published,
  nothing depends on it, and its diagnostics redact secrets by default.

### Security

- **The Steam session token is device-only and is never transmitted to DMarket.** It is reachable
  only through the Steam-facing ports, and **no marketplace method accepts it**, so the boundary
  cannot be crossed by accident. This is the central invariant, and the reason the clients are open
  source: anyone can verify it.
- **Five hard rules, treated as build-blocking in review:** no auto-click of Steam's web Send button,
  no Steam Guard / Mobile Authenticator code generation, no reading Steam's `identity_secret` or
  `shared_secret`, no POSTs to `mobileconf` endpoints, and no annotation of non-DMarket trade offers.
- **Request URLs and response bodies never reach a string that leaves the core.** Non-2xx HTTP
  failures are re-minted as status, method and a redacted URL — never a body, and with no retained
  cause or response object; a response body is opt-in per request, always scrubbed and capped. The
  serialization library's debug info, which appends the offending input verbatim to a decoding error,
  is forced off for every `Json` instance. Credential-bearing types redact in `toString()` while
  serializing unchanged. Failure messages that cross a boundary go through a scrub-and-cap helper,
  which also covers throwables the core does not own. The redactor matches tokens **by shape** as well
  as by key name, so a token echoed with no recognisable key in front of it no longer passes through.
- **Steam endpoint hosts are compiled in.** The four base URLs are validated against a fixed Steam
  host set on construction *and* on copy, so paths and query-parameter names stay tunable while the
  hosts do not — the device-only Steam token travels on one of them as a query parameter, and an
  off-Steam base now throws instead of being used. The session-transfer fallback target is
  allow-listed too, and nothing is POSTed if no candidate passes.
- **Both Steam writes are bound to the browser session's own account.** Reads and writes authenticate
  differently — reads pass the session token, writes POST with the browser's cookie session — so
  every write site now verifies the cookie's account immediately before writing, catching a switch
  that happened after the credential was acquired rather than a cycle later. Both identity axes fail
  **open**, so a missing backend field or a cookie hiccup never blocks a legitimate write, and a
  refused write reports failure so the backend releases its lease. The emitted event names only the
  account we hold a token for, never whoever else is signed into the browser.
- **The DMarket refresh token goes to DMarket's own refresh endpoint and nowhere else** — the resolved
  endpoint is allow-listed to the API base or site origin before the request is built, and both
  tokens, the request body and the response are in the redactor's named-secret set. Steam's durable
  refresh cookie is never read.
- No plaintext in-memory credential vault is exported from the JS API.
- Diagnostics redact by default, printing a token's length, decoded claims and a fingerprint rather
  than the token — the claims are what actually distinguish "wrong scope", "expired" and "transport
  problem", so redaction costs no diagnostic power.

[Unreleased]: https://github.com/dmarket/p2p-tracker-core/compare/v1.0.0-beta.1...HEAD
[1.0.0-beta.1]: https://github.com/dmarket/p2p-tracker-core/releases/tag/v1.0.0-beta.1
