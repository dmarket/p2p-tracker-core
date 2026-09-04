// MV3 service worker — the orchestrator. Boots the self-driving integration-test session on every
// (re)spawn, fans the library's session-log entries into IndexedDB + the dashboard, and routes the
// FE create-trade command (relayed by the dmarket.com content script) into the library.
//
// MV3 lifecycle: the worker is killable and respawned on events; the library's own chrome.alarms
// listener (inside the session) must be re-registered synchronously on each spawn, so we start the
// session at top level with DEFAULTS first, then asynchronously reconcile to stored settings.

import { DEFAULTS, loadSettings } from "./settings.js";
import * as harness from "./harness.js";
import { appendLog, clearLogs, readAllLogs } from "./sessionLog.js";

let settings = DEFAULTS; // synchronously-available best guess; reconciled below.

// ---- session log fan-out ------------------------------------------------------------------------

function onLog(entryJson) {
  let entry;
  try {
    entry = JSON.parse(entryJson);
  } catch (_) {
    entry = { category: "raw", raw: entryJson };
  }
  enrichWithCookies(entry)
    .then((e) => appendLog(e, settings.logging.maxEntries, Date.now()))
    .then((stored) => broadcast({ type: "log-entry", entry: stored }))
    .catch(() => {});
}

const FNV64_OFFSET = 0xcbf29ce484222325n;
const FNV64_PRIME = 0x100000001b3n;
const FNV64_MASK = 0xffffffffffffffffn;

/**
 * The same FNV-1a/64 the library's `SecretRedaction` prints, so a cookie fingerprint here and a token
 * fingerprint in a library entry are directly comparable. Non-cryptographic by design: it answers
 * "did this change?" / "is it the same value in both places?" for high-entropy material.
 */
function fingerprint(value) {
  let hash = FNV64_OFFSET;
  for (const byte of new TextEncoder().encode(value)) {
    hash = ((hash ^ BigInt(byte)) * FNV64_PRIME) & FNV64_MASK;
  }
  return hash.toString(16).padStart(16, "0");
}

/**
 * Attach to a network-call entry metadata about the cookies actually sent with that request — i.e.
 * those matching its URL (`chrome.cookies.getAll({url})` scopes by domain/path/secure, so it returns
 * only the cookies used for that call). No-op for non-network entries. Read via chrome.cookies (works
 * for HttpOnly).
 *
 * **Values are not recorded unless the session runs with `revealSecrets`.** The log is persisted and
 * exportable, and these cookies are live credentials: `steamLoginSecure` embeds the Steam access
 * token, `dm-trade-token` IS the DMarket bearer (its prod `Domain=.dmarket.com` also puts it on
 * `api.dmarket.com` calls). Each entry carries `valueLength` + `valueFingerprint` instead, which is
 * what the cookie probes actually need, and keeps the library's redaction meaningful end-to-end
 * rather than undone one layer above it.
 */
async function enrichWithCookies(entry) {
  if (entry.category !== "network" || !entry.url) return entry;
  try {
    const cookies = await chrome.cookies.getAll({ url: entry.url });
    entry.cookies = cookies.map((c) => ({
      name: c.name,
      httpOnly: c.httpOnly,
      secure: c.secure,
      expiresAt: c.expirationDate ? c.expirationDate * 1000 : null,
      valueLength: c.value.length,
      valueFingerprint: fingerprint(c.value),
      ...(settings.revealSecrets ? { value: c.value } : {}),
    }));
  } catch (_) {
    /* no host permission / read failure */
  }
  return entry;
}

/**
 * Availability probe for a scrape source's auth cookie: present AND unexpired. Feeds the dashboard's
 * scrape-status indicators (the freshness axis is computed dashboard-side from the session log).
 * HttpOnly cookies (steamLoginSecure / dm-trade-token) are readable here — the `cookies` permission
 * plus the manifest host access cover both the Steam and DMarket FE origins.
 */
async function getCookieState(url, name) {
  try {
    if (!url) return { present: false, expiresAt: null };
    const c = await chrome.cookies.get({ url, name });
    if (!c) return { present: false, expiresAt: null };
    const expiresAt = c.expirationDate ? c.expirationDate * 1000 : null;
    const present = expiresAt == null || expiresAt > Date.now();
    return { present, expiresAt };
  } catch (_) {
    return { present: false, expiresAt: null };
  }
}

