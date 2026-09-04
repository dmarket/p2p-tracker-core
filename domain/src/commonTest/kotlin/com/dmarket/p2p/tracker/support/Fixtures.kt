package com.dmarket.p2p.tracker.support

/** Loads a fixture file from `commonTest/resources/fixtures/` by name (e.g. `"heartbeat_response.json"`). */
expect fun fixture(name: String): String
