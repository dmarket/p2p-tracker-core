import type { IoChannel } from "./types.js";
/**
 * Injectable `WebSocket` constructor. Defaults to the global browser
 * `WebSocket`. Supplying one lets the transport run under a polyfill (e.g. the
 * `ws` package in Node for the deferred single-threaded run — see RUN_E2E.md),
 * without any code change.
 */
export type WebSocketCtor = {
    new (url: string, protocols?: string | string[]): WebSocket;
};
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
export declare class WsIoChannel implements IoChannel {
    #private;
    constructor(ws: WebSocket);
    /** Resolves once the socket is open; rejects if it fails to open. */
    whenReady(): Promise<void>;
    /** The subprotocol the server selected (`""` until open / if none). */
    get protocol(): string;
    read(): Promise<Uint8Array | null>;
    write(data: Uint8Array): Promise<void>;
    close(): Promise<void>;
}
//# sourceMappingURL=channel.d.ts.map