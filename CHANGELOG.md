# Changelog

All notable changes to `@dmarket/p2p-tracker-core` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html), and commit messages follow
[Conventional Commits](https://www.conventionalcommits.org/).

Releases are automated: the section matching `VERSION_NAME` (in `gradle.properties`) is published to
npm and used as the GitHub Release notes when the version bump is merged to `main`.

## [Unreleased]

### Fixed

- A create whose request Ktor's JS engine rejected (`Error: Fail to fetch`, which is how every create's network
  failure reads, in the browser too) is a transport failure (`SteamCreateFailureCause.TRANSPORT`) rather than an
  unrecognised one: `SteamWriteConfig.transportFailureMarkers` gains `fail to fetch`. Such failures now count
  towards the create surface's breaker (`globalBreakerThreshold`) and carry the transport cause.
- `trackerCoreVersion()` and the heartbeat's fallback `clientVersion` report the real library version.
  Both returned a hand-written `0.1.0-SNAPSHOT` through every release up to 1.0.3-beta; the value is now
  generated from `VERSION_NAME` at build time.
- History states 4, 5, 6, 7, 9 and 11 are decisive transitions, alongside 3 and 12. A reversal Steam
  reported as 4, 5, 6, 7 or 11 went out unproven, and a refund is never moved on an unproven report.

### Added

- `startTrackerWithEvents` and `createBrowserLoop` take an optional trailing `clientVersion`: the host's
  own build, sent as the heartbeat's `clientVersion` so the backend can tell which build a device runs.
  Omitted or blank, the heartbeat reports this library's version as before. Appended last, so existing
  positional calls are unchanged.
- A tracked deal's `proofSites`: the places the backend enforces a proof at on that deal. A decisive code
  whose site is not listed is reported at once, without a proof. An empty list, or a site name this build
  does not know, proves the full decisive set as before.
