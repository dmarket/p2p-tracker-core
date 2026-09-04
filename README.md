# DMarket P2P Trade-Tracker Core

<!--
  `circle-token` below is a CircleCI status-badge token: read-only, scoped to this branch's build
  status alone (no logs, artifacts, env, contexts or pipeline control). A private project's badge 404s
  without it, hence it is committed. Rotate in Project Settings → Status Badges; drop the parameter
  once this repo is public — public badges need no token.
-->

[![CircleCI](https://dl.circleci.com/status-badge/img/gh/dmarket/p2p-tracker-core/tree/main.svg?style=shield&circle-token=CCIPRJ_Achx1Gudu1PvZFyug1DvBq_3de7e84ca282b5fbb4991471da9cad4b0abfdd41)](https://dl.circleci.com/status-badge/redirect/gh/dmarket/p2p-tracker-core/tree/main)
[![npm](https://img.shields.io/npm/v/@dmarket/p2p-tracker-core?logo=npm)](https://www.npmjs.com/package/@dmarket/p2p-tracker-core)

A **Kotlin Multiplatform** library that is the shared brain of the DMarket P2P trade-tracker. One
Kotlin codebase compiles to:

- **JavaScript** — consumed by the MV3 web extension (shipping today),
- an **iOS XCFramework** *(target enabled on request)*,
- an **Android AAR** *(target enabled on request)*.

It is the **seller plugin core** of DMarket's new P2P flow, where skins stay in users' Steam
inventories (not in DMarket custody). It heartbeats to the backend, executes device-leased
directives (`create_offer` / `cancel_offer` / `report_inventory`), watches both Steam trade axes,
and reports **raw** Steam status codes — plus TLSN proofs for decisive transitions on
`proof_required` deals. **The buyer has no plugin** (DMarket FE + Steam UI only). **It contains no
UI** — each open-source client provides its own native UI and links this core.

This is a **clean-room implementation**. The Steam JWT it uses is **device-only and never sent to the
DMarket backend** — that audit boundary is why the clients are open-source.

---

## Contents

- [At a glance](#at-a-glance)
- [Architecture](#architecture) — the bulk of this document
  - [Design principles](#design-principles)
  - [Module layout & the dependency rule](#module-layout--the-dependency-rule)
  - [Ports & adapters (hexagonal)](#ports--adapters-hexagonal)
  - [The pure engine](#the-pure-engine)
  - [The runtime cycle](#the-runtime-cycle)
  - [The backend endpoints](#the-backend-endpoints)
  - [Identity](#identity)
  - [Cadence & scheduling](#cadence--scheduling)
  - [Credentials & the audit boundary](#credentials--the-audit-boundary)
  - [Steam write rules](#steam-write-rules)
  - [TLSN proofs](#tlsn-proofs)
  - [Multi-game](#multi-game)
  - [Configuration (`TrackerConfig`)](#configuration-trackerconfig)
  - [Source tree](#source-tree)
- [Build & run](#build--run)
- [Installing](#installing)
- [Usage](#usage)
- [Testing](#testing)
- [Current limitations (v1)](#current-limitations-v1)
- [Docs](#docs)
- [License](#license)

---

## At a glance

| | |
|---|---|
| **Language** | Kotlin 2.4.0, Kotlin Multiplatform |
| **Targets** | JS (web extension — shipping) · iOS XCFramework · Android AAR *(both on request)* |
| **Modules** | `:domain` (pure, zero-IO) · `:core` (IO + platform glue) · `:debug-harness` (dev-only, unpublished) |
| **Backend contract** | `/exchange/v1/p2p/ext/` — heartbeat, trade-events, notary, trade-actions, inventory |
| **Version** | `1.0.0-beta.1` |
| **Coverage gate** | `:domain` ≥70% (`koverVerify`), currently ~90%+ |
| **HTTP** | Ktor multiplatform (OkHttp / Darwin / JS-fetch per target) |
| **License** | MIT |

---

## Architecture

> The **system-level** architecture & contract — the backend (`p2p` Temporal service,
> exchange-gateway, notary) plus every FE/client and how they fit together — is tracked
> separately. This README covers **this library's implementation of the client (seller-plugin)
> side**.

The library is a small, strictly-layered core built around one idea: **all decisions are a pure
function; all side effects are ports.** Everything below follows from that.

### Design principles

1. **Purity at the center.** The decision logic (`:domain`) performs no IO, reads no clock, and
   touches no platform API. It takes an immutable snapshot in and returns an immutable plan out. This
   is the audited surface, and it is unit-testable with **zero mocks**.
2. **Side effects at the edge.** Everything impure — HTTP, secure storage, OS scheduling, time — sits
   behind a *port* (an interface in `:domain`) and is implemented by an *adapter* (an `actual` /
   class in `:core`, per platform).
3. **The dependency arrow points inward.** `:core` depends on `:domain`; `:domain` depends on
   nothing platform-specific. Purity is compiler-enforced — there is no Ktor, no `kotlinx-io`, no
   `chrome.*` reachable from `:domain`.
4. **The backend decides; the plugin executes.** Every heartbeat returns `active_tracking[]` (deals
   to watch) and `directives[]` (one-shot, device-leased commands). The client **never decides**
   what to create or cancel — it executes what the backend leases to it and reports each outcome.
   It forwards **raw** Steam status codes from both trade axes; the backend maps them and owns the
   deal state machine and the money.
5. **Security by construction.** The Steam credential is reachable only through Steam-facing ports;
   **no marketplace method can accept it**. The two Steam write surfaces each build exactly one fixed
   URL, so a confirm/`mobileconf` endpoint cannot be reached even by mistake. See
   [Credentials & the audit boundary](#credentials--the-audit-boundary).

### Module layout & the dependency rule

```
        ┌─────────────────────────────────────────────────────────────┐
        │  consumers: MV3 web extension · iOS app · Android app        │
        └───────────────┬─────────────────────────────────────────────┘
                        │ links / imports
        ┌───────────────▼─────────────────────────────────────────────┐
        │  :core   — IO + platform glue (depends on :domain)           │
        │  Ktor clients · loop driver · credential providers ·         │
        │  expect/actual adapters · JS facade                          │
        └───────────────┬─────────────────────────────────────────────┘
                        │ depends on (inward only)
        ┌───────────────▼─────────────────────────────────────────────┐
        │  :domain — pure, zero-IO (depends on nothing platform)       │
        │  model · engine · policy · game · port (interfaces) · wire   │
        └─────────────────────────────────────────────────────────────┘

        :debug-harness — dev-only: conformance probes + Chrome console
                         depends on :core; nothing depends on it; never published
```

| Module | What lives there | Purity |
|---|---|---|
| **`:domain`** | `model/` (immutable types, enums, value-class ids, `Deal`/`P2PDealState`/`Directive`/`TrackedDeal`/`TradeStatusReport`), `engine/` (the pure `DirectivePlanner` + `TrackerTick` + `DecisiveTransitions` + `ProofFreshness` + `TerminalClassification`), `policy/` (`CadencePolicy`, `ExponentialBackoff`), `game/` (`GameAdapter`, `Cs2GameAdapter`, `GameRegistry`), `port/` (interfaces only), `wire/` (`@Serializable` DTOs + mappers + `TrackerJson`), `net/` (`NetworkRedaction`), `config/` (`TrackerConfig` tunables). | **Pure, zero-IO.** No Ktor, no platform APIs. Compiler-enforced. The audited surface. |
| **`:core`** | Ktor `MarketplaceClient` + `SteamReadClient`; the `TradeTrackerLoop` driver; `SteamCredentialProvider` / `MarketplaceCredentialProvider` + session refreshers; `expect`/`actual` adapters (`Scheduler`, `CredentialVault`, `Clock`, HTTP engine); the `@JsExport` JS facade. | **IO + platform glue.** Depends on `:domain` only. |
| **`:debug-harness`** | Two things, split by whether they need a browser: `C1ReportProbes` + `SecretRedaction` (`commonMain`, platform-free, unit-tested) — the **client half of a contract conformance check**, one callable probe per report/write endpoint, so a backend can be verified against the real client and deserializer; and a dev-only Chrome MV3 debug console (`createDebugSession` / `DebugSession`, `jsMain`) that exposes those plus the live browser paths (scrape / refresh / vault / Steam + DMarket reads and writes). | Dev-only. **Never published** (no publish config; the npm artifact is built from `:core` alone) and nothing depends on it, so none of this reaches a consumer. Must not expand `:core`'s audited `@JsExport` surface. Diagnostics **redact secrets by default** — see below. |

### Ports & adapters (hexagonal)

`:domain` declares **what** the runtime needs as interfaces in
[`port/`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/port/); `:core` provides the **how**
per platform. Each port has a web `actual`/implementation today and a safe default (usually a no-op),
so unimplemented platform seams keep compiling. The composition root
([`TradeTrackerCore.createLoop`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/runtime/TradeTrackerCore.kt))
wires the chosen adapters into a `TradeTrackerLoop`. See the [ports table](#ports-you-implement-per-platform).

### The pure engine

All decisions are two pure functions in
[`engine/`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/engine/). The runtime gathers
inputs through ports, calls the engine, and is then a *dumb executor* of the returned plan.

```
   ports (IO)                 pure core (no IO, no clock)                  ports (IO)
 ┌───────────┐  heartbeat  ┌──────────────────────────────────────┐  plan  ┌───────────┐
 │ heartbeat │ ──────────▶ │ DirectivePlanner.plan(hb, handled)   │ ─────▶ │ execute + │
 │ + observe │             │  • create / cancel / inventory scans │        │ report    │
 └───────────┘   observed  │ TrackerTick.reduce(now, tracking,    │reports └───────────┘
                ──────────▶ │                observed, reported)  │ ─────▶ /trade-events
                            │  • changed-code reports + proofs    │        + /notary
                            └──────────────────────────────────────┘
```

- [`DirectivePlanner.plan(heartbeat, handled)`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/engine/DirectivePlanner.kt)
  filters the heartbeat's `directives[]` down to what this tick should execute, partitioned into a
  `DirectivePlan` (`creates` / `cancels` / `inventoryScans`). It enforces **single-flight** — a
  `directive_id` already in the `handled` set is never re-executed — and drops malformed or unknown
  actions (forward-compatible).
- [`TrackerTick.reduce(now, activeTracking, observed, reported)`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/engine/TrackerTick.kt)
  is the pure change-detector for the watch loop: per tracked deal it compares the freshly observed
  raw Steam codes (both axes) against the last-reported baseline (`ReportedStatus`) and emits a
  `ReportPlan` — one `TradeStatusReport` per **changed** axis (raw code, no verdict — the backend
  maps it) plus a `ProofIntent` when the deal is `proof_required` and the code is decisive
  ([`DecisiveTransitions`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/engine/DecisiveTransitions.kt):
  offer 2/3/4/6/7, history 3/12).
- [`ProofFreshness.due(tracking, progress)`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/engine/ProofFreshness.kt)
  is the second, independent reason a proof is minted: the backend stamps a `prove_after`
  mark on a deal's watch entry when its protection hold expires, and releases the payout only against
  a proof attested at or after it. A demand is deliberately **not** a `ProofIntent` and not a
  `TrackerTick` concern — it exists precisely when nothing changed, it carries no Steam status code
  (the proven read discovers it), and it is answered regardless of `role` or `proof_required`. Its
  per-deal standing — the greatest mark a verified proof satisfied, plus the backoff ladder for a
  refused one — persists alongside the two guards below.

Both idempotency guards persist behind
[`TrackerProgressStore`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/loop/TrackerProgressStore.kt)
(handled `directive_id`s + per-deal `ReportedStatus`), durable across worker respawns.

Because the engine reads nothing but its inputs (not even a clock — `now` is supplied), a test is
just: construct a heartbeat, call the function, assert on the plan. No mocks, no fakes, no time
control.

### The runtime cycle

[`TradeTrackerLoop`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/loop/TradeTrackerLoop.kt) is
the single place that coordinates every port into one full cycle (happy-path phases 2–4):

```
 runOnce():
   1. heartbeat     POST /heartbeat {device_id, foreground, steam_id}
                    → active_tracking[] + directives[] + ttl_seconds
   2. execute       DirectivePlanner.plan(heartbeat, handled)                        (pure)
                    create_offer → SteamOfferCreator (stops at NeedsConfirmation)
                    cancel_offer → SteamOfferCanceller
                    report_inventory → SteamInventoryReader → POST /inventory
                    each outcome → POST /trade-actions (releases the lease)
   3. watch+report  Steam reads (offerStatuses + recentTransfers) for active_tracking[]
                    TrackerTick.reduce(now, tracking, observed, reported)            (pure)
                    changed raw codes → POST /trade-events
                    decisive + proof_required → NotaryProver → POST /notary
   4. record        persist handled directive_ids + per-deal ReportedStatus
   5. reschedule    arm the next wake (deal-watch cadence or next heartbeat, whichever is sooner)
```

Cycles are **single-flighted** by a mutex, so a scheduled wake and a delivered push can never run a
cycle concurrently (no double execute). Directive execution is additionally gated by
`directivesEnabled` — a launch gate that stays `false` until the backend `device_id` lease is live.
The loop has three entry shapes:

| Entry | Use |
|---|---|
| `suspend fun runOnce(): TickOutcome` | One full self-contained cycle (heartbeat + directives + watch/report). OS-driven platforms call it once per wake. |
| `fun start(scope): Job` | A continuous loop reacting to scheduled wakes and pushes from a single consumer. With the default `NoOpPushChannel` it is poll-only. |
| `suspend fun wakeFromPush(signal): TickOutcome` | Direct entry for a one-shot OS push callback (APNs/FCM); a push is just a nudge that runs one cycle now. |

On the web, the loop **self-drives**: `startTracker` arms a `chrome.alarms` wake-up that survives
MV3 service-worker teardown, runs one cycle per wake, and re-arms the single named alarm to the next
client-owned time (replace, never stack). See [Usage](#web-extension-the-primary-shipping-path).

### The backend endpoints

The DMarket backend is reached over the **trade-tracker** REST endpoints (via the
exchange-gateway; base path `/exchange/v1/p2p/ext/`), modelled by
[`MarketplaceClient`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/port/marketplace/MarketplaceClient.kt).
The backend derives the account from the Bearer token, so **no request carries an `account_id`**.

| Method | Endpoint | Purpose |
|---|---|---|
| `heartbeat(request)` | `POST …/ext/heartbeat` | Presence + `device_id`; returns `active_tracking[]` + `directives[]` + `ttl_seconds`. The sole presence + work-dispatch call. |
| `reportTradeStatus(reports)` | `POST …/ext/trade-events` | Batch **raw** `steam_status_code` ints (no proof); one `TradeStatusResult` per report. |
| `submitProof(proof)` | `POST …/ext/notary` | A TLSN proof for a decisive transition (`proof_required` deals only). |
| `reportDirective(outcome)` | `POST …/ext/trade-actions` | Report a `create_offer`/`cancel_offer` directive outcome (releases the Redis lease). |
| `reportInventory(report)` | `POST …/ext/inventory` | The present-`asset_ids` snapshot; the backend computes the stale diff. |
| `acceptDeal(id)` | `POST /p2p/deals/{id}/accept` | The seller COMMIT (`applied=false` if too late) — a **DMarket app convenience**, not part of the tracker's directive loop. |
| `getDeal(id)` | `GET /p2p/deals/{id}` | A single deal snapshot (host convenience). |

The Ktor implementation
([`KtorMarketplaceClient`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/client/marketplace/KtorMarketplaceClient.kt))
handles the `{code, message}` error envelope, 401-refresh-once (via the
[authenticator](#credentials--the-audit-boundary)), and 429 `Retry-After` (`RateLimitedException`).

### Identity

- **Identity is `deal_id`.** The canonical tracking key is
  [`DealId`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/model/Ids.kt) — the wire is
  `deal_id`-centric. A Steam trade-offer is an `OfferId`, an item instance an `AssetId`, a Steam
  account a `SteamId`, and the install-scoped directive-lease key is a `DeviceId`. Ids are
  **strongly typed** value classes — never bare strings. The backend derives the account from the
  Bearer token (no request sends an `account_id`) and owns the deal state machine and the
  deal↔trade join.
- **No status decoding on the client.** The client reads both Steam axes
  ([`SteamReadClient`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/port/steam/SteamReadClient.kt)
  `offerStatuses` + `recentTransfers`) and forwards the **raw** codes as `TradeStatusReport`s — the
  backend maps them and decides every deal transition. The client's only local classification is
  change-detection (`TrackerTick`) plus the fixed decisive set (`DecisiveTransitions`) for proof
  routing.

### Cadence & scheduling

Cadence is **client-owned**, governed by
[`CadencePolicy`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/policy/CadencePolicy.kt):

- The deal-watch poll runs on FE-chosen **poll classes** clamped to per-surface/mode **floors**; the
  heartbeat cadence is the `ttl_seconds` returned by the last heartbeat response (clamped to the
  surface floor and a safety ceiling). The loop arms the next wake to whichever is sooner.
- **Floors** are per-surface/mode (asking iOS to wake every 60s while suspended is a lie the OS won't
  honour, so the floor wins). The actual wake-up is the platform's own primitive (`chrome.alarms` /
  WorkManager / BGTaskScheduler) behind the `Scheduler` port.
- Retries use full-jitter exponential backoff
  ([`ExponentialBackoff`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/policy/ExponentialBackoff.kt)),
  applied in-call by the Ktor clients and tuned via `TrackerConfig.marketplaceRetry`.

### Credentials & the audit boundary

Two independent credentials flow through the library, kept strictly separate:

**1. The Steam JWT — device-only, never transmitted to DMarket.** This is the central security
invariant and the reason the clients are open-source.

```
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  Steam-facing ports ONLY (the JWT lives here, behind the boundary):            │
 │  SteamReadClient · SteamOfferCreator · SteamOfferCanceller · NotaryProver      │
 └──────────────────────────────────────────────────────────────────────────────┘
            ╳  no MarketplaceClient method accepts a SteamCredential  ╳
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  MarketplaceClient (DMarket) — only ever sees a SteamId, never the credential  │
 └──────────────────────────────────────────────────────────────────────────────┘
```

The credential is managed by
[`SteamCredentialProvider`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/credential/steam/SteamCredentialProvider.kt),
which composes three ports: a `CredentialVault` (device-local secure storage), a
`SteamSessionScraper` (re-acquire a fresh JWT from a logged-in session), and a
`SteamSessionRefresher` (keep the `steamLoginSecure` web session alive in the background). Freshness
is keyed on the JWT's `exp` (with a configurable skew). The vault **defaults to the lib-owned
`platformCredentialVault()`** (`chrome.storage.local` on web, Keychain / Android Keystore on native)
— so the host never sees the plaintext credential.

**2. The DMarket marketplace JWT.** Managed by
[`MarketplaceCredentialProvider`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/credential/marketplace/MarketplaceCredentialProvider.kt)
and attached via a pluggable
[`MarketplaceAuthenticator`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/client/marketplace/MarketplaceAuthenticator.kt)
strategy — *how* a DMarket request is authenticated is decoupled from the client so each platform
supplies the one path that fits it:

| Authenticator | Platform | Behaviour |
|---|---|---|
| `CredentialMarketplaceAuthenticator` | **Web** — the library owns auth | Reads the `dm-trade-token` cookie and refreshes it through the DMarket refresh API when it nears expiry (`DefaultMarketplaceCredentialProvider`), attaches it as the `Authorization` header **raw — no `Bearer ` scheme**, which is what the gateway parses, and asks for one forced refresh on 401. |
| `CredentialMarketplaceAuthenticator` over a **host-supplied** provider | **Mobile** — the host owns refresh | Same authenticator, different provider: the app plugs its own token layer in through `HostTokenMarketplaceCredentialProvider`, so the library never runs a second refresh mechanism against the same rotating credential. |
| `TransportManagedMarketplaceAuthenticator` (default) | A host that authenticates inside its own transport | Attaches nothing and never retries. Note this is **not** the right choice for the DMarket Android app, which attaches `Authorization` per Retrofit method rather than via an interceptor. |

When either session is logged out the loop surfaces a **signal-only** flag — `needsReLogin` (Steam)
and `needsMarketplaceReLogin` (DMarket) — and never opens UI itself; the host shows login UI, and the
flag clears on the next successful scrape. Both axes additionally resolve into `blockingState` (the one
value a host renders): a DMarket logout as `DM_SESSION_MISSING`, and a Steam session that is
*demonstrably* gone (the session cookie has been deleted) as `STEAM_SESSION_MISSING`. A failed Steam
scrape on its own stays signal-only — it is equally what a Steam rate-limit, 5xx or page change looks
like, and must not be shown to the user as "you are signed out".

### Steam write rules

The **only** two Steam write surfaces are **create** and **cancel** — **never confirm**. Enforcement
is by encapsulation: each write actual builds **only its one fixed URL**
(`…/tradeoffer/new/send` for create, `…/tradeoffer/{id}/cancel` for cancel), so no
confirm / Guard-code / `mobileconf` endpoint can be constructed through these ports — there is no
code path that builds one. The hard, build-blocking rules:

1. No auto-click of Steam's web **Send button** (DOM action).
2. No Steam Guard / Mobile Authenticator code generation.
3. No reading Steam's `identity_secret` / `shared_secret`.
4. No POSTs to `mobileconf` endpoints.
5. No annotation of non-DMarket trade offers.

These write-path URL suffixes are deliberately **kept hardcoded** (not in `TrackerConfig`) precisely
because a remote-tunable write path could reach a forbidden endpoint. The create write surface is
pending DMarket security sign-off.

### TLSN proofs

TLSN is **pending DMarket security review** — launch-blocking for production, but non-TLSN tracking
is fully functional: v1 runs **client-reported mode** (no proofs) until the backend flips per-trade
`proof_required`.

**The notary is off unless the host supplies a proving context, and that is enforced in code, not by
convention.** The real prover is selected on one condition: a host proof delegate. `TrackerConfig.notary
.notaryUrl` is no longer part of that gate — it is required and defaults to the deployed production notary
— so what a host controls is *whether a prover can run at all*. Pass no delegate and the loop gets
[`NoOpNotaryProver`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/adapter/notary/NoOpNotaryProver.kt)
— no WASM prover runs, no notary socket opens, and the `wss://` proxy default is inert. That is the state
of every caller that passes no prover (mobile today) and of Firefox, which cannot host one.
The prover ships inside the package (`vendor/tlsn/`, copied next to the library's ESM entry)
but is `import()`ed **lazily on the first proof**, so a build that never enables it never loads the
~10 MB module. Bundling notes: [vendor/tlsn/INTEGRATION.md](vendor/tlsn/INTEGRATION.md).

Proof routing is driven by `proof_required` + the **fixed decisive set** (offer
2/3/6, history 12 —
[`DecisiveTransitions`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/engine/DecisiveTransitions.kt)),
not a client capability. When a `proof_required` deal crosses a decisive transition, the loop asks
[`NotaryProver.proveTransition(dealId, source, credential)`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/port/notary/NotaryProver.kt)
for the proof and POSTs it to `/notary`. **MVP** uses
[`NoOpNotaryProver`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/adapter/notary/NoOpNotaryProver.kt) —
a **stub presentation**, so the flow runs end-to-end against the backend's mock verify until the
real WASM prover (`dmarket/steam-provenance` — P-256, MaxConcurrency=2) is wired.

### Multi-game

CS2 only at v1, but `appid=730` is **never** hardcoded. Everything Steam encodes per-title — the
inventory context id, app-id ownership — lives behind
[`GameAdapter`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/game/GameAdapter.kt).
`GameRegistry.v1()` enables CS2 only; turning on Dota2/TF2/Rust is registering an adapter and
flipping the enabled set — a config change, never an engine edit. The adapter matters for the Steam
trade-creation body (inventory context id); the engine reads no app-id constant.

### Configuration (`TrackerConfig`)

[`TrackerConfig`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/config/TrackerConfig.kt) is one
typed, host-suppliable bundle of every operationally-tunable / third-party-dependent value the
library would otherwise hardcode — cadence intervals & floors, retry/backoff bounds, credential-freshness
skews, the session-refresh gate, the HTTP timeout, notary concurrency, Steam base URLs & read
endpoints, the (fragile) scraping regexes, cookie names, and the CS2 inventory context id. It is
passed to `startTracker` / `createBrowserLoop`; `TrackerConfig.defaults()` reproduces the in-code
baseline exactly, so omitting it changes nothing. Durations are stored as `Int` milliseconds to stay
JS-friendly across the export boundary. The Steam **write** URL suffixes and the locked DMarket
endpoint paths are intentionally *not* tunable (see [Steam write rules](#steam-write-rules)).

### Source tree

```
domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/
  model/    immutable types, enums, value-class ids, Deal/P2PDealState, Directive, TrackedDeal, TradeStatusReport
  engine/   DirectivePlanner + TrackerTick (the pure engine), DecisiveTransitions, ProofFreshness, TerminalClassification
  policy/   CadencePolicy (poll classes + floors), Backoff (ladders/cursor)
  game/     GameAdapter, Cs2GameAdapter, GameRegistry
  port/     all port interfaces (the audit boundary is declared here)
  wire/     @Serializable DTOs + mappers (P2pMappers) + TrackerJson
  net/      NetworkRedaction (secret scrubbing for observed HTTP metadata)
  config/   TrackerConfig + sub-configs

core/src/commonMain/kotlin/com/dmarket/p2p/tracker/
  runtime/      TradeTrackerCore (composition root), TrackerHandle
  loop/         TradeTrackerLoop (the driver), TrackerProgressStore, LoopStateStore, LoopConfig
  client/       KtorMarketplaceClient, KtorSteamReadClient, MarketplaceAuthenticator, HTTP engine (expect)
  credential/   Steam/Marketplace credential providers + session refreshers
  adapter/      Clock/Scheduler/CredentialVault defaults + expect declarations + no-op ports
core/src/jsMain/...   Chrome actuals (alarms, storage), Fetch* ports, JsApi + Tracker.js (the @JsExport facade)
core/src/{jvm,android,ios}Main/...   per-target actuals (HTTP engine, scheduler, vault)
```

---

## Build & run

> **JDK requirement:** the Kotlin compiler can't yet parse JDK 25's version string, so **Gradle must
> run on JDK 17–21**. Locally: `export JAVA_HOME=$(/usr/libexec/java_home -v 21)` (Android Studio's
> bundled JBR 21 works). The build's own compile/test toolchain is pinned to JDK 17 and
> auto-provisioned via foojay, independent of the JDK running Gradle.

```bash
./gradlew check                  # all tests (JVM + JS) + spotlessCheck + koverVerify
./gradlew :domain:jvmTest        # fast pure-core unit tests (JVM)
./gradlew :domain:jsNodeTest     # same suite cross-compiled to JS (Node)
./gradlew spotlessApply          # auto-format (ktlint via Spotless) — run before committing
./gradlew :domain:koverHtmlReport
./gradlew :core:jsBrowserProductionLibraryDistribution   # ESM + .d.ts for the extension
./gradlew publishToMavenLocal    # dry-run publish (real publish is on-request)
```

- **iOS / Android targets are deferred (on request)** — see the commented blocks in
  `build-logic/.../dmarket.kmp.library.gradle.kts`. The iOS target can only be built on a CI macOS
  runner with full Xcode.
- **The dev-only Chrome debug harness:** `./gradlew :debug-harness:assembleDebugExtension`, then load
  `tools/debug-extension` unpacked — see [tools/debug-extension/README.md](tools/debug-extension/README.md).

### Verifying a backend against this client (conformance)

`:debug-harness` carries one callable probe per report/write request — `reportTradeStatus` (plus its
batched form), `reportDirective`, `reportInventory`, `submitProof` — in
[`C1ReportProbes`](debug-harness/src/commonMain/kotlin/com/dmarket/p2p/tracker/debug/C1ReportProbes.kt).
Each issues **one** request through the real `MarketplaceClient` and hands back what the real
deserializer read from the reply, so a field the backend renamed or re-cased shows up as a missing
value instead of as a green test. The loop cannot be used for this: it reaches a write endpoint only
once the deal state makes it decide to, which a conformance run has no way to arrange.

Two properties are deliberate. Probes take **wire primitives**, so a malformed value can be sent on
purpose (the id value classes reject it). And **no input is coerced** — an unrecognised
`action`/`status`/`source` is refused, naming the accepted set, rather than being replaced with a
default: a typo silently rewritten into a valid-but-different request would be accepted by the backend
and pass, which is worse than no probe at all.

Because the probes take a port rather than a browser, they are driven both from the Chrome console and
from `commonTest` against a Ktor `MockEngine`
([`C1ReportProbesTest`](debug-harness/src/commonTest/kotlin/com/dmarket/p2p/tracker/debug/C1ReportProbesTest.kt)),
which is where the emitted field names are asserted — the half a live run cannot check for itself.

### Credentials in the debug surface

The diagnostic probes print a token's **length, decoded JWT claims and a fingerprint — never the token
itself**. Those claims are what actually distinguish "wrong scope", "expired" and "transport problem",
so redaction costs no diagnostic power, and the fingerprint keeps "is this the same token?" answerable.
The raw values appear only through `createDebugSessionRevealingSecrets(...)` (or
`startDebugSession(..., revealSecrets = true)` for the session log) — a separate entry point, for
hands-on local diagnosis, that nothing automated calls. This is the same rule the production path
follows (`NetworkObserver.redactSecrets` defaults to `true`), made the default here too instead of an
exception living in a dev module.

## Installing

- **Web extension (npm — the shipping target):**

  ```bash
  npm install @dmarket/p2p-tracker-core
  ```

  Ships as an ES module with bundled TypeScript types (`.d.ts`); the package entry is
  `p2p-tracker-core.mjs`. The `latest` dist-tag is the current **beta** line (`1.0.0-beta.1`), so a
  plain `npm install` resolves it; because a SemVer prerelease is not matched by a caret range, pin
  it explicitly (`"@dmarket/p2p-tracker-core": "1.0.0-beta.1"`) rather than `^1.0.0`. Development
  builds continue to publish under the `snapshot` dist-tag
  (`npm install @dmarket/p2p-tracker-core@snapshot`).
- **Android / JVM:** `implementation("com.dmarket.p2p:trade-tracker-core:<version>")`
  (`trade-tracker-domain` too) — *planned; Maven Central publishing is not wired yet.*
- **iOS:** XCFramework via a SwiftPM binary target — *once the iOS target is enabled.*

---

## Usage

### Web extension (the primary shipping path)

The web build exports a deliberately thin JS facade
([JsApi.kt](core/src/jsMain/kotlin/com/dmarket/p2p/tracker/runtime/JsApi.kt) +
[Tracker.js.kt](core/src/jsMain/kotlin/com/dmarket/p2p/tracker/runtime/Tracker.js.kt)). All Steam
wiring is internal — JS only ever holds an opaque `TradeTrackerLoop`/handle and never sees the Steam
credential ([audit boundary](#credentials--the-audit-boundary)).

The lifecycle is **start / stop**. After `startTracker` the library self-drives: it arms a
`chrome.alarms` wake-up that **survives MV3 service-worker teardown**, runs one full cycle per wake
(heartbeat + directives + watch/report), and reschedules itself to the next client-owned time
(deal-watch cadence or next heartbeat, whichever is sooner). The single named alarm is re-armed each
cycle (replace, never stack) and cycles are single-flighted — no duplicate runs. **Push is delivered
by the host, not the lib:** your own `push` listener hands the raw payload string to
`deliverPush(handle, payload)`, which nudges one cycle and reschedules.

```js
// sw.js — module service worker. Call startTracker SYNCHRONOUSLY at the top level on every boot:
// when an alarm respawns a torn-down worker, only listeners registered during that fresh top-level
// run receive the event.
import { startTracker, stopTracker, deliverPush, subscribeActiveTrackingCount } from "@dmarket/p2p-tracker-core";

// 1. Start the self-driving tracker. No token seeding — the DMarket JWT is scraped automatically
//    from the logged-in dmarket.com session (the dm-trade-token cookie).
const handle = startTracker("https://api.dmarket.com");

// 2. Deliver pushes from your OWN handler — the lib only parses the payload.
self.addEventListener("push", (e) => e.waitUntil(deliverPush(handle, e.data.text())));

// 3. (Optional) Track the live number of actively-watched trades (size of active_tracking[]).
//    onCount fires immediately with the current value, then on every change; the return unsubscribes.
const unsubscribe = subscribeActiveTrackingCount(handle, (count) => {
  console.log(`actively tracking ${count} trades`);
});
// …or read it synchronously (e.g. when a popup mounts): activeTrackingCount(handle)

// 4. Stop it (e.g. on sign-out): clears the alarm + detaches listeners.
// unsubscribe();
// stopTracker(handle);
```

`startTracker` is the same `expect`/`actual` entry on every platform (web `chrome.alarms` now; iOS
`BGTaskScheduler` and Android `WorkManager` when those targets are enabled). Re-login is signal-only;
for finer control, build the loop yourself (below) and poll `needsReLogin` / `needsMarketplaceReLogin`.

**Exported factory functions:**

| Export | Purpose |
|---|---|
| `startTracker(baseUrl, config?)` | **Start the self-driving tracker** (the primary entry). Returns an opaque handle. |
| `stopTracker(handle)` | Stop a tracker started with `startTracker` (clears the alarm + detaches listeners). |
| `deliverPush(handle, payloadJson)` | Hand a backend push payload (received by your own `push` handler) to the tracker — nudges one cycle, reschedules. Returns a `Promise`. |
| `startTrackerWithEvents(baseUrl, config?, onEvent)` | Like `startTracker`, but `onEvent(json)` receives **every** loop lifecycle event as a secret-free JSON string (the full firehose). |
| `subscribeActiveTrackingCount(handle, onCount)` | Subscribe to the **active-tracking count** — the live number of trades being watched (size of `active_tracking[]`). `onCount(n)` fires immediately with the current value, then on each change. Returns an unsubscribe `() => void`. |
| `activeTrackingCount(handle)` | Read the current active-tracking count synchronously (`number`; `0` before the first cycle). |
| `createBrowserLoop(baseUrl, config?)` | Lower-level: a fully-wired `TradeTrackerLoop` if you want to drive `runOnce()` yourself. |
| `createBrowserMarketplaceClient(baseUrl, config?)` | Standalone Ktor marketplace client (fetch engine; scrapes the DMarket JWT from the `dm-trade-token` cookie). |
| `createBrowserSteamClient()` | Standalone Steam read client (fetch engine). |
| `trackerCoreVersion()` / `enabledGameCount()` | Version string / count of enabled games (CS2 only at v1). |

**Required MV3 manifest permissions:** `"storage"`, `"cookies"`, `"alarms"` (self-drive wake-up),
`"tabs"` (background `dmarket.com` load for marketplace session-refresh), `"notifications"` (push
display, when enabled), and `host_permissions` for `https://dmarket.com/` (scrape the DMarket JWT
from the `dm-trade-token` cookie + session-refresh), `https://steamcommunity.com` (cookie-bearing
scrape + offer create/cancel), `https://login.steampowered.com` and `https://store.steampowered.com`
(Steam background session-refresh).

### Steam user profiles (nickname / avatar / level)

[`SteamProfileReader`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/port/steam/SteamProfileReader.kt)
fetches a Steam user's public profile — nickname + avatars (`ISteamUser/GetPlayerSummaries`) and
account level (`IPlayerService/GetSteamLevel`) — authorised, like every Steam read here, by the
device session token (no `key=` Web API key; nothing leaves the device). The Ktor implementation
([`KtorSteamProfileReader`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/client/steam/KtorSteamProfileReader.kt))
batches summaries (≤100 ids/call), fetches levels in parallel (concurrency-capped), retries HTTP 429
with jittered exponential backoff, and is wrapped by
[`CachingSteamProfileReader`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/client/steam/CachingSteamProfileReader.kt)
for a per-id TTL cache (default 5 min) that spares the 100k-calls/day quota.

```kotlin
val reader = CachingSteamProfileReader(
    delegate = KtorSteamProfileReader(
        httpClient = createSteamHttpClient(requestTimeoutMs = config.steamProfile.requestTimeoutMs.toLong()),
        endpoints = config.steamEndpoints,
        config = config.steamProfile,
    ),
    clock = SystemClock(),
    ttl = config.steamProfile.cacheTtl,
)

val credential = steamCredentialProvider.current() ?: error("not logged in")

// One profile — throws UserNotFoundException if Steam doesn't know the id,
// InvalidSteamId64Exception if the id isn't a 17-digit "7656…" steamID64.
val me = reader.getUserProfile(credential, SteamId("76561198000000001"))
println("${me.nickname} · level ${me.level ?: "private"} · ${me.avatarFullUrl}")

// Many at once — batched summaries + parallel levels, input order preserved,
// unknown ids omitted. A private profile still returns identity with level = null.
val profiles = reader.getUserProfiles(credential, ids)
```

Behaviour: **403** → `SteamProfileAuthException` (token invalid/rotated); **429** past the retry
budget → `SteamRateLimitedException` (carries `Retry-After`); **private profile** → normal result
with `level = null`. Tunables live in `TrackerConfig.steamProfile`
([`SteamProfileConfig`](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/config/TrackerConfig.kt):
`cacheTtlMs`, `maxConcurrency`, `batchSize`, `requestTimeoutMs`, `maxRetries`, `retryBaseDelayMs`,
`retryMaxDelayMs`).

> **Not yet confirmed against live Steam** that these two endpoints accept the web-session
> `access_token` (support varies per endpoint) or that they rate-limit with HTTP 429 + `Retry-After`.
> The shapes are unit-tested but pending real-world verification; because the endpoint paths and
> param names are config-driven, a pivot to a `key=` model is a config + small auth change, not a
> rewrite. No `@JsExport` factory is shipped yet — add a `createBrowserSteamProfileReader()` (mirroring
> `createBrowserSteamClient()`) when a JS consumer needs it.

### Custom wiring (JVM / Android / iOS)

On platforms without the JS facade you compose the loop yourself through
[`TradeTrackerCore.createLoop(...)`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/runtime/TradeTrackerCore.kt),
the composition root. Inject the ports your platform provides; the rest default to no-ops (and the
vault defaults to the lib-owned `platformCredentialVault()`).

```kotlin
val loop = TradeTrackerCore().createLoop(
    config = LoopConfig(
        clientVersion = "1.0.0",
        surface = RuntimeSurface.AndroidNative,
        mode = TrackerMode.Background,
    ),
    marketplace = myMarketplaceClient, // MarketplaceClient (e.g. KtorMarketplaceClient over OkHttp)
    steamReader = mySteamReadClient,   // SteamReadClient (offerStatuses + recentTransfers)
    scraper = mySteamSessionScraper,   // SteamSessionScraper
    scheduler = myWorkManagerScheduler, // Scheduler (WorkManager / BGTaskScheduler)
    deviceId = myDeviceIdStore,        // DeviceIdStore — persistent install-scoped device_id (the directive-lease key)
    // vault defaults to platformCredentialVault() (Keychain / Android Keystore); override for tests.
    // clock / notary / offerCreator / offerCanceller / inventoryReader / sessionRefresher /
    // marketplaceCredentials / progress / loopState / pushChannel / eventObserver all default to
    // safe no-ops; directivesEnabled defaults to false (the launch gate).
)

val outcome = loop.runOnce() // TickOutcome
```

Need just the decision logic with no IO? Call the pure engine directly:
`DirectivePlanner.plan(heartbeat, handled): DirectivePlan` and
`TrackerTick.reduce(now, activeTracking, observed, reported): ReportPlan`.

**Observing the active-tracking count (Flow).** Non-JS hosts get the live count of actively-watched
trades as a Kotlin `StateFlow<Int>` via
[`ActiveTrackingCountChannel`](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/runtime/ActiveTrackingCountChannel.kt) —
an `EventObserver` decorator. Wire it in as the loop's `eventObserver` and collect `.count`:

```kotlin
val counter = ActiveTrackingCountChannel(delegate = myEventObserver) // delegate optional; still gets the full firehose
val loop = TradeTrackerCore().createLoop(config = …, eventObserver = counter, /* … */)
scope.launch { counter.count.collect { n -> /* render "Activity on DMarket: n" */ } }
// …or read the latest synchronously: counter.count.value
```

`count` is a `StateFlow<Int>` kept in sync from the loop's own cycle events (`CycleCompleted.watching`
every cycle, `HeartbeatSent.trackingCount` each heartbeat); it holds the current value (`0` before the
first cycle), replays it to new collectors, and conflates identical counts. On JS the same channel is
reached via the exported `subscribeActiveTrackingCount(handle, onCount)` / `activeTrackingCount(handle)`
(above).

### `LoopConfig`

[LoopConfig.kt](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/loop/LoopConfig.kt)

| Field | Meaning |
|---|---|
| `clientVersion` | Semver string reported by the client. |
| `surface` | `WebChrome` / `WebFirefox` / `IosNative` / `AndroidNative` — drives cadence floors. |
| `mode` | `Foreground` / `Background` — affects cadence floors. |
| `tunables` | A [`TrackerConfig`](#configuration-trackerconfig); defaults to `TrackerConfig.defaults()`. |

### Loop lifecycle

[TradeTrackerLoop.kt](core/src/commonMain/kotlin/com/dmarket/p2p/tracker/loop/TradeTrackerLoop.kt)

| Member | Use |
|---|---|
| `suspend fun runOnce(): TickOutcome` | One full self-contained cycle (heartbeat + directives + watch/report), so OS-driven platforms call it once per wake. |
| `fun start(scope): Job` | Continuous loop over scheduled wakes + pushes, from a single consumer — two cycles never run concurrently. With the default `NoOpPushChannel` this is poll-only. |
| `suspend fun wakeFromPush(signal): TickOutcome` | Direct entry for a one-shot OS push callback (APNs/FCM); a push is just a nudge that runs one cycle. |
| `fun nextWakeDelay(): Duration` | The single scheduling source of truth (deal-watch cadence or next heartbeat, whichever is sooner). |
| `val needsReLogin: Boolean` | `true` when the last background scrape found no Steam session. Clears on the next successful scrape. Signal-only — the user-facing block is `blockingState`/`STEAM_SESSION_MISSING`, which additionally requires the session cookie to be gone. |
| `val steamSessionMissing: Boolean` | `true` when there is no Steam web session at all (cookie deleted), so no credential can be acquired. Persisted, so it survives a service-worker respawn; clears on the first cycle that acquires a credential. |
| `val blockingState: TrackerBlock` | The single highest-priority reason the tracker is blocked, for a host to render exactly one prompt, most actionable first: `DM_SESSION_MISSING` > `STEAM_SESSION_MISSING` > `STEAM_ACCOUNT_MISMATCH` > `DM_CONNECTION_ERROR` > `NONE`. |
| `val needsMarketplaceReLogin: Boolean` | `true` when no DMarket session can be recovered silently — no token pair at all, a spent refresh token, or a refresh the server refused. A transient failure (5xx / 429 / a broken gateway) deliberately leaves it `false`. Always `false` if no `MarketplaceCredentialProvider` was wired in. |

### DMarket token refresh, and how a mobile host reuses its own

The DMarket bearer (`dm-trade-token`) lives ~24 h and is rotated against
`POST {base}/marketplace-api/v1/refresh-token` with `{"RefreshToken": "…"}`, which returns a **new pair**
(`AuthToken`/`RefreshToken` plus epoch-second expiries). Two things about that shape drive the design:

1. **The access token's expiry is inside the token, not on its container.** The DMarket web frontend gives
   *both* cookies the **refresh** token's ~30-day expiry, deliberately, so an idle user is not signed out. So
   anything that derives freshness from the cookie sees a fresh token for a month and never refreshes. The
   authority is the `exp` claim of the JWT (`MarketplaceTokenJwt`), and `MarketplaceTokenStore` therefore does
   not even report an access expiry.
2. **The server does not `Set-Cookie`** — the page writes both cookies from the response body. On web the token
   store *is* the browser cookie jar, shared with that page, so a refresh that does not write both halves back
   signs the user out of the website. Writing the pair back is part of the feature, not bookkeeping.

Everything above is implemented once, in `commonMain`, over two thin ports — so a platform supplies a token
store and gets the whole algorithm (single-flight, a rotation rate limit, a persisted refused-token latch, a
three-way compare-and-swap against the competing writer, and a total status policy in which **only** a 401 or
a token-less 200 may mean "sign in again").

**A host that already refreshes DMarket tokens must not use any of it.** Two authorities rotating one shared
credential is strictly worse than either alone. Such a host replaces `MarketplaceCredentialProvider` outright:

```kotlin
// Android: the app's own :token_manager already does storage, single-flight, rotation and 401 handling.
val marketplaceCredentials: MarketplaceCredentialProvider =
    HostTokenMarketplaceCredentialProvider { force ->
        // TokenException (no usable pair) → null + lastRefreshFailedLoggedOut, i.e. "show the sign-in prompt".
        val data = tokenManager.getTokenData(forceRefresh = force)
        MarketplaceCredential(
            token = data.authorizationToken,
            expiresAt = Instant.fromEpochSeconds(data.authorizationTokenExpirationTimestamp),
        )
    }

val marketplace = KtorMarketplaceClient(
    httpClient = androidMarketplaceClient(hostOkHttp),
    baseUrl = baseUrl,
    authenticator = CredentialMarketplaceAuthenticator(marketplaceCredentials),
)
```

`null` from the provider is load-bearing, not advisory: the loop's pre-heartbeat guard and the 401
authenticator are both null checks, so a provider that hands back a token it knows is dead turns one clean
missing-connection verdict into `1 + maxRetries` rejected requests on every wake.

**Audit boundary.** This client reads the durable `dm-trade-refresh-token` and sends it to DMarket's own
refresh endpoint — and nowhere else: the resolved endpoint is allow-listed to the API-base or site origin
before the request is built. That is a change from earlier versions, which refreshed by loading a
`dmarket.com` page in a background tab and never touched the refresh token. The Steam side is unchanged and
still never reads Steam's durable `steamRefresh_steam`. Both DMarket tokens, the refresh request body and the
response are in the redactor's named-secret set, so neither reaches a log line, an exception message or a
crash report.

### Ports you implement per platform

All interfaces live in
[domain/.../port/](domain/src/commonMain/kotlin/com/dmarket/p2p/tracker/port/). Each has a web actual
and a default; substitute platform implementations as you enable iOS/Android.

| Port | Purpose | Web actual / default |
|---|---|---|
| `Clock` | Current time (injected for deterministic tests). | `SystemClock` |
| `Scheduler` | Platform wake-up scheduling (`chrome.alarms` / WorkManager / BGTaskScheduler). | `WebExtAlarmsScheduler` (web) · `CoroutineScheduler` (tests/foreground) |
| `CredentialVault` | Device-local secure storage for the Steam credential. | `WebExtStorageCredentialVault` (default via `platformCredentialVault()`) · `InMemoryCredentialVault` (tests) |
| `MarketplaceClient` | The `/exchange/v1/p2p/ext/` endpoints (heartbeat / trade-events / notary / trade-actions / inventory) + the DMarket app conveniences (accept / getDeal). **Never accepts a `SteamCredential`** (audit boundary). | `KtorMarketplaceClient` |
| `DeviceIdStore` | Persistent install-scoped `device_id` — the directive-lease key sent in every heartbeat. | `WebExtStorageDeviceIdStore` (web) |
| `MarketplaceCredentialProvider` | The DMarket bearer, refreshed when it nears expiry. **The one seam a host that already owns token refresh replaces wholesale** — see below. | `DefaultMarketplaceCredentialProvider` (library-owned) · `HostTokenMarketplaceCredentialProvider` (host-owned) |
| `MarketplaceTokenStore` | Where the platform keeps the token pair. On web this is the browser cookie jar, **shared with the dmarket.com page**, which is why the rotated pair must be written back. | `FetchMarketplaceCookieTokenStore` |
| `MarketplaceTokenRefreshClient` | `POST {base}/marketplace-api/v1/refresh-token`. Unauthenticated, no 401 retry, on a transport of its own (the 401 path calls into it). | `KtorMarketplaceTokenRefreshClient` |
| `MarketplaceRefreshStateStore` | Refresh guards that must survive a process restart: the refused-token latch, the rotation rate limit, the transient-failure count. Stores a **fingerprint**, never the token. | `PersistedMarketplaceRefreshStateStore` |
| `SteamReadClient` | Read-only Steam status polling — both axes: `offerStatuses` (`ETradeOfferState`) + `recentTransfers` (`ETradeStatus`), account-wide batch reads. | `KtorSteamReadClient` |
| `SteamInventoryReader` | Read the seller's **own** inventory for the `report_inventory` directive → `POST /inventory` snapshot. Follows Steam's paging cursor and returns an `InventoryScan` carrying whether the enumeration was **complete**, so a truncated read reports `scan_complete=false` instead of looking like an empty inventory. | `KtorSteamInventoryReader` (web) / `NoOpSteamInventoryReader` |
| `SteamSessionScraper` | Re-acquire a fresh Steam credential from an already-logged-in session. | `KtorSteamSessionScraper` |
| `SteamOfferCreator` | **Create** a Steam trade for the buyer, stopping at `CreatedNeedsConfirmation` — never confirms. | `FetchSteamOfferCreator` / `NoOpSteamOfferCreator` |
| `SteamOfferCanceller` | **Cancel** a sent offer (the only other write surface). | `FetchSteamOfferCanceller` / `NoOpSteamOfferCanceller` |
| `SteamSessionRefresher` / `SteamWebSessionGateway` | Keep the `steamLoginSecure` web session alive in the background. | `DefaultSteamSessionRefresher` / `FetchSteamWebSessionGateway` · `NoOpSteamSessionRefresher` |
| `NotaryProver` | Generate a TLSN proof for a decisive transition on a `proof_required` deal (`POST /notary`). | `NoOpNotaryProver` (MVP stub presentation) |
| `PushChannel` | Backend→client push wake-up (transport-agnostic). | `NoOpPushChannel` (poll-only) |

---

## Testing

The whole point of the pure core is that the decision surface is testable with no test doubles:

- New logic lands in `:domain` first with **table-driven `commonTest`** cases (construct a
  `HeartbeatResponse`, call `DirectivePlanner.plan` / `TrackerTick.reduce`, assert on the plan).
- Every domain suite runs on **both JVM and JS Node** (`:domain:jvmTest` + `:domain:jsNodeTest`) —
  the same Kotlin, two backends.
- `:core` IO is exercised with `ktor-client-mock` round-trips and fake ports.
- Coverage gate: **≥70%** on `:domain` (`koverVerify`), currently ~90%+.
- `./gradlew check` runs everything (tests + `spotlessCheck` + `koverVerify`).
- If a test of pure logic needs a mock, the logic is in the wrong module.

## Current limitations (v1)

- **TLSN proofs** use a stub presentation (`NoOpNotaryProver`) against the backend's mock verify; the
  real WASM prover and production rollout are gated on DMarket security review. v1 runs
  client-reported mode until the backend flips per-trade `proof_required`.
- **Directive execution is gated** by `directivesEnabled` (default `false`) until the backend
  `device_id` Redis lease goes live; the create write surface is additionally pending DMarket
  security sign-off.
- **Push** is poll-only by default; the lib parses payloads but owns no push transport (host-delivered
  via `deliverPush`).
- **iOS / Android targets** are deferred (on request); the web extension is the shipping path.

## Docs

- [CONTRIBUTING.md](CONTRIBUTING.md) — module layout, conventions and the checks a change must pass.
- [tools/debug-extension/README.md](tools/debug-extension/README.md) — the dev-only Chrome debug harness.

## License

[MIT](LICENSE).

Vendored third-party components (the TLSN prover WASM under `vendor/tlsn/`, also redistributed inside
the npm package) are licensed separately — see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
