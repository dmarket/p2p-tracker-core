// Dashboard (options page) — a pure session-log viewer plus the essential settings (DMarket base URL +
// FE URL). It never loads the facade; it relays to the service worker via chrome.runtime.
// Create-trade is triggered by the real DMarket FE's window.postMessage, not from here.

import { baseUrlPresets } from "./settings.js";

const $ = (id) => document.getElementById(id);

const CUSTOM_PRESET = "__custom__";

// The loop self-drives on this chrome.alarms entry (mirrors WebExtAlarmsScheduler.DEFAULT_ALARM_NAME).
const ALARM_NAME = "dmarket_p2p_tracker_tick";

// A scrape source counts as "fresh" if its most recent successful (2xx) call landed within this
// window; older (or none yet) → "stale". Tune freely — it's a dev-only viewer heuristic.
const FRESH_WINDOW_MS = 120000; // 2 min

// Hosts whose successful reads count as a fresh Steam scrape (the DMarket hosts are derived from
// the live settings — API base + FE origin — in dmarketHosts()).
const STEAM_HOSTS = ["steamcommunity.com", "api.steampowered.com", "store.steampowered.com", "login.steampowered.com"];

// Committed presets + any settings.local.js extras; resolved once in init() before first render.
let presets = [];

// Populate the Env dropdown once (presets + a Custom sentinel). Picking a preset fills the still-
// editable base-URL field; Custom leaves whatever the user typed.
function initEnvPreset() {
  const sel = $("envPreset");
  sel.innerHTML = "";
  for (const p of presets) {
    sel.add(new Option(p.label, p.url));
  }
  sel.add(new Option("Custom", CUSTOM_PRESET));
  sel.addEventListener("change", () => {
    const p = presets.find((x) => x.url === sel.value);
    if (p) {
      $("dmarketBaseUrl").value = p.url;
      $("dmarketFeUrl").value = p.feUrl;
    }
  });
}

// Reflect the current API + FE fields in the dropdown: a preset only when BOTH match, else Custom.
function syncEnvPreset() {
  const base = $("dmarketBaseUrl").value.trim();
  const fe = $("dmarketFeUrl").value.trim();
  const match = presets.find((p) => p.url === base && p.feUrl === fe);
  $("envPreset").value = match ? match.url : CUSTOM_PRESET;
}

function send(msg) {
  return new Promise((resolve, reject) => {
    chrome.runtime.sendMessage(msg, (res) => {
      const err = chrome.runtime.lastError;
      if (err) reject(new Error(err.message));
      else resolve(res);
    });
  });
}

// ---- settings -----------------------------------------------------------------------------------

let settings = null;

async function loadAndRender() {
  const res = await send({ type: "describe" });
  settings = res.settings;
  $("version").textContent = `v${res.version}`;
  $("session").textContent = `session: ${res.hasSession ? "running" : "stopped"}`;
  $("session").className = `pill ${res.hasSession ? "on" : "off"}`;
  $("dmarketBaseUrl").value = settings.dmarketBaseUrl;
  $("dmarketFeUrl").value = settings.dmarketFeUrl;
  syncEnvPreset();
}

async function apply() {
  // Persist ONLY the fields this form owns. Writing back the whole merged snapshot would bake every
  // other resolved value into storage — including `revealSecrets` picked up from settings.local.js —
  // and a stored value outranks the base defaults, so removing it from that file would stop turning
  // redaction back on.
  const patch = {
    dmarketBaseUrl: $("dmarketBaseUrl").value.trim(),
    dmarketFeUrl: $("dmarketFeUrl").value.trim(),
  };
  const stored = (await chrome.storage.local.get("p2p_debug_settings")).p2p_debug_settings || {};
  await chrome.storage.local.set({ p2p_debug_settings: { ...stored, ...patch } });
  await send({ type: "settings-changed" });
  await loadAndRender();
  pushEntry({ seq: "·", ts: Date.now(), category: "command", note: "settings applied & session restarted" });
}

// ---- session log --------------------------------------------------------------------------------

const logEl = $("log");
let entries = [];

// Per-cycle loop-tick lifecycle events are not API calls and just bury the network entries — hide them
// from the viewer (the service worker's IndexedDB buffer still retains everything).
const HIDDEN_LIFECYCLE = new Set(["CycleStarted", "CycleCompleted", "HeartbeatSent"]);
const isHidden = (e) => e.category === "lifecycle" && HIDDEN_LIFECYCLE.has(e.event);