- `TrackedDeal.deadline` (when the phase its watch serves times out) and `Directive.deadline` (a
  `create_offer`'s create deadline) read the heartbeat's `deadlineTime`; `HeartbeatResponse.dealDeadlines()`
  gives each deal the earlier of the two. Absent, blank, malformed or zero is `null`, never a failed
  heartbeat. Informational: the loop does not act on them.
- `TrackedDeal.assetId` reads the heartbeat's `assetId`, in the deal snapshot's form; blank is `null`.
- A host that runs without a browser can compose the loop itself:
  - `MarketplaceAuthenticator.headersFor` lets an authenticator sign each DMarket request (method, path
    with query, exact body) instead of attaching a token. The default is unchanged.
  - `HeaderSessionSteamOfferCreator` and `HeaderSessionSteamOfferCanceller` send the Steam session, the
    `sessionid` and the anti-CSRF `Referer`/`Origin` as explicit headers. They build only the fixed create
    and cancel URLs, like the browser writers.
  - `PersistedTrackerProgressStore`, `PersistedLoopStateStore` and `PersistedDeviceIdStore` keep the loop's
    state over any `DeviceKeyValueStore`.
  - `LoopConfig.requireLinkedSteamId` takes no Steam action while the heartbeat names no linked Steam
    account (`LinkedSteamIdUnknown`), and `LoopConfig.minWriteTokenLifetime` defers creates on a token about
    to expire. Both are off by default.
  - `TradeTrackerLoop.linkedSteamId`, and `RuntimeSurface.Headless`, which reports platform `sdk`.
  - `PushedSteamToken.check` vets a Steam access token a host pushes in: a steamid64 `sub`, a `web`
    audience, no refresh audience (`renew`/`derive`) and an `exp` still ahead. A refusal's reason never
    carries the token.
  - `PushedSteamSession` holds a pushed Steam token in memory and is the loop's credential cache and source
    (`vault`, `scraper`), so a push reaches the loop's next cycle without waiting for the previous token to
    go stale. It binds to the account the last heartbeat named as linked, once a heartbeat for the current
    DMarket account has come back (`heartbeatReturned`, `forgetLinkedAccount`, `boundSteamId`); before that
    the first token is held on trust. A token for another account is refused
    (`SteamPushResult.WrongAccount`) and never evicts the held one. Nothing is written to storage, and no
    result carries the token: `SteamPushResult.Held` names only the account and the expiry.
  - `HeadlessTracker` composes and drives the loop for such a host. The host pushes the Trading API key
    (`pushTradingApiKey`, held once a signer is built for it; `TradingKeyPushResult`) and a Steam access
    token (`pushSteamToken`); both stay in memory, and the first key and every held token heartbeat at once.
    It neither heartbeats nor touches Steam until a key and a live token are held, binds the Steam account
    fail-closed, starts no create on a token with under 30 minutes left, and generates the Steam `sessionid`
    from the platform's CSPRNG. `HeadlessPlatform` takes the storage for the loop's non-secret state
    (`storageFor`, one store per Steam account), the clock, an event observer and an optional HTTP engine;
    `state()` reports what is held without either secret.
  - `HeadlessTracker` serves one DMarket account. A key pushed while another is held replaces it only after a
    heartbeat signed with it names the Steam account the held key is bound to (`KeyChange.ROTATED`). A key for
    another account is refused (`TradingKeyPushResult.AccountChanged`, naming both public keys and both linked
    accounts), and so is one DMarket refuses (`Invalid`) or whose heartbeat does not come back (`Unverified`):
    the held key and the binding stay, and switching accounts takes a restart. The check is one heartbeat beside
    the loop, for the same device, and leaves the loop's schedule as it was. With no account bound yet a checked
    key replaces the held one (`KeyChange.REPLACED`); with no Steam token ever pushed there is nothing to check,
    and the first heartbeat after a token is pushed takes the binding. A key whose account links no Steam
    account is another account's.
  - `TradingApiAuthenticator` signs each DMarket request with a Trading API key (`TradingApiKey`): Ed25519
    over method, path with query, body and timestamp, sent as `X-Api-Key`, `X-Sign-Date` and
    `X-Request-Sign`, with nothing to refresh on a `401`. `platformEd25519Signer` signs through the JDK on
    the JVM and through WebCrypto on the web (Chrome 137+; Node 24), and refuses a secret that does not
    belong to the public key. The secret is never sent.
  - `HttpConfig.maxReportBodyBytes` caps the body of one `/trade-events` or `/trade-actions` request; a
    longer batch goes out as several requests, in order, and a single report over the cap goes alone. A
    gateway that buffers a large body to disk can check a request signature against an empty body, so a
    signed batch past its buffer is rejected. `null` (the default) means off in the browser and 7 KiB on
    `HeadlessTracker`; `0` is off. Appended last, so positional calls are unchanged. A failure stops the
    batch: reports already sent keep their results, and the rest count as unacknowledged and go out again
    on a later cycle. `reportTradeStatus` signals that case with `TradeStatusBatchInterruptedException`,
    which carries what was delivered.
  - `BundledWasmProverModule` takes an optional `WasmProverHost`: where the prover's assets are, the
    compiled `.wasm` to hand to its init, the `WebSocket` constructor for each notary connect, and a
    connector for the target leg in place of the `wss://` proxy. Every member defaults to what the
    extension does, so the browser path is unchanged. `WasmProverHost.onTargetResponse` is handed the
    target's HTTP status before the presentation is built and can refuse it; unset, the status is not read.
  - `HeadlessPlatform.proving` (`HeadlessProving`) lets `HeadlessTracker` prove through a host that runs the
    prover itself (`ProofHost`), at most one proof at a time by default and two at most. `HostedNotaryProver`
    hands each proof to the host as a credential-free `NotaryProofRequest` with the Steam token and session
    id beside it (`HostedProofRequest`), and a `NotaryUpgradeSigner`: the host asks it for the notary upgrade's
    `X-Api-Key`, `X-Sign-Date` and `X-Request-Sign` right before it connects, signed afresh each time over
    `GET` of the notary URL's path with an empty body (`TradingApiNotaryUpgradeSigner`). The key never leaves
    the tracker. No proof starts on a Steam token with under 30 minutes left (`PROOF_TOKEN_EXPIRING_REASON`).
    The host answers `HostedProofOutcome`: a presentation, or a `ProofFailure` that keeps what ended the proof
    apart (refused upgrade and its status, closed session and its code, unreachable notary, protocol version,
    Steam leg, MPC limits, killed prover and why, not started); `ProofFailures.classify` reads one out of what a
    host observed. Without `proving` the tracker makes no proof, as before.
  - `runHostedProof` runs one such proof in a host's prover context with the wasm prover over the
    `WasmProverHost` seams; the notary socket must authenticate by headers. `HeaderSteamProofCookieSource`
    (and `PushedSteamSession.proofCookies`) builds the Steam web session a cookie-authenticated proven read
    sends from the token. `TradingApiSignature.redact` blanks signed-header values in a log line.
  - `HeadlessTracker`'s heartbeat reports `foreground` from the token: `true` while the token has 30 minutes
    left and no heartbeat has named another Steam account as linked, from the first heartbeat on. Once an
    account is bound, a token that expires or is
    missing no longer stops the heartbeat: it goes on for the bound account with `foreground: false` and
    touches nothing on Steam. In the loop these are `LoopConfig.foregroundMinTokenLifetime` and
    `LoopConfig.heartbeatWithoutCredential`, off by default.
  - `HeadlessTracker` keeps the loop's state per account: `HeadlessPlatform.storageFor` is called for the
    Steam account DMarket links to the key's account. Until the first heartbeat names it, the state of the
    held token's account is read and writes are held in memory; a heartbeat for that account writes them,
    one for another drops them.
  - `HeadlessTracker(steamWrites = false)` builds no offer writer, and `LoopConfig.steamWrites` off drops
    every leased `create_offer` and `cancel_offer` unreported and refuses `createTrade`.
  - `LoopConfig.reportUnsentCreates` off never answers `FAILED` for a `create_offer` that did not reach
    Steam (refused by the account or buyer-role guard, or deferred), which DMarket reads as the seller failing
    to deliver; the lease lapses instead. `HeadlessTracker` turns it off.
  - A 429 from DMarket quiets `HeadlessTracker` until `Retry-After` has passed and not before a backoff that
    doubles from 30 s to 10 min, with up to a quarter more at random. `createLoop` takes the `Random` its
    draws use.
  - `RuntimeSurface.Headless` has its own cadence: it aims its heartbeat `CadenceConfig.headlessHeartbeatMarginMs`
    (5 s) inside the advertised ttl, not a whole poll floor, above a `headlessHeartbeatFloorMs` of 30 s, so at
    the 80-95 s DMarket hands out it beats every 75-90 s instead of every 60 s. Its poll floor stays the
    browser's `webPollFloorMs` (60 s), and with it how often Steam is read. `CadencePolicy.heartbeatMargin` and
    `heartbeatWakeDelay` expose the rule. Every other surface is unchanged.
  - `HeadlessTrackerState` adds `trackedDealIds`, `clockOffset` and `clockSkewed` (DMarket's `Date` against
    the host's clock, past `HeadlessTracker.MAX_CLOCK_SKEW`), `steamNotLinked` (the heartbeat's
    `403 P2P_STEAM_NOT_LINKED`), `updateRequired` and `updateDueTime`, and `dealDeadlines` (the last
    heartbeat's, by deal). A key refused while the clock is off is `Unverified`, not `Invalid`.
    `TradeTrackerLoop.trackedDealIds` lists the last heartbeat's deals.
  - `HeartbeatResponse.updateRequired` and `updateDueTime` read the backend's minimum-version signals; absent,
    they are `false` and `null`, and a malformed time is no schedule.
  - `KtorMarketplaceClient(onServerDate = …)` hears the `Date` of every DMarket reply, a refusal included, and
    `HttpStatusException.serverDate` carries it. A failed heartbeat's body is read for the gateway's
    machine code, carried as `MarketplaceServerErrorException.errorCode`: the `code` field of the gateway's
    `{"code","message"}` reply as sent, a status text with no `P2P_` prefix included, or the first `P2P_…`
    token in a body that is not such an object.
- `DirectiveExecuted` carries the deal it acted for and the outcome's error.
- `LoopConfig.unansweredCreateGrace`, off by default: a `create_offer` Steam did not answer
  (`SteamCreateFailureCause.TRANSPORT`) is not reported `FAILED`, since Steam may have made the offer and
  `FAILED` would leave it with nobody to cancel it. The deal's claim stays in flight, so no second create
  starts, and later cycles read the account's sent offers (`SteamReadClient.sentOffers`, `SteamSentOffer`):
  the offer to the same partner giving the same assets is reported `needs_confirmation`/`success` with its
  id; none once the grace has passed since the attempt is reported `failed`; a failed read waits. The check
  ends with the claim's TTL or the create's deadline (`UnansweredCreateCheck`), reporting nothing, told as
  `DirectiveDropped` with `UNANSWERED_CREATE_UNSETTLED`. `LifecycleEvent.CreateUnanswered` says a create is
  held. `DealWriteClaim.unanswered` (`UnansweredCreate`) and `DealWriteClaimStore.replace` (a compare-and-set)
  carry it; an implementation of `DealWriteClaimStore` outside this library must add it. `SteamReadClient.sentOffers`
  has a default that throws, so such a create is then never settled by a read. `HeadlessTracker` turns it on
  with a two-minute grace.
- `DirectiveDropped.dealId` names the deal of an offer write dropped because `LoopConfig.steamWrites` is off
  (reason `OFFER_WRITES_DISABLED`, now public). Appended last with a `null` default; the event's JSON carries
  `dealId` only when it is set, so the browser's events are unchanged.
- A heartbeat sent with no Steam session (`LoopConfig.heartbeatWithoutCredential`) tells each leased
  `create_offer` and `cancel_offer` it does not run as a `DirectiveDropped` with its deal and
  `STEAM_SESSION_MISSING_DROP_REASON`. They stay unreported, as before.

### Changed

- The history axis joins a deal with no trade id by the heartbeat's `assetId` and reads no
  `GET /exchange/v1/p2p/deals/{id}` for it; a ref that matches nothing waits for the next heartbeat rather
  than a deal read. From a backend that does not send the field, the lookup works as before.
- The browser facade persists loop progress, loop state and the device id through the common persisted
  stores over `chrome.storage.local`. Storage keys and value formats are unchanged.
- The TLSN prover moves to steam-provenance commit `ac583a4` (1.0.3-beta shipped `99e090a`), and is now
  taken at build time from the npm package `@dmarket/steam-provenance`, pinned to exactly
  `0.1.0-SNAPSHOT.30` and to that release's registry integrity, instead of from a copy committed to this
  repository. The layout hosts rely on is unchanged: `pkg/`, `transport/` and `VERSION` at the package
  root, and the prover package is not added to `dependencies`. The build verifies the pin, the package's
  `SHA256SUMS` and `WASM_SHA256` before it copies anything. Deploy against a notary built from `ac583a4`'s
  `dep/tlsn` pin.

## [1.0.3-beta] - 2026-09-23

### Fixed

- `create_offer` and `cancel_offer` no longer fail with `no Steam session cookie` after a browser
  restart. Steam's `sessionid` is a browser-session cookie while the login cookie persists, so a write
  that fired before the user opened Steam found no `sessionid` and gave up without contacting Steam. The
  write actuals now mint it with one request to the community root and fail only if it is still absent.

## [1.0.2-beta] - 2026-09-23

### Fixed

- A failed `cancel_offer` outcome carries Steam's own (redacted) refusal reason instead of a generic
  string, so an offer Steam already closed can be told apart from a transient failure.
- A replacement `create_offer` for a deal whose offer Steam closed without a trade (expired, cancelled,
  declined, invalid items, cancelled by second factor) now creates a new offer instead of re-reporting the
  dead offer's id. The deal's create claim is released when the watch sees the offer closed, and a
  suppressed duplicate checks the claimed offer on Steam before replaying it.

### Added

- `DeadOfferClaimReleased` lifecycle event.

## [1.0.1-beta] - 2026-09-08

### Added

- Refused directives are reported as `unsupported` / `malformed`, releasing their lease.
- Targeted single-trade history read when the windowed read has no row for a deal.

### Changed

- Offer states 8 and 10 are decisive transitions.
- A decisive closure no proof can cover is reported once, unproven, instead of withheld.

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

[Unreleased]: https://github.com/dmarket/p2p-tracker-core/compare/v1.0.3-beta...HEAD
[1.0.3-beta]: https://github.com/dmarket/p2p-tracker-core/compare/v1.0.2-beta...v1.0.3-beta
[1.0.2-beta]: https://github.com/dmarket/p2p-tracker-core/compare/v1.0.1-beta...v1.0.2-beta
[1.0.1-beta]: https://github.com/dmarket/p2p-tracker-core/compare/v1.0.0-beta.1...v1.0.1-beta
[1.0.0-beta.1]: https://github.com/dmarket/p2p-tracker-core/releases/tag/v1.0.0-beta.1
