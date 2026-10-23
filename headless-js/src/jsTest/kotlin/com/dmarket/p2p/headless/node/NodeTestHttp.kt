@file:JsModule("node:http")

package com.dmarket.p2p.headless.node

internal external fun createServer(listener: (request: dynamic, response: dynamic) -> Unit): dynamic
