package com.dmarket.p2p.tracker.wire

/**
 * Splits [items] into consecutive runs whose encoded request body stays within [maxBytes] UTF-8 bytes.
 *
 * For the two reporting batches (`/trade-events`, `/trade-actions`): a gateway that spools a body larger than
 * its buffer to disk can end up checking a request signature against an empty body, so a signed batch past
 * that size is rejected as unauthenticated. Keeping every body under the cap is the client-side answer. It
 * cannot shrink a single item that is already larger — that item goes alone, in a run of its own.
 *
 * [encode] renders a whole batch body. Sizes are computed rather than re-encoded per candidate: a compact
 * JSON body is the envelope plus the items joined by `,`, so one encode of the empty batch and one per item
 * give the exact size of any run.
 *
 * Order is preserved, every item lands in exactly one run, and no run is empty. [maxBytes] `<= 0` means no
 * cap: everything is one run.
 */
fun <T> chunkByEncodedSize(items: List<T>, maxBytes: Int, encode: (List<T>) -> String): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    if (maxBytes <= 0) return listOf(items)
    val envelope = encode(emptyList()).encodeToByteArray().size
    val chunks = mutableListOf<List<T>>()
    var current = mutableListOf<T>()
    var size = envelope
    for (item in items) {
        val itemSize = encode(listOf(item)).encodeToByteArray().size - envelope
        if (current.isNotEmpty() && size + 1 + itemSize > maxBytes) {
            chunks += current
            current = mutableListOf()
            size = envelope
        }
        size += itemSize + if (current.isEmpty()) 0 else 1
        current += item
    }
    chunks += current
    return chunks
}
