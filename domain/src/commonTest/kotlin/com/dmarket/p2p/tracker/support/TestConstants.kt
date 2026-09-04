package com.dmarket.p2p.tracker.support

import kotlin.time.Instant

/** A fixed reference instant for deterministic tests (no real clock anywhere in `:domain`). */
val T0: Instant = Instant.parse("2026-06-16T12:00:00Z")

/** The seller's own Steam id (this device). */
const val SELF_STEAM_ID: String = "76561198000000001"

/** The counterparty (buyer) Steam id. */
const val COUNTERPARTY_STEAM_ID: String = "76561198000000002"
