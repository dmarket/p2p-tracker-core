package com.dmarket.p2p.tracker.support

/**
 * Encodes [text] as unpadded base64url — the one encoder the JWT tests share.
 *
 * There were two, one per JWT test class, and they disagreed on how they got there (one encoded into the
 * standard alphabet and post-substituted `+`/`/`, the other used the url alphabet directly). Two encoders that
 * must both agree with a single decoder is a trap: each class's padding edge case was only ever exercised
 * against its own encoder.
 */
fun base64UrlEncode(text: String): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val bytes = text.encodeToByteArray()
    val out = StringBuilder()
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else -1
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else -1
        out.append(alphabet[b0 shr 2])
        if (b1 < 0) {
            out.append(alphabet[(b0 and 0x03) shl 4])
        } else {
            out.append(alphabet[((b0 and 0x03) shl 4) or (b1 shr 4)])
            if (b2 < 0) {
                out.append(alphabet[(b1 and 0x0F) shl 2])
            } else {
                out.append(alphabet[((b1 and 0x0F) shl 2) or (b2 shr 6)])
                out.append(alphabet[b2 and 0x3F])
            }
        }
        i += 3
    }
    return out.toString()
}
