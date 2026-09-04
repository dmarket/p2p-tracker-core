package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageCredentialVault
import com.dmarket.p2p.tracker.port.host.CredentialVault

/**
 * Web resolves to [WebExtStorageCredentialVault] (`chrome.storage.local` / `browser.storage.local`),
 * which survives MV3 service-worker teardown. It is origin-isolated but **not** encrypted at rest —
 * a documented MV3 platform limitation (see [WebExtStorageCredentialVault]).
 */
actual fun platformCredentialVault(): CredentialVault = WebExtStorageCredentialVault()
