package com.dmarket.p2p.tracker.support

actual fun fixture(name: String): String = checkNotNull(
    Thread.currentThread().contextClassLoader.getResourceAsStream("fixtures/$name"),
) { "Fixture not found on classpath: fixtures/$name" }
    .readBytes()
    .decodeToString()
