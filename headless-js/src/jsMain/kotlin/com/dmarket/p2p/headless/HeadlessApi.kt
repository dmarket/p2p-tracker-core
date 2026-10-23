@file:OptIn(ExperimentalJsExport::class)

package com.dmarket.p2p.headless

import com.dmarket.p2p.tracker.runtime.TradeTrackerCore

/** The version of the library inside this package: the one the tracker reports on every heartbeat. */
@JsExport
fun headlessVersion(): String = TradeTrackerCore.VERSION
