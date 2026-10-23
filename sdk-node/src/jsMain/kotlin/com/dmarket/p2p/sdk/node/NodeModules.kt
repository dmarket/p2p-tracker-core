package com.dmarket.p2p.sdk.node

/**
 * The Node built-ins the SDK uses, loaded once. Kept `dynamic` and confined to this package: every call site
 * wraps them in a typed function, so nothing outside `node/` touches an untyped API.
 */
internal object NodeModules {
    val fs: dynamic = js("require('node:fs/promises')")
    val path: dynamic = js("require('node:path')")
    val http: dynamic = js("require('node:http')")
    val process: dynamic = js("process")
    val buffer: dynamic = js("require('node:buffer').Buffer")
    val os: dynamic = js("require('node:os')")
}
