package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.webext.WebExtStorageCredentialVault
import com.dmarket.p2p.tracker.port.host.CredentialVault

/**
 * Web resolves to [WebExtStorageCredentialVault] (`chrome.storage.session` / `browser.storage.session`),
 * which survives MV3 service-worker teardown but never reaches disk and is gone when the browser closes
 * (see [WebExtStorageCredentialVault]).
 */
actual fun platformCredentialVault(): CredentialVault = WebExtStorageCredentialVault()