/** Best-effort broadcast to any open dashboard (ignored if none is listening). */
function broadcast(msg) {
  try {
    chrome.runtime.sendMessage(msg, () => void chrome.runtime.lastError);
  } catch (_) {
    /* no receiver */
  }
}

// ---- session lifecycle --------------------------------------------------------------------------

function bootSession(s) {
  harness.startSession({ baseUrl: s.dmarketBaseUrl, feUrl: s.dmarketFeUrl, onLog, revealSecrets: s.revealSecrets });
}

function wiringChanged(a, b) {
  return (
    a.dmarketBaseUrl !== b.dmarketBaseUrl ||
    a.dmarketFeUrl !== b.dmarketFeUrl ||
    // Redaction is decided when the library builds its observer, so a change only takes effect on a
    // fresh session.
    a.revealSecrets !== b.revealSecrets
  );
}

// Register UI/message listeners FIRST — MV3 requires them attached synchronously on every spawn, and
// hoisting them above bootSession() guarantees the toolbar icon (left-click) keeps opening the
// dashboard even if the session boot below throws (e.g. the Kotlin facade fails to start).
chrome.action.onClicked.addListener(() => {
  chrome.tabs.create({ url: chrome.runtime.getURL("dashboard.html") });
});

// Synchronous boot (DEFAULTS) so the library's alarm listener attaches on every spawn. Guarded: a
// boot failure must not abort the SW top-level and unregister the listeners above.
if (DEFAULTS.autoStart) {
  try {
    bootSession(DEFAULTS);
  } catch (e) {
    console.error("[p2p-debug] boot failed", e);
  }
}

// Reconcile to stored overrides; restart only if the DMarket wiring actually differs.
loadSettings().then((s) => {
  settings = s;
  if (!s.autoStart) {
    harness.stopSession();
  } else if (wiringChanged(s, DEFAULTS)) {
    bootSession(s);
  }
}).catch((e) => console.error("[p2p-debug] settings reconcile failed", e));

// ---- Steam anti-CSRF header rewrites (STANDING) -------------------------------------------------
//
// The per-domain `login/settoken` (session re-mint) and `tradeoffer/{id}/cancel` (cancel) enforce an
// anti-CSRF check that a service-worker fetch can't satisfy on its own: its `Origin` is
// `chrome-extension://…` and `Referer` is a forbidden fetch header. Without a first-party
// `Origin`/`Referer`, Steam replies `403`.
//
// These fire from the library's **own alarm loop** (autonomous session refresh, a leased `cancel_offer`
// directive) — NOT from a message handler — so the rewrites are STANDING session rules installed at boot.
// A **static** Referer is sufficient for these: settoken/cancel carry no per-trade `partner`. (settoken
// needs a per-domain Origin/Referer because `steamLoginSecure` is audience-scoped per domain.)
//
// `tradeoffer/new/send` (create) is DELIBERATELY NOT here. Steam validates the create Referer's
// `partner` against the POST body, so a partner-less static Referer 403s — its rewrite must be
// per-trade. FetchSteamOfferCreator installs that rule (session-rule id 1) around each send itself and
// tears it down after, covering both FE-message and directive-driven creates. Do NOT re-add a standing
// create rule here (it would collide on id 1 and reinstate the partner-less-Referer regression).

const STEAM_ANTICSRF_RULE_IDS = { settokenCommunity: 2, settokenStore: 3, cancel: 4 };

const STEAM_ANTICSRF_RULES = [
  {
    // Directive-driven `cancel_offer` (FetchSteamOfferCanceller → POST tradeoffer/{id}/cancel) fires from
    // the same alarm loop and hits the same anti-CSRF gate — cover it with a standing rule.
    id: STEAM_ANTICSRF_RULE_IDS.cancel,
    priority: 1,
    action: {
      type: "modifyHeaders",
      requestHeaders: [
        { header: "referer", operation: "set", value: "https://steamcommunity.com/" },
        { header: "origin", operation: "set", value: "https://steamcommunity.com" },
      ],
    },
    condition: { urlFilter: "||steamcommunity.com/tradeoffer/*/cancel", resourceTypes: ["xmlhttprequest", "other"] },
  },
  {
    id: STEAM_ANTICSRF_RULE_IDS.settokenCommunity,
    priority: 1,
    action: {
      type: "modifyHeaders",
      requestHeaders: [
        { header: "referer", operation: "set", value: "https://steamcommunity.com/" },
        { header: "origin", operation: "set", value: "https://steamcommunity.com" },
      ],
    },
    condition: { urlFilter: "||steamcommunity.com/login/settoken", resourceTypes: ["xmlhttprequest", "other"] },
  },
  {
    id: STEAM_ANTICSRF_RULE_IDS.settokenStore,
    priority: 1,
    action: {
      type: "modifyHeaders",
      requestHeaders: [
        { header: "referer", operation: "set", value: "https://store.steampowered.com/" },
        { header: "origin", operation: "set", value: "https://store.steampowered.com" },
      ],
    },
    condition: { urlFilter: "||store.steampowered.com/login/settoken", resourceTypes: ["xmlhttprequest", "other"] },
  },
];

