// Thin adapter over the compiled Kotlin/JS debug facade (vendor/harness.bundle.mjs). Imported only by
// the MV3 service worker — the dashboard relays every call here via chrome.runtime messaging.
//
// It manages a single self-driving DebugSessionHandle (the integration-test session: real DMarket
// backend, live Steam, all HTTP observed) and forwards the FE-triggered create-trade to the library.

import * as mod from "./vendor/harness.bundle.mjs";

let handle = null;

/**
 * (Re)starts the self-driving session. `onLog` receives one entry JSON string per exchange/event.
 * `feUrl` is the FE origin the DMarket `dm-trade-token` cookie is read + refreshed from (blank → prod).
 * `revealSecrets` opts the library out of redaction, so log entries carry bodies, URLs and headers
 * verbatim — for local diagnosis only (see `settings.js`); omitted/false keeps the redacted default.
 */
export function startSession({ baseUrl, feUrl, onLog, revealSecrets }) {
  stopSession();
  handle = mod.startDebugSession(baseUrl, onLog || null, feUrl || "", !!revealSecrets);
  return handle;
}

export function stopSession() {
  if (handle) {
    try {
      mod.stopDebugSession(handle);
    } catch (_) {
      /* already torn down */
    }
    handle = null;
  }
}

export function hasSession() {
  return handle !== null;
}

/** FE "create trade" trigger → library POSTs the Steam offer to live Steam + reports steam_offer_id to the BE. */
export async function createTrade({ directiveId, dealId, partnerSteamId, assetIds, tradeToken, linkedSteamId }) {
  if (!handle) throw new Error("no running debug session");
  return await mod.createTradeInSession(
    handle,
    directiveId || "",
    dealId || "",
    partnerSteamId,
    assetIds || [],
    tradeToken || null,
    linkedSteamId || null,
  );
}

/** Run one heartbeat cycle now (fallback when a CreateTrade postMessage has no directive_id). */
export function nudge() {
  if (handle) mod.nudgeSession(handle);
}

export function version() {
  return mod.debugHarnessVersion();
}
