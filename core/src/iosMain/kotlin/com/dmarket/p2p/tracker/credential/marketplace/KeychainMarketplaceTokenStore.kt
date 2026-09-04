// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores this
// source set until then; it is linted by spotless but not type-checked, and can only be built on a macOS CI
// runner with full Xcode.
//
// SPECULATIVE — read the KDoc before using this. It is the right answer only for an iOS client that does NOT
// already own DMarket token refresh. If it does, the answer is `HostTokenMarketplaceCredentialProvider` and
// this file is wrong by construction. That question is open (no iOS repository was available when this was
// written), which is exactly why this is a scaffold and not wiring.
package com.dmarket.p2p.tracker.credential.marketplace

import com.dmarket.p2p.tracker.model.marketplace.MarketplaceTokenPair
import com.dmarket.p2p.tracker.model.marketplace.StoredMarketplaceTokens
import com.dmarket.p2p.tracker.port.marketplace.MarketplaceTokenStore
import kotlin.time.Instant

/**
 * An iOS [MarketplaceTokenStore] over the Keychain, so an iOS host with no refresh mechanism of its own gets
 * the shared algorithm (`DefaultMarketplaceCredentialProvider`) for free.
 *
 * **Read this before choosing it.** Two of the shared algorithm's defences only mean something over a store
 * with a *second writer*: the locked re-read and the compare-and-swap on write exist because on web the store
 * is the browser cookie jar that the dmarket.com page also writes. A private Keychain has one writer, so those
 * two steps are inert here — harmless, but their inertness is the tell that this store is for the narrow case
 * where the app genuinely has no token layer. An app that has one should implement
 * [com.dmarket.p2p.tracker.port.marketplace.MarketplaceCredentialProvider] by delegation instead, and this
 * file should then be deleted rather than adapted.
 *
 * The Keychain item is written with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`: background refresh must
 * work while the device is locked, but the pair must never travel to another device or into a backup.
 *
 * Finalize the `CFDictionary` memory details against a real build, as with `KeychainCredentialVault` — the same
 * caveat in that file applies here.
 */
class KeychainMarketplaceTokenStore(private val vault: KeychainStringVault) : MarketplaceTokenStore {

    override suspend fun read(): StoredMarketplaceTokens? {
        val access = vault.get(KEY_ACCESS)
        val refresh = vault.get(KEY_REFRESH)
        return StoredMarketplaceTokens(
            accessToken = access,
            refreshToken = refresh,
            refreshTokenExpiresAt = vault.get(KEY_REFRESH_EXPIRY)?.toLongOrNull()?.let { Instant.fromEpochSeconds(it) },
        )
    }

    override suspend fun write(tokens: MarketplaceTokenPair): MarketplaceTokenStore.WriteOutcome {
        // Refresh half first, mirroring the web store: a teardown between the two writes must not leave a
        // fresh access token beside a superseded refresh token.
        vault.set(KEY_REFRESH, tokens.refreshToken)
        tokens.refreshTokenExpiresAt?.let { vault.set(KEY_REFRESH_EXPIRY, it.epochSeconds.toString()) }
        vault.set(KEY_ACCESS, tokens.accessToken)
        // Verify by read-back, for the same reason the web store does: "accepted" and "present" differ.
        return if (vault.get(KEY_ACCESS) == tokens.accessToken && vault.get(KEY_REFRESH) == tokens.refreshToken) {
            MarketplaceTokenStore.WriteOutcome.WRITTEN
        } else {
            MarketplaceTokenStore.WriteOutcome.BLIND
        }
    }

    private companion object {
        const val KEY_ACCESS = "dmarket_access_token"
        const val KEY_REFRESH = "dmarket_refresh_token"
        const val KEY_REFRESH_EXPIRY = "dmarket_refresh_token_expires_at"
    }
}

/**
 * The three Keychain primitives this store needs, kept as a seam so the `CFDictionary` bridging lives in one
 * place (alongside `KeychainCredentialVault`) rather than being duplicated per store.
 */
interface KeychainStringVault {
    suspend fun get(key: String): String?

    suspend fun set(key: String, value: String)

    suspend fun remove(key: String)
}
