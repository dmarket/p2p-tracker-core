import { WsIoChannel, type WebSocketCtor } from "./channel.js";
/**
 * WebSocket subprotocol marker for the notary channel. **Must** match the
 * notary's `transport::SUBPROTOCOL` and the native `ws.rs` `SUBPROTOCOL`
 * (`tlsn.notary.v2`) — bump all three together on a contract change.
 */
export declare const NOTARY_SUBPROTOCOL = "tlsn.notary.v2";
/** Shared options for the `connect*` helpers. */
export interface ConnectOptions {
    /**
     * `WebSocket` implementation to use. Defaults to the global browser
     * `WebSocket`. Inject a polyfill (e.g. Node's `ws`) to run outside a browser.
     */
    WebSocketImpl?: WebSocketCtor;
}
/**
 * Connect the notary channel, offering `tlsn.notary.v2, bearer.<token>` in
 * `Sec-WebSocket-Protocol`, and resolve once the socket is open with the
 * negotiated subprotocol confirmed to be `tlsn.notary.v2` (the notary echoes
 * back only the marker, never the token).
 */
export declare function connectNotary(url: string, token: string, opts?: ConnectOptions): Promise<WsIoChannel>;
/**
 * Connect the target byte pipe through the `p2p-wss-proxy` worker: opens
 * `ws://<proxyBase>/?host=<host>&port=<port>` (no subprotocol / auth — the proxy
 * reads its target from the query string), and resolves once open. The worker
 * opens a raw TCP socket to `host:port` and relays bytes; MPC-TLS then runs over
 * this pipe inside `prove()`.
 */
export declare function connectProxy(proxyBase: string, host: string, port: number, opts?: ConnectOptions): Promise<WsIoChannel>;
//# sourceMappingURL=transport.d.ts.map