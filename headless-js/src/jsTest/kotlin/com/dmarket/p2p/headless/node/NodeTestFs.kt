@file:JsModule("node:fs/promises")

package com.dmarket.p2p.headless.node

import kotlin.js.Promise

// What the tests read back from disk, beside what the store itself uses.

internal external fun mkdtemp(prefix: String): Promise<String>

internal external fun stat(path: String): Promise<NodeStats>

internal external interface NodeStats {
    val mode: Int
}
