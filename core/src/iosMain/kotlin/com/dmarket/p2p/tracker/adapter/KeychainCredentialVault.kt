// PHASE 3 SCAFFOLD — reference only, NOT compiled until the iOS targets are enabled in
// build-logic/.../dmarket.kmp.library.gradle.kts (iosArm64()/iosSimulatorArm64()/iosX64()). KMP ignores
// this source set until then; it is linted by spotless but not type-checked, and can only be built on a
// macOS CI runner with full Xcode. Finalize the cinterop / CFDictionary memory details there. It
// implements ONLY the CredentialVault port — the OS owns the encryption key (Keychain), so no crypto is
// hand-rolled here; the lib only stores and retrieves.
package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.credential.steam.DeviceVaultKeys
import com.dmarket.p2p.tracker.credential.steam.StoredSteamCredential
import com.dmarket.p2p.tracker.model.steam.SteamCredential
import com.dmarket.p2p.tracker.port.host.CredentialVault
import com.dmarket.p2p.tracker.wire.trackerJson
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import kotlinx.serialization.encodeToString
import platform.CoreFoundation.CFBridgingRelease
import platform.CoreFoundation.CFBridgingRetain
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecValueData
import platform.darwin.OSStatus

/**
 * iOS [CredentialVault] backed by the Keychain (a generic-password item).
 *
 * The credential is persisted as the JSON-encoded [StoredSteamCredential] (the same on-disk shape the
 * web vault uses), keyed by [service] + [DeviceVaultKeys.STEAM_CREDENTIAL]. The encryption key lives in
 * the device keychain / Secure Enclave and **never enters library memory** — the lib hand-rolls no
 * crypto, it only stores and retrieves.
 *
 * **Accessibility:** items are written with `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly` — readable
 * in the background after first unlock (so the tracker survives app suspension), but never synced to
 * iCloud Keychain and never off the device, honouring the audit boundary on [SteamCredential].
 *
 * Behaviour contract (fixed): [writeSteamCredential] upserts, [readSteamCredential] decodes-or-null,
 * [clearSteamCredential] deletes. Treating `errSecItemNotFound` as a no-op keeps clear/read idempotent.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class KeychainCredentialVault(private val service: String = DEFAULT_SERVICE) : CredentialVault {

    private val json = trackerJson { ignoreUnknownKeys = true }
    private val account = DeviceVaultKeys.STEAM_CREDENTIAL

    override suspend fun readSteamCredential(): SteamCredential? {
        val raw = keychainReadString() ?: return null
        // A corrupt or schema-migrated item decodes to null → triggers a re-scrape; no crash.
        return runCatching { json.decodeFromString<StoredSteamCredential>(raw).toDomain() }.getOrNull()
    }

    override suspend fun writeSteamCredential(credential: SteamCredential) {
        keychainUpsert(json.encodeToString(StoredSteamCredential.from(credential)))
    }

    override suspend fun clearSteamCredential() {
        SecItemDelete(matchQuery()).ignoringNotFound()
    }

    // ---- Keychain bridging (finalize on a real iOS build) ------------------------------------------

    /** Identity query: class + service + account (no value, no return flags). */
    private fun matchQuery(): CFDictionaryRef = cfDictionaryOf(
        kSecClass to kSecClassGenericPassword,
        kSecAttrService to service.cf(),
        kSecAttrAccount to account.cf(),
    )

    private fun keychainReadString(): String? = memScoped {
        val query = cfDictionaryOf(
            kSecClass to kSecClassGenericPassword,
            kSecAttrService to service.cf(),
            kSecAttrAccount to account.cf(),
            kSecReturnData to kCFBooleanTrue,
            kSecMatchLimit to kSecMatchLimitOne,
        )
        val result = alloc<CFTypeRefVar>()
        val status = SecItemCopyMatching(query, result.ptr)
        if (status != errSecSuccess) return null
        val data = CFBridgingRelease(result.value) as? NSData ?: return null
        NSString.create(data, NSUTF8StringEncoding) as String?
    }

    private fun keychainUpsert(value: String) {
        val data = (value as NSString).dataUsingEncoding(NSUTF8StringEncoding) ?: return
        val dataRef = CFBridgingRetain(data)
        val updated = SecItemUpdate(matchQuery(), cfDictionaryOf(kSecValueData to dataRef))
        if (updated == errSecItemNotFound) {
            SecItemAdd(
                cfDictionaryOf(
                    kSecClass to kSecClassGenericPassword,
                    kSecAttrService to service.cf(),
                    kSecAttrAccount to account.cf(),
                    kSecValueData to dataRef,
                    kSecAttrAccessible to kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
                ),
                null,
            ).requireOk()
        } else {
            updated.requireOk()
        }
    }

    private fun String.cf(): CFTypeRef? = CFBridgingRetain(this as NSString)

    private fun OSStatus.requireOk() {
        check(this == errSecSuccess || this == errSecItemNotFound) { "Keychain op failed: OSStatus=$this" }
    }

    private fun OSStatus.ignoringNotFound() {
        if (this != errSecItemNotFound) requireOk()
    }

    /** Builds an immutable `CFDictionary` from CF key/value pairs (canonical SecItem query shape). */
    private fun cfDictionaryOf(vararg pairs: Pair<CFStringRef?, CFTypeRef?>): CFDictionaryRef = memScoped {
        val keys = allocArrayOf(pairs.map { it.first })
        val values = allocArrayOf(pairs.map { it.second })
        CFDictionaryCreate(
            kCFAllocatorDefault,
            keys.reinterpret<CValuesRef<*>>(),
            values.reinterpret<CValuesRef<*>>(),
            pairs.size.convert(),
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr,
        )!!
    }

    companion object {
        const val DEFAULT_SERVICE: String = "com.dmarket.p2p.tracker.steam-credential"
    }
}
