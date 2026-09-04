package com.dmarket.p2p.tracker.adapter

import com.dmarket.p2p.tracker.adapter.host.InMemoryCredentialVault
import com.dmarket.p2p.tracker.port.host.CredentialVault

/**
 * JVM has no OS secure-storage mechanism (it exists for tests and foreground/manual composition), so
 * it resolves to the in-memory stub. **Not for production secret storage** — the JVM target is not a
 * shipping client.
 */
actual fun platformCredentialVault(): CredentialVault = InMemoryCredentialVault()
