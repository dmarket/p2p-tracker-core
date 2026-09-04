package com.dmarket.p2p.tracker.port.host

import kotlin.time.Instant

/** Reads the current instant. Injected so the engine's inputs (and tests) are deterministic. */
interface Clock {
    fun now(): Instant
}
