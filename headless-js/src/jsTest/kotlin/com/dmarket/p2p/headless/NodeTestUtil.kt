@file:JsModule("node:util")

package com.dmarket.p2p.headless

/** Node's own renderer: what a host's `console.log` shows, every property reachable from [value] included. */
internal external fun inspect(value: Any?, options: dynamic): String
