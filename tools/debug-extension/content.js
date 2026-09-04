// Content script injected on dmarket.com — the frontend → extension bridge.
//
// It is intake-only: it receives the DMarket FE's `window.postMessage("create trade")` command,
// validates it, and relays it to the service worker (which asks the library to POST the Steam offer to
// live Steam). It NEVER receives the session log or any secret (the page is untrusted).
//
// The real FE does not emit this message yet — simulate it from the dmarket.com DevTools console:
//   window.postMessage({ source: "dmarket-fe",
//     command: { type: "CreateTrade", directive_id: "<directiveId>", deal_id: "<dealId>",
//       partner_steam_id: "765…", asset_ids: ["<assetId>"], trade_token: "<token>",
//       linked_steam_id: "<the DMarket account's linked Steam id>" } },
//     location.origin);
// A linked_steam_id that differs from the logged-in Steam account blocks the create and replies with
// { source: "dmarket-ext", type: "AccountMismatch", status: "account_mismatch", linkedSteamId, tokenSteamId }.

(() => {
  "use strict";

  const SETTINGS_KEY = "p2p_debug_settings";
  const EXPECTED_SOURCE = "dmarket-fe";
  // Fallback allow-list until settings load (matches settings.js DEFAULTS). Extra origins from
  // settings.local.js reach here through storage once the settings are applied; until then the
  // same-origin self-post acceptance below already covers a manifest-matched dev FE page.
  let allowedOrigins = ["https://dmarket.com", "https://www.dmarket.com"];

  chrome.storage.local.get(SETTINGS_KEY, (stored) => {
    const s = stored[SETTINGS_KEY];
    if (s && Array.isArray(s.allowedOrigins)) allowedOrigins = s.allowedOrigins;
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area === "local" && changes[SETTINGS_KEY]?.newValue?.allowedOrigins) {
      allowedOrigins = changes[SETTINGS_KEY].newValue.allowedOrigins;
    }
  });

  window.addEventListener("message", (event) => {
    // Security: the page is untrusted. Accept only messages this same window posted, tagged by the
    // FE, of the one command type this bridge forwards. This content script is injected only on
    // manifest-matched (trusted) hosts, so a same-window self-post from this page's own origin is
    // safe — accept it even if the stored allow-list is stale (e.g. a newly-added dev FE origin);
    // the configured allow-list still admits any additional cross-window origins.
    if (event.source !== window) return;
    if (event.origin !== location.origin && !allowedOrigins.includes(event.origin)) return;
    const data = event.data;
    if (!data || data.source !== EXPECTED_SOURCE || !data.command) return;
    if (data.command.type !== "CreateTrade") return;

    // If the extension was reloaded, this injected content script is orphaned and chrome.runtime is
    // dead — reloading the dmarket.com tab re-injects a fresh one.
    if (!chrome.runtime?.id) {
      console.warn("[p2p-debug] extension context invalidated — reload this dmarket.com tab, then retry.");
      return;
    }

    const directiveId = String(data.command.directive_id || "");
    // deal_id is the DMarket deal key (NOT the Steam offer id) — /trade-actions requires it, so the
    // library rejects a fast-path create without it. Relay it verbatim; the FE owns supplying it.
    const dealId = String(data.command.deal_id || "");
    // linked_steam_id is the Steam account the DMarket profile is bound to. The library verifies it
    // against the logged-in Steam token before writing, and blocks the create on a wrong-account session.
    const linkedSteamId = data.command.linked_steam_id ? String(data.command.linked_steam_id) : "";

    // No directive_id → fall back to the regular heartbeat/directive flow (let the BE lease the
    // create_offer directive; the loop executes it on its own cadence). No FE-fast-path create.
    if (!directiveId) {
      chrome.runtime.sendMessage({ type: "request-cycle" }, () => void chrome.runtime.lastError);
      return;
    }

    chrome.runtime.sendMessage(
      {
        type: "create-trade",
        directiveId,
        dealId,
        partnerSteamId: String(data.command.partner_steam_id || ""),
        assetIds: Array.isArray(data.command.asset_ids) ? data.command.asset_ids.map(String) : [],
        tradeToken: data.command.trade_token ? String(data.command.trade_token) : undefined,
        linkedSteamId: linkedSteamId || undefined,
      },
      (res) => {
        if (chrome.runtime.lastError || !res) return;
        // Hand the created Steam offer back to the FE (parallel model): the FE registers the
        // steam_offer_id with the DMarket backend — Steam only mints it on this POST, so the FE's
        // initial "create clicked" call can't carry it. The plugin itself never calls /trade-actions.
        let payload = {};
        try {
          payload = JSON.parse(res.result || "{}");
        } catch (_) {
          payload = { raw: res.result };
        }
        // A wrong-account session is reported as AccountMismatch (no Steam offer was created) so the FE
        // can prompt the user to log into the correct Steam account; otherwise it's a normal TradeCreated.
        const type = payload.status === "account_mismatch" ? "AccountMismatch" : "TradeCreated";
        window.postMessage({ source: "dmarket-ext", type, ...payload }, location.origin);
      },
    );
  });
})();
