package com.dmarket.p2p.tracker.client

// The JVM engines throw an exception whose own message names the failure; there is no hidden cause to add.
internal actual fun Throwable.transportCause(): String? = null