function renderEntry(e) {
  // Each entry is a collapsed <details> — the head is the always-visible summary; the body (decoded
  // network blocks or the JSON dump) stays hidden until the user expands it, so long bodies don't bury the list.
  const div = document.createElement("details");
  div.className = "entry";
  const head = document.createElement("summary");
  head.className = "head";
  const left = document.createElement("span");
  left.innerHTML = `<span class="seq">#${e.seq ?? "-"}</span> <span class="cat cat-${e.category}">${e.category}</span> ${summarize(e)}`;
  const right = document.createElement("span");
  right.className = "muted";
  right.textContent = e.ts ? new Date(e.ts).toLocaleTimeString() : "";
  head.append(left, right);
  div.appendChild(head);
  if (e.category === "network") {
    // Render the request as a copy-pasteable curl (method + URL + headers + body, plus cookies in a
    // reveal-secrets session); keep the response decoded separately so it stays legible at a glance.
    appendBlock(div, "curl", buildCurl(e), null, "curl");
    // Cookies sent with the call. Values live in the curl line when revealed; by default this is the
    // only place they show up at all, as name + length + fingerprint.
    appendBlock(div, "cookies", describeCookies(e.cookies), "resp");
    appendBlock(div, "response", decodeBody(e.responseBody), "resp", "json");
    if (e.error) appendBlock(div, "error", e.error, "err");
  } else {
    const pre = document.createElement("pre");
    pre.innerHTML = highlightJson(JSON.stringify(e, null, 2));
    div.appendChild(pre);
  }
  return div;
}

// ---- syntax highlighting (dependency-free; MV3 CSP forbids a remote highlight lib) ---------------

/** Escape the three HTML-significant chars before we build highlighted markup. Quotes are safe in text. */
const escapeHtml = (s) => String(s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");

/** Wrap JSON tokens (keys, strings, numbers, booleans, null) in coloured spans. Input is escaped first. */
function highlightJson(json) {
  return escapeHtml(json).replace(
    /("(?:\\.|[^"\\])*"(\s*:)?|\b(?:true|false)\b|\bnull\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)/g,
    (m) => {
      let cls = "tok-num";
      if (m[0] === '"') cls = /:\s*$/.test(m) ? "tok-key" : "tok-str";
      else if (m === "null") cls = "tok-null";
      else if (m === "true" || m === "false") cls = "tok-bool";
      return `<span class="${cls}">${m}</span>`;
    },
  );
}

/** Colour a curl command: the `curl` keyword, flags (-X/-H/-b/--data), and single-quoted arguments. */
function highlightCurl(text) {
  return escapeHtml(text)
    .replace(/^curl\b/, '<span class="tok-cmd">curl</span>')
    .replace(/(^|\s)(-X|-H|-b|--data(?:-raw)?)(?=\s)/g, (_m, sp, flag) => `${sp}<span class="tok-flag">${flag}</span>`)
    .replace(/'(?:\\.|[^'\\])*'/g, (m) => `<span class="tok-str">${m}</span>`);
}

/** Return highlighted HTML for a body, or null when the text isn't a highlightable shape. */
function highlight(text, lang) {
  if (lang === "curl") return highlightCurl(text);
  if (lang === "json") {
    const t = text.trimStart();
    if (t.startsWith("{") || t.startsWith("[")) return highlightJson(text);
  }
  return null;
}

/** Single-quote a value for a POSIX shell (wraps in '…', escaping embedded single quotes). */
const shq = (s) => `'${String(s).replace(/'/g, "'\\''")}'`;

/**
 * Reconstruct the request as a copy-pasteable curl command: method, URL, the request headers the
 * library captured, the cookies attached by the service worker from chrome.cookies (via `-b`), and the
 * raw body.
 *
 * **Replayable only in a reveal-secrets session.** By default the library redacts the Authorization
 * header and the service worker records cookie fingerprints rather than values, so the emitted command
 * carries no credential — copy it to inspect the shape, not to re-send it. Set `revealSecrets: true`
 * (see settings.js) when you actually need to replay a call.
 * Note: the browser adds the `Cookie` header at fetch time, so it is never in `e.headers`; and only
 * cookies scoped to the request host are sent.
 */