/** Install the standing create + settoken anti-CSRF header rewrites (idempotent). Best-effort at boot. */
async function installSteamAntiCsrfHeaderRules() {
  try {
    await chrome.declarativeNetRequest.updateSessionRules({
      removeRuleIds: Object.values(STEAM_ANTICSRF_RULE_IDS),
      addRules: STEAM_ANTICSRF_RULES,
    });
  } catch (e) {
    console.error("[p2p-debug] anti-CSRF header rules failed", e);
  }
}

// Install the standing anti-CSRF header rewrites (settoken re-mint + cancel) so the autonomous loop can
// re-mint steamLoginSecure and cancel offers (fire-and-forget; the boot above doesn't depend on it).
// The create rewrite is per-trade and installed by FetchSteamOfferCreator itself, not standing here.
// (Runs after the STEAM_ANTICSRF_* consts are initialized — calling it earlier would hit their TDZ.)
installSteamAntiCsrfHeaderRules();

// ---- message router -----------------------------------------------------------------------------

chrome.runtime.onMessage.addListener((msg, _sender, sendResponse) => {
  if (!msg || typeof msg.type !== "string") return undefined;
  (async () => {
    try {
      sendResponse(await handle(msg));
    } catch (e) {
      sendResponse({ ok: false, error: String((e && e.message) || e) });
    }
  })();
  return true; // async response
});

async function handle(msg) {
  switch (msg.type) {
    case "describe":
      return { ok: true, version: harness.version(), hasSession: harness.hasSession(), settings };

    case "settings-changed": {
      const prev = settings;
      settings = await loadSettings();
      if (!settings.autoStart) {
        harness.stopSession();
      } else if (wiringChanged(prev, settings) || !harness.hasSession()) {
        bootSession(settings);
      }
      return { ok: true, settings };
    }

    // FE "create trade" command relayed by the dmarket.com content script.
    case "create-trade": {
      // The `tradeoffer/new/send` anti-CSRF Origin/Referer rewrite is a STANDING rule installed at boot
      // (see installSteamAntiCsrfHeaderRules) — it covers this message path AND directive-driven creates
      // from the library's alarm loop, so no per-create install/teardown is needed here.
      const result = await harness.createTrade({
        directiveId: msg.directiveId,
        dealId: msg.dealId,
        partnerSteamId: msg.partnerSteamId,
        assetIds: msg.assetIds,
        tradeToken: msg.tradeToken,
        linkedSteamId: msg.linkedSteamId,
      });
      onLog(
        JSON.stringify({
          category: "command",
          event: "CreateTrade",
          directiveId: msg.directiveId,
          dealId: msg.dealId,
          partnerSteamId: msg.partnerSteamId,
          linkedSteamId: msg.linkedSteamId,
          result,
        }),
      );
      return { ok: true, result };
    }

    // CreateTrade with no directive_id → fall back to the regular heartbeat/directive flow. Also the
    // dashboard's "force tick" button.
    case "request-cycle": {
      harness.nudge();
      onLog(JSON.stringify({ category: "command", event: "RequestCycle", reason: "no directive_id → regular heartbeats" }));
      return { ok: true };
    }

    // Availability of the two scrape sources' auth cookies for the dashboard's scrape-status
    // indicators. Freshness (recency of the last successful call) is derived dashboard-side from
    // the session log, so this stays a thin cookie probe.
    case "scrape-status": {
      const [steam, dmarket] = await Promise.all([
        getCookieState("https://steamcommunity.com/", "steamLoginSecure"),
        getCookieState(settings.dmarketFeUrl, "dm-trade-token"),
      ]);
      return { ok: true, steam, dmarket };
    }

    case "get-log":
      return { ok: true, entries: await readAllLogs() };

    case "clear-log":
      await clearLogs();
      return { ok: true };

    default:
      return { ok: false, error: `unknown message type: ${msg.type}` };
  }
}
