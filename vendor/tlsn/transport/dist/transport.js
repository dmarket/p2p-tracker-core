// WebSocket transport for the `client-wasm` browser-extension prover.
//
// Provides the two `IoChannel`s that `prove()` (see `client_wasm.d.ts`) needs,
// mirroring the native `connect_notary` / `connect_proxy` in
// `test-full-e2e/src/ws.rs`:
//
//   - `connectNotary(url, token)`  — the notary socket, authenticated via the
//     `Sec-WebSocket-Protocol` bearer subprotocol (browsers can't set an
//     `Authorization` header on a WS handshake — only subprotocols — so the
//     contract carries the token this way; CLIENT_CONTRACT §2).
//   - `connectProxy(proxyBase, host, port)` — the target byte pipe, tunnelled
//     through the `p2p-wss-proxy` worker (a browser extension can't open raw
//     TCP, so `serverIo` is WS → proxy → TCP; JOURNAL toplevel/06).
//
// Both return a `WsIoChannel` (see `./channel`) already open, so a bad handshake
// (auth failure, unreachable proxy) rejects here rather than surfacing later as
// a mid-MPC read error.
import { WsIoChannel } from "./channel.js";
/**
 * WebSocket subprotocol marker for the notary channel. **Must** match the
 * notary's `transport::SUBPROTOCOL` and the native `ws.rs` `SUBPROTOCOL`
 * (`tlsn.notary.v2`) — bump all three together on a contract change.
 */
export const NOTARY_SUBPROTOCOL = "tlsn.notary.v2";
function resolveWebSocket(opts) {
    const impl = opts?.WebSocketImpl ?? globalThis.WebSocket;
    if (!impl) {
        throw new Error("no WebSocket implementation: not in a browser and none injected — " +
            "pass { WebSocketImpl } (e.g. Node's `ws` package)");
    }
    return impl;
}
/**
 * Connect the notary channel, offering `tlsn.notary.v2, bearer.<token>` in
 * `Sec-WebSocket-Protocol`, and resolve once the socket is open with the
 * negotiated subprotocol confirmed to be `tlsn.notary.v2` (the notary echoes
 * back only the marker, never the token).
 */
export async function connectNotary(url, token, opts) {
    const WS = resolveWebSocket(opts);
    const ws = new WS(url, [NOTARY_SUBPROTOCOL, `bearer.${token}`]);
    const channel = new WsIoChannel(ws);
    try {
        await channel.whenReady();
    }
    catch (cause) {
        throw new Error(`notary websocket handshake failed (auth?): ${url}`, { cause });
    }
    if (channel.protocol !== NOTARY_SUBPROTOCOL) {
        await channel.close();
        throw new Error(`notary negotiated an unexpected subprotocol: ${JSON.stringify(channel.protocol)}`);
    }
    return channel;
}
/**
 * Connect the target byte pipe through the `p2p-wss-proxy` worker: opens
 * `ws://<proxyBase>/?host=<host>&port=<port>` (no subprotocol / auth — the proxy
 * reads its target from the query string), and resolves once open. The worker
 * opens a raw TCP socket to `host:port` and relays bytes; MPC-TLS then runs over
 * this pipe inside `prove()`.
 */
export async function connectProxy(proxyBase, host, port, opts) {
    const WS = resolveWebSocket(opts);
    const url = `${proxyBase.replace(/\/+$/, "")}/?host=${encodeURIComponent(host)}&port=${port}`;
    const ws = new WS(url);
    const channel = new WsIoChannel(ws);
    try {
        await channel.whenReady();
    }
    catch (cause) {
        throw new Error(`proxy websocket handshake failed: ${url}`, { cause });
    }
    return channel;
}
//# sourceMappingURL=transport.js.map