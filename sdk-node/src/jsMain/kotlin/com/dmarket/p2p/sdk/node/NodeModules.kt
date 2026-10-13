package com.dmarket.p2p.sdk.node

/**
 * The Node built-ins the SDK uses, loaded once. Kept `dynamic` and confined to this package: every call site
 * wraps them in a typed function, so nothing outside `node/` touches an untyped API.
 */
internal object NodeModules {
    val fs: dynamic = js("require('node:fs/promises')")
    val path: dynamic = js("require('node:path')")
    val crypto: dynamic = js("require('node:crypto')")
    val http: dynamic = js("require('node:http')")
    val process: dynamic = js("process")
    val buffer: dynamic = js("require('node:buffer').Buffer")
}

/** A Node `Buffer` view of [bytes], without a copy. */
internal fun ByteArray.toBuffer(): dynamic {
    val array: dynamic = this
    return NodeModules.buffer.from(array.buffer, array.byteOffset, array.length)
}

/** A copy of a Node `Buffer` as a [ByteArray]. */
internal fun bufferToByteArray(buffer: dynamic): ByteArray {
    val length = buffer.length as Int
    return ByteArray(length) { i -> (buffer[i] as Int).toByte() }
}

/** [bytes] cryptographically random bytes, hex-encoded. */
internal fun randomHex(bytes: Int): String = NodeModules.crypto.randomBytes(bytes).toString("hex") as String

/** The `code` of a Node system error (`ENOENT`, …), or `null`. */
internal fun Throwable.nodeErrorCode(): String? {
    val error: dynamic = this
    return error.code as? String
}
