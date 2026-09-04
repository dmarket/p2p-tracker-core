package com.dmarket.p2p.tracker.port.host

import com.dmarket.p2p.tracker.model.steam.SteamCredential

/**
 * Device-local secure storage for the Steam credential. The platform `actual`s are Keychain (iOS),
 * Android Keystore-backed storage (Android) and `chrome.storage.local` (web), selected internally by
 * `platformCredentialVault()` — the host never supplies a vault and so never sees the plaintext
 * credential. The Steam credential never leaves the device, so it lives only behind this port.
 *
 * **Security contract (the lib cannot enforce this — implementations must honour it):**
 * - Every production `actual` **MUST** persist the credential **encrypted at rest** using OS-backed
 *   key material (Keychain / Android Keystore), where the key lives in hardware and never enters
 *   library memory. (`chrome.storage.local` is the one exception — origin-isolated but not encrypted
 *   at rest; a documented platform limitation of MV3 extensions.)
 * - The stored value is **device-only** and is **never transmitted to DMarket** (see the audit
 *   boundary on [com.dmarket.p2p.tracker.model.steam.SteamCredential]).
 * - `InMemoryCredentialVault` is **test/JVM-only** — it stores plaintext in memory and must never be
 *   wired into a shipping mobile/extension build.
 *
 * The DMarket marketplace JWT is **not** stored here — it is scraped on demand from the logged-in
 * `dmarket.com` cookie session (see `MarketplaceCredentialProvider`),
 * whose cookie jar is its own durable store.
 */
interface CredentialVault {
    suspend fun readSteamCredential(): SteamCredential?

    suspend fun writeSteamCredential(credential: SteamCredential)

    suspend fun clearSteamCredential()
}
