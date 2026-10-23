@file:JsModule("node:fs/promises")

package com.dmarket.p2p.headless.node

import kotlin.js.Promise

// The parts of `node:fs/promises` the file store uses. Imported, not required: the library is an ES module.

internal external fun readFile(path: String, encoding: String): Promise<String>

internal external fun mkdir(path: String, options: MkdirOptions): Promise<String?>

internal external fun writeFile(path: String, data: String, options: WriteFileOptions): Promise<Unit>

internal external fun rename(oldPath: String, newPath: String): Promise<Unit>

internal external fun unlink(path: String): Promise<Unit>

internal external interface MkdirOptions {
    var recursive: Boolean
    var mode: Int
}

internal external interface WriteFileOptions {
    var encoding: String
    var mode: Int
}
