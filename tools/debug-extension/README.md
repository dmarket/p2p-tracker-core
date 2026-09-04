# P2P Tracker — Integration-Test Console

A **dev-only** Chrome MV3 extension that runs the P2P trade-tracker core as an **integration test**
against a **real DMarket frontend + real DMarket backend + LIVE Steam**. On boot it auto-starts the
self-driving tracker (heartbeat → directives → watch + report) and logs every network request and
lifecycle event. It is a **pure log viewer** — there are no manual action buttons; the trade-creation
trigger comes from the real DMarket FE.

> ⚠️ **Live Steam creates real offers.** A `create` POSTs a real trade offer to `steamcommunity.com`.
> It **stops at `NeedsConfirmation`** (the port cannot confirm — nothing transfers until you confirm in
> the mobile Steam app), but it is a real pending offer to a real partner. Use **test accounts** and
> real `asset_ids`.

## Build & load

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)     # Gradle needs JDK 17–21 (Android Studio JBR 21 works)
./gradlew :debug-harness:assembleDebugExtension       # builds vendor/harness.bundle.mjs
```

`chrome://extensions` → Developer mode → **Load unpacked** → this folder. After Kotlin changes, re-run
the task and **Reload** the card.

## Setup for a run

1. Be **logged into the real DMarket** (env under test) **and real Steam** in the same Chrome
   profile. The tracker scrapes your real `steamId` so the real backend's
   `steam_id == account.linked_steam_id` check passes.
2. Open the console (toolbar icon). Set **DMarket base URL** to the environment under test.
   *Apply & restart*.

## Testing against a non-public environment

The committed configuration knows only the public production hosts. To point the console at an
internal / staging environment (whose hostnames must not be committed), add two **gitignored** local
files next to this README:

1. **`settings.local.js`** — extra Env presets and default overrides, picked up automatically by
   `settings.js` (dynamic import; absence is fine):

   ```js
   export const EXTRA_PRESETS = [
     { label: "Dev", url: "https://<api-host>", feUrl: "https://<fe-host>/" },
   ];
   export const DEFAULT_OVERRIDES = {
     dmarketBaseUrl: "https://<api-host>",
     dmarketFeUrl: "https://<fe-host>/",
     allowedOrigins: ["https://dmarket.com", "https://www.dmarket.com", "https://<fe-host>"],
   };
   ```

2. **`manifest.json` host entries** — MV3 manifests are static, so the extension can only reach
   hosts listed there. Add your API + FE hosts to `host_permissions` and the FE host to
   `content_scripts[0].matches` **locally, without committing** (keep the full dev variant in a
   gitignored `manifest.local.json` and copy it over `manifest.json` while testing; restore with
   `git checkout -- manifest.json` before committing).

## Triggering a trade (FE → Steam)

The real DMarket FE drives creation by posting a `CreateTrade` message; the content script relays it and
the plugin POSTs the offer to live Steam. Until the FE emits it, simulate from the **dmarket.com DevTools
console**:

```js
window.postMessage({ source: "dmarket-fe",
  command: {
    type: "CreateTrade",
    directive_id: "<create_offer directive id from the BE>",  // FE gets this from its BE deal call
    deal_id: "<DMarket deal id>",  // the deal key (NOT the Steam offer id) — /trade-actions requires it
    partner_steam_id: "765…", asset_ids: ["<assetId>"], trade_token: "<token>",
  } },
  location.origin);
```

The FE hands the extension the backend's `create_offer` **`directive_id`** (obtained from its own BE
deal call) plus the **`deal_id`** so the extension can register the outcome without waiting for a
heartbeat. After creating the offer the extension reports `{directive_id, deal_id, steam_offer_id,
status}` to the BE on `POST /trade-actions` itself — the BE rejects the report without `deal_id`
(`"deal_id is required"`), which would leave the directive lease held until its TTL expires. (The heartbeat-directive flow stays as the fallback for non-FE-driven inits, e.g. mobile.)

After the plugin POSTs the offer, the extension posts the result **back to the page** so the FE can
show the outcome and correlate the `steam_offer_id` on its side (Steam only mints the id on this
POST, so the FE's parallel "create clicked" call can't carry it; the BE registration itself already
happened via the plugin's own `POST /trade-actions` report above):

```js
// The FE listens for this on window:
// { source: "dmarket-ext", type: "TradeCreated", ok: true, status: "needs_confirmation", steamOfferId: "…" }
window.addEventListener("message", (e) => {
  if (e.source === window && e.data?.source === "dmarket-ext" && e.data.type === "TradeCreated") {
    console.log("offer created:", e.data.steamOfferId, e.data.status); // → FE registers it with the BE
  }
});
```

The offer parks at `NeedsConfirmation`. Confirm the offer in the **mobile Steam app**; the real partner
accepts in Steam. The watch loop then reports the real state transitions to `/trade-events`.

## The session log

Every HTTP exchange (full URL, method, headers, query, body) and every loop lifecycle event is
persisted in an IndexedDB ring buffer and streamed live to the viewer. Export dumps it to JSON; clear
empties it. Real DMarket heartbeats/reports and Steam reads/writes all appear here, tagged
`network`/`lifecycle`/`command`.

**Redaction is the default, in two layers, which is what makes an exported log safe to share:** the
library redacts bodies, URLs and headers before an entry reaches the log, and the service worker
records cookie **metadata** — name, length, fingerprint — instead of cookie values. Both layers matter:
`steamLoginSecure` embeds the Steam access token and `dm-trade-token` *is* the DMarket bearer, so
recording their values would undo the library's redaction one layer above it.

If you are chasing a credential problem the redacted view cannot explain, set `revealSecrets: true` —
via `DEFAULT_OVERRIDES` in your gitignored `settings.local.js`, or a stored settings patch. The service
worker restarts the session and captures everything verbatim: bodies, URLs, headers and cookie values.
**That export must not leave your machine.** The one-shot diagnostic probes follow the same rule:
`createDebugSession(...)` prints token fingerprints and JWT claims,
`createDebugSessionRevealingSecrets(...)` prints the tokens themselves.

Copy-as-cURL follows from this: by default the command carries no credential (redacted Authorization,
no cookie values), so it is for inspecting the request shape — replaying it needs a reveal-secrets
session.

## Conformance probes

`DebugSession` also exposes one probe per report/write request — `reportTradeStatus`,
`reportTradeStatusBatch`, `reportDirective`, `reportInventory`, `submitProof` — each issuing a single
request through the real client, with no deal state to arrange first. Pass exact wire names: an
unrecognised `action`/`status`/`source` comes back as `{ok:false, error}` naming the accepted set
rather than being silently replaced with a default. See the conformance section of the root
[README.md](../../README.md).

## Architecture

```
dmarket.com page (real FE) ──window.postMessage(CreateTrade)──▶ content.js ──relay──▶ service worker
                                                                                          │ createTradeInSession
real DMarket backend ◀── heartbeat / trade-events / trade-actions ── self-driving loop ◀──┤
live Steam           ◀── reads / tradeoffer writes ────────────────────────────────────────┘
```

The observability (`NetworkObserver`/`EventObserver`) lives in `:core`/`:domain` — environment-agnostic,
reusable on Android/iOS.
