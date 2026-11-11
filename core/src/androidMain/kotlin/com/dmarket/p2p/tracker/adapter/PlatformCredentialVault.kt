// The default createLoop falls back to. It is not scoped to an account or bound to the install: the Android
// tracker takes its vault and stores from AndroidTrackerPlatform instead.
package com.dmarket.p2p.tracker.adapter

import android.content.Context
import com.dmarket.p2p.tracker.port.host.CredentialVault

/**
 * Android resolves to the Keystore-backed [AndroidCredentialVault]. The vault needs an application
 * [Context]; the host stashes it once at startup (see [AndroidAppContextHolder]) so this factory stays
 * free of Context plumbing — mirroring how [AndroidWorkManagerHolder] feeds [platformScheduler]. The
 * host passes only a Context (non-secret); the plaintext credential never crosses back to host code.
 */
actual fun platformCredentialVault(): CredentialVault = AndroidCredentialVault(AndroidAppContextHolder.context)

/**
 * Captured once by the host at startup (e.g. an `androidx.startup` `Initializer` or `Application.onCreate`):
 * `AndroidAppContextHolder.context = applicationContext`. Kept as a tiny holder so
 * [platformCredentialVault] stays free of Android `Context` plumbing.
 */
object AndroidAppContextHolder {
    lateinit var context: Context
}