/**
 * One line per cookie the service worker attached: name, value length and fingerprint (the same
 * FNV-1a the library prints, so "same value here and there?" stays answerable), and `= <value>` only
 * when the session reveals secrets. Null when the entry carried no cookies, so the block is skipped.
 */
function describeCookies(cookies) {
  if (!cookies || !cookies.length) return null;
  return cookies
    .map((c) => {
      const flags = [c.httpOnly ? "httpOnly" : null, c.secure ? "secure" : null].filter(Boolean).join(" ");
      const shown = c.value != null ? ` = ${c.value}` : "";
      return `${c.name}${shown}  (len ${c.valueLength ?? "?"}, fp ${c.valueFingerprint ?? "?"}${flags ? ", " + flags : ""})`;
    })
    .join("\n");
}

function buildCurl(e) {
  const parts = [`curl -X ${e.method} ${shq(e.url)}`];
  for (const [k, v] of Object.entries(e.headers || {})) {
    parts.push(`-H ${shq(`${k}: ${v}`)}`);
  }
  const withValues = (e.cookies || []).filter((c) => c.value != null);
  if (withValues.length) {
    parts.push(`-b ${shq(withValues.map((c) => `${c.name}=${c.value}`).join("; "))}`);
  }
  if (e.requestBody != null && e.requestBody !== "") {
    parts.push(`--data ${shq(e.requestBody)}`);
  }
  return parts.join(" \\\n  ");
}

/** Pretty-print a JSON body, decode an x-www-form-urlencoded body, else return as-is. Null if empty. */
function decodeBody(raw) {
  if (raw == null || raw === "") return null;
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch (_) {
    /* not JSON */
  }
  if (raw.includes("=") && !raw.includes("\n")) {
    try {
      const pairs = [...new URLSearchParams(raw).entries()];
      if (pairs.length) return pairs.map(([k, v]) => `${k} = ${v}`).join("\n");
    } catch (_) {
      /* not form-encoded */
    }
  }
  return raw;
}

/**
 * Append a labelled `<pre>` block; skips entirely when text is null. `variant` tints the label; `lang`
 * ("json" | "curl") applies syntax highlighting (falls back to plain text when it isn't highlightable).
 */
function appendBlock(parent, label, text, variant, lang) {
  const lbl = document.createElement("div");
  lbl.className = `blocklabel${variant ? ` ${variant}` : ""}`;
  lbl.textContent = label;
  parent.appendChild(lbl);
  if (text != null) {
    const pre = document.createElement("pre");
    const html = lang ? highlight(text, lang) : null;
    if (html != null) pre.innerHTML = html;
    else pre.textContent = text;
    parent.appendChild(pre);
  }
}

const truncate = (s) => (s && s.length > 90 ? `${s.slice(0, 90)}…` : s || "");

function summarize(e) {
  switch (e.category) {
    case "network":
      return `${e.method} ${e.origin} ${e.status ?? ""} — ${truncate(e.url)}`;
    case "lifecycle":
      return e.event || "";
    case "command":
      return e.event || e.note || "";
    default:
      return "";
  }
}

