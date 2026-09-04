// Single source of truth for the integration-test configuration.
//
// Integration test against a REAL DMarket frontend + REAL backend + LIVE Steam. Zero-config: when
// nothing is stored, loadSettings() returns DEFAULTS and the service worker boots a working session on
// install. Overrides persist in storage.
//
// Internal / non-public environments live in an OPTIONAL, gitignored `settings.local.js` next to
// this file (see README). It is loaded via dynamic import so a fresh clone works without it, and so
// module evaluation stays synchronous — the MV3 module service worker must register its listeners in
// the first event-loop turn, so no top-level await may enter the import graph.

export const SETTINGS_KEY = "p2p_debug_settings";

// Selectable DMarket environments, surfaced as the dashboard's Env dropdown. `url` is the API base
// (all /exchange/v1/p2p/ext/ calls); `feUrl` is the FE origin the `dm-trade-token` auth cookie is read
// + refreshed from (can be a DIFFERENT host than the API). Both fields stay editable, so a URL that
// matches none of these presets is shown as "Custom". `settings.local.js` appends extra presets.
const BASE_URL_PRESETS = [
  { label: "Prod", url: "https://api.dmarket.com", feUrl: "https://dmarket.com/" },
];

export const DEFAULTS = {
  // Auto-start the self-driving session on service-worker boot.
  autoStart: true,
  // REAL DMarket gateway origin (settings.local.js typically repoints this at the env under test).
  dmarketBaseUrl: "https://api.dmarket.com",
  // FE origin the `dm-trade-token` auth cookie is read + refreshed from.
  dmarketFeUrl: "https://dmarket.com/",
  // Origins the content script accepts FE `window.postMessage` create-trade commands from (exact match).
  allowedOrigins: ["https://dmarket.com", "https://www.dmarket.com"],
  logging: {
    // Max session-log entries retained in IndexedDB (ring buffer).
    maxEntries: 1000,
  },
  // Capture request/response bodies, URLs, headers and cookie VALUES verbatim in the session log.
  // Default `false` keeps the audited redaction, which is what makes an exported log safe to share.
  // Local diagnosis only: a reveal-secrets export holds live Steam/DMarket credentials and must not
  // leave the machine. Flip it via `settings.local.js` DEFAULT_OVERRIDES or a stored settings patch —
  // the service worker restarts the session when it changes.
  revealSecrets: false,
};

// Optional gitignored local overrides: `{ EXTRA_PRESETS?: […], DEFAULT_OVERRIDES?: {…} }`.
// Resolved once per module instance; a missing file simply means "no overrides".
const localOverrides = import("./settings.local.js").catch(() => ({}));

/** Env presets for the dashboard dropdown: the committed ones plus any local extras. */
export async function baseUrlPresets() {
  const local = await localOverrides;
  return [...BASE_URL_PRESETS, ...(local.EXTRA_PRESETS ?? [])];
}

/** Shallow-merge one level of nested objects so stored partials don't drop DEFAULTS sub-keys. */
function merge(base, patch) {
  const out = { ...base };
  for (const k of Object.keys(patch || {})) {
    const v = patch[k];
    out[k] = v && typeof v === "object" && !Array.isArray(v) ? merge(base[k] || {}, v) : v;
  }
  return out;
}

export async function loadSettings() {
  const local = await localOverrides;
  const base = merge(DEFAULTS, local.DEFAULT_OVERRIDES ?? {});
  const stored = await chrome.storage.local.get(SETTINGS_KEY);
  return merge(base, stored[SETTINGS_KEY] || {});
}

/** Persists a (possibly partial) patch over the current settings and returns the merged result. */
export async function saveSettings(patch) {
  const next = merge(await loadSettings(), patch);
  await chrome.storage.local.set({ [SETTINGS_KEY]: next });
  return next;
}
