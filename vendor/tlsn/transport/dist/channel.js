/**
 * Adapts a browser `WebSocket` into the wasm prover's `IoChannel`.
 *
 * ## Byte-stream semantics (mirrors the native `WsByteStream`)
 *
 * - **read()** resolves the next inbound binary frame as a `Uint8Array`, one
 *   frame per call. The Rust `JsIoAdapter` buffers any leftover bytes, so frame
 *   boundaries need not align with the reader's buffer. When no frame is
 *   buffered the returned promise stays pending until one arrives (or EOF).
 * - **EOF** — a socket `close` or `error` resolves `read()` with `null`, which
 *   Rust maps to `Ok(0)` (io.rs treats null / empty as EOF). Empty binary
 *   frames are *skipped*, not treated as EOF, exactly as `WsByteStream` skips a
 *   zero-length payload rather than reporting end-of-stream.
 * - **write()** sends `data` as a binary frame and resolves as soon as the
 *   bytes are queued in the browser's send buffer. The browser flushes to the
 *   wire on its own — unlike tungstenite, whose userspace buffering forced the
 *   native eager-flush fix (`ws.rs`) and the io.rs write-completion queue. That
 *   queue is therefore satisfied immediately here (the returned promise is
 *   already resolved), which is correct: "send() called" ⇒ "bytes will reach
 *   the wire" for a browser `WebSocket`.
 *
 * Invariant kept by the buffering: at most one of `#queue` / `#waiters` is
 * non-empty at any time (read() drains the queue before ever parking a waiter,
 * and onMessage delivers to a waiter only when the queue is empty), so inbound
 * ordering is preserved with no reordering.
 */
export class WsIoChannel {
    #ws;
    /** Inbound frames received while no reader was waiting (FIFO). */
    #queue = [];
    /** Parked `read()` resolvers awaiting the next frame (FIFO). */
    #waiters = [];
    /** Resolves on `open`; rejects if the socket errors/closes before opening. */
    #ready;
    /** Set once the socket has closed (or errored) — all further reads are EOF. */
    #closed = false;
    constructor(ws) {
        this.#ws = ws;
        // Must be set before any message arrives so binary frames arrive as
        // ArrayBuffer (not Blob); safe to set synchronously right after construction.
        ws.binaryType = "arraybuffer";
        this.#ready = new Promise((resolve, reject) => {
            if (ws.readyState === WebSocket.OPEN) {
                resolve();
                return;
            }
            ws.addEventListener("open", () => resolve(), { once: true });
            // A failed handshake (bad auth, unreachable proxy, …) fires error/close
            // *before* open — surface it to the awaiter instead of hanging.
            ws.addEventListener("close", (ev) => reject(new Error(`websocket closed before open (code ${ev.code})`)), { once: true });
            ws.addEventListener("error", () => reject(new Error("websocket error before open")), {
                once: true,
            });
        });
        // Swallow the ready rejection here so it never becomes an unhandled
        // rejection; callers observe it via `whenReady()` (and reads see EOF).
        this.#ready.catch(() => { });
        ws.addEventListener("message", (ev) => this.#onMessage(ev));
        ws.addEventListener("close", () => this.#onEof());
        // Browsers emit `error` then `close`; treat either as end-of-stream for
        // readers. `#onEof` is idempotent, so the trailing `close` is a no-op.
        ws.addEventListener("error", () => this.#onEof());
    }
    /** Resolves once the socket is open; rejects if it fails to open. */
    whenReady() {
        return this.#ready;
    }
    /** The subprotocol the server selected (`""` until open / if none). */
    get protocol() {
        return this.#ws.protocol;
    }
    #onMessage(ev) {
        const chunk = toBytes(ev.data);
        // Skip zero-length frames rather than signalling EOF (matches WsByteStream,
        // which loops past an empty payload). An empty read *would* be read as EOF
        // by io.rs, so this guard is load-bearing.
        if (chunk === null || chunk.length === 0)
            return;
        const waiter = this.#waiters.shift();
        if (waiter)
            waiter(chunk);
        else
            this.#queue.push(chunk);
    }
    #onEof() {
        if (this.#closed)
            return;
        this.#closed = true;
        // Wake every parked reader with EOF; buffered frames (if any) were already
        // delivered ahead of any waiter by the queue-first invariant.
        let waiter;
        while ((waiter = this.#waiters.shift()))
            waiter(null);
    }
    // --- IoChannel ---------------------------------------------------------
    read() {
        const buffered = this.#queue.shift();
        if (buffered !== undefined)
            return Promise.resolve(buffered);
        if (this.#closed)
            return Promise.resolve(null);
        return new Promise((resolve) => this.#waiters.push(resolve));
    }
    async write(data) {
        await this.#ready;
        if (this.#closed || this.#ws.readyState !== WebSocket.OPEN) {
            // Fail loud: a silently-dropped write would strand a protocol frame and
            // deadlock the notary (it never replies) — the exact hazard the io.rs
            // write-completion fix guards against on the Rust side.
            throw new Error("cannot write: websocket is not open");
        }
        // Copy into a fresh (non-shared) buffer before sending. `data` may be a view
        // into the wasm linear memory, which in the multi-threaded build is a
        // SharedArrayBuffer — and `WebSocket.send()` rejects SharedArrayBuffer-backed
        // views in some browsers. `slice()` yields a plain-ArrayBuffer copy and also
        // decouples us from however io.rs hands the bytes over. Cost is negligible
        // (protocol frames are small).
        this.#ws.send(data.slice());
    }
    close() {
        if (this.#closed || this.#ws.readyState === WebSocket.CLOSED) {
            this.#onEof();
            return Promise.resolve();
        }
        return new Promise((resolve) => {
            this.#ws.addEventListener("close", () => resolve(), { once: true });
            try {
                this.#ws.close();
            }
            catch {
                // Already closing/closed — resolve; the EOF is handled by #onEof.
                resolve();
            }
        });
    }
}
/** Normalise a `MessageEvent.data` to bytes (arraybuffer / text / view). */
function toBytes(data) {
    if (data instanceof ArrayBuffer)
        return new Uint8Array(data);
    if (ArrayBuffer.isView(data)) {
        const view = data;
        return new Uint8Array(view.buffer, view.byteOffset, view.byteLength);
    }
    if (typeof data === "string")
        return new TextEncoder().encode(data);
    // Blob or unknown — unreachable while binaryType === "arraybuffer".
    return null;
}
//# sourceMappingURL=channel.js.map