// A new tick cycle begins at whichever comes first: a `RequestCycle` command (force tick / FE fallback)
// or the heartbeat POST (alarm-driven cycle). A visual divider before that entry separates one cycle's
// log run from the next.
const isTickStart = (e) =>
  (e.category === "command" && e.event === "RequestCycle") ||
  (e.category === "network" && !!e.url && /\/ext\/heartbeat(\?|#|$)/.test(e.url));

// A force tick logs a RequestCycle AND then heartbeats — collapse the two into a single divider by
// suppressing a tick-start that immediately follows another (no non-tick entry rendered between them).
let justMarkedTick = false;

function renderDivider(e) {
  const d = document.createElement("div");
  d.className = "tickdiv";
  const label = document.createElement("span");
  label.textContent = `new tick${e.ts ? ` · ${new Date(e.ts).toLocaleTimeString()}` : ""}`;
  d.appendChild(label);
  return d;
}

/** Append an entry to the log DOM, prefixed by a tick divider when it starts a new cycle. */
function appendRendered(e) {
  if (isTickStart(e)) {
    if (!justMarkedTick) logEl.appendChild(renderDivider(e));
    justMarkedTick = true;
  } else {
    justMarkedTick = false;
  }
  logEl.appendChild(renderEntry(e));
}

function pushEntry(entry) {
  recordSignal(entry); // session health is tracked outside `entries` so it survives a log clear
  if (isHidden(entry)) return;
  entries.push(entry);
  appendRendered(entry);
  $("logCount").textContent = String(entries.length);
  if ($("autoscroll").checked) $("out").scrollTop = $("out").scrollHeight;
}

async function refreshLog() {
  const res = await send({ type: "get-log" });
  entries = (res.entries || []).filter((e) => !isHidden(e));
  logEl.innerHTML = "";
  justMarkedTick = false;
  for (const e of entries) {
    recordSignal(e);
    appendRendered(e);
  }
  $("logCount").textContent = String(entries.length);
}

function exportLog() {
  const blob = new Blob([JSON.stringify(entries, null, 2)], { type: "application/json" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `p2p-session-log-${Date.now()}.json`;
  a.click();
  URL.revokeObjectURL(url);
}

// ---- scrape status ------------------------------------------------------------------------------
// Two traffic-light indicators (dmarket / steam) combine availability (auth-cookie presence, probed
// in the SW via chrome.cookies) with observed session health:
//   green  = logged in + a successful scrape within the window;
//   orange = logged in but stale, OR a 401/403 was caught (the client re-mints the session + retries);
//   red    = logged out (no cookie) or the loop signalled a re-login is required.
// The client itself refreshes on a stale credential and on 401/403 (SteamCredentialProvider /
// RefreshingSteamReadClient / CredentialMarketplaceAuthenticator) and re-mints the web session; these
// indicators only reflect the outcome. A later success supersedes an earlier failure by timestamp, so
// a self-healed 401 flips back to green on its own.

const hostOf = (u) => {
  try {
    return new URL(u).host;
  } catch (_) {
    return null;
  }
};

// Hosts whose successful calls count as a fresh DMarket scrape: the API base + the FE (token) origin.
function dmarketHosts() {
  return [hostOf(settings?.dmarketBaseUrl), hostOf(settings?.dmarketFeUrl)].filter(Boolean);
}

// Session health is tracked HERE, independent of the (clearable) display log — so "clear" empties the
// viewer without fabricating staleness. Per source we keep the newest timestamp of each signal:
//   good    = a successful (2xx) call to the source;
//   bad     = a 401/403 (the auth rejection the client re-mints + retries on);
//   reLogin = the loop's ReLoginNeeded lifecycle event (steam only; marketplace re-login is a polled
//             flag the loop doesn't emit, so the dmarket red state keys on the cookie / repeated 401).
// Fed by every entry (streamed + the initial load); the clear button leaves these intact.
const sig = {
  dmarket: { good: null, bad: null, reLogin: null },
  steam: { good: null, bad: null, reLogin: null },
};

// Which indicator source a network URL belongs to, by host.
function networkSource(url) {
  const h = hostOf(url);
  if (!h) return null;
  if (STEAM_HOSTS.includes(h)) return "steam";
  if (dmarketHosts().includes(h)) return "dmarket";
  return null;
}

// Map a lifecycle-event axis ("steam"/"marketplace") to our indicator source ("steam"/"dmarket").
const axisSource = (axis) => (axis === "steam" ? "steam" : axis === "marketplace" ? "dmarket" : null);

// Fold one log entry into the health signals. Monotonic per key (keeps the latest).
function recordSignal(e) {
  if (!e || !e.ts) return;
  if (e.category === "network" && e.url && typeof e.status === "number") {
    const src = networkSource(e.url);
    if (!src) return;
    if (e.status >= 200 && e.status < 300) sig[src].good = Math.max(sig[src].good ?? 0, e.ts);
    else if (e.status === 401 || e.status === 403) sig[src].bad = Math.max(sig[src].bad ?? 0, e.ts);
  } else if (e.category === "lifecycle" && e.event === "ReLoginNeeded") {
    const src = axisSource(e.axis);
    if (src) sig[src].reLogin = Math.max(sig[src].reLogin ?? 0, e.ts);
  }
}

// Combine cookie availability (present) with the observed signals into a traffic-light state.
function scrapeState(present, s, now) {
  const { good, bad, reLogin } = s;
  const recovered = good != null && (reLogin == null || good >= reLogin);
  if (reLogin != null && !recovered) return "red"; // loop asked for re-login, nothing succeeded since
  if (!present) return "red"; // no auth cookie → logged out
  if (bad != null && (good == null || bad > good)) return "orange"; // 401/403 newer than last success
  if (good != null && now - good <= FRESH_WINDOW_MS) return "green"; // recently scraped
  return "orange"; // logged in but no recent successful scrape
}

const ago = (ms) => {
  const sec = Math.round(ms / 1000);
  return sec < 60 ? `${sec}s ago` : `${Math.round(sec / 60)}m ago`;
};

function scrapeDetail(present, s, now, state) {
  if (state === "red") return present ? "re-login required" : "logged out";
  if (state === "green") return `logged in · last scrape ${ago(now - s.good)}`;
  if (s.bad != null && (s.good == null || s.bad > s.good)) return "auth error (401/403) · refreshing";
  return s.good == null ? "logged in · no recent scrape" : `logged in · stale · last scrape ${ago(now - s.good)}`;
}

function applyIndicator(id, label, present, s) {
  const el = $(id);
  if (!el) return;
  const now = Date.now();
  const state = scrapeState(present, s, now);
  el.className = `pill scrape ${state}`;
  el.title = `${label} scrape: ${state} — ${scrapeDetail(present, s, now, state)}`;
}

async function refreshScrapeStatus() {
  const res = await send({ type: "scrape-status" });
  if (!res || !res.ok) return;
  applyIndicator("dmarketScrape", "dmarket", res.dmarket?.present, sig.dmarket);
  applyIndicator("steamScrape", "steam", res.steam?.present, sig.steam);
}

// ---- tick control -------------------------------------------------------------------------------

// Reflect the real time until the loop's next self-tick by reading its chrome.alarms entry. The
// dashboard is a long-lived options page, so a 1s setInterval is fine (unlike the killable SW).
function pollCountdown() {
  chrome.alarms.get(ALARM_NAME, (a) => {
    if (chrome.runtime.lastError || !a || !a.scheduledTime) {
      $("tickCountdown").textContent = "next tick: —";
      return;
    }
    const secs = Math.max(0, Math.round((a.scheduledTime - Date.now()) / 1000));
    $("tickCountdown").textContent = `next tick: ${secs}s`;
  });
}

// Force one loop cycle now, ignoring the countdown. Reuses the SW's request-cycle → harness.nudge()
// path; the countdown resets on the next poll once the cycle reschedules the alarm.
async function forceTick() {
  await send({ type: "request-cycle" });
  pollCountdown();
}

// Live entries broadcast by the service worker.
chrome.runtime.onMessage.addListener((msg) => {
  if (msg?.type === "log-entry") {
    pushEntry(msg.entry);
    // A network call (2xx/401/403) or a ReLoginNeeded event can flip a scrape indicator — refresh on
    // those; the interval below covers steady liveness + the age counting down for everything else.
    const en = msg.entry;
    if (en?.category === "network" || (en?.category === "lifecycle" && en.event === "ReLoginNeeded")) {
      refreshScrapeStatus().catch(() => {});
    }
  }
});

// ---- wiring -------------------------------------------------------------------------------------

$("dmarketBaseUrl").addEventListener("input", syncEnvPreset);
$("dmarketFeUrl").addEventListener("input", syncEnvPreset);
$("apply").addEventListener("click", () => apply().catch((e) => pushEntry({ seq: "·", ts: Date.now(), category: "error", error: e.message })));
$("forceTick").addEventListener("click", () => forceTick().catch((e) => pushEntry({ seq: "·", ts: Date.now(), category: "error", error: e.message })));
$("exportLog").addEventListener("click", exportLog);
$("clearLog").addEventListener("click", async () => {
  await send({ type: "clear-log" });
  entries = [];
  logEl.innerHTML = "";
  justMarkedTick = false;
  $("logCount").textContent = "0";
});

(async function init() {
  presets = await baseUrlPresets();
  initEnvPreset();
  await loadAndRender();
  await refreshLog();
  pollCountdown();
  setInterval(pollCountdown, 1000);
  refreshScrapeStatus().catch(() => {});
  setInterval(() => refreshScrapeStatus().catch(() => {}), 2000);
})();
