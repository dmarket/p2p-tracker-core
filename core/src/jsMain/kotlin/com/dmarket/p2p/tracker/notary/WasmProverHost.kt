package com.dmarket.p2p.tracker.notary

import kotlin.js.Promise

/**
 * Where [BundledWasmProverModule] gets its assets and its two IO channels, and who sees the target's answer. Every member is `null` by default,
 * and `null` is exactly what the extension runs, so `WasmProverHost()` changes nothing on web.
 *
 * The seams exist for a host outside the browser, where each default is unavailable: there is no extension
 * root to resolve the assets against, `fetch` refuses the `file:` URL the wasm init would load the `.wasm`
 * from, the notary may authenticate the upgrade by headers rather than by a `bearer.` subprotocol, and the
 * target can be a plain TCP socket instead of a tunnel through `p2p-wss-proxy`. This source set also ships to
 * the browser, so nothing here names a Node API: the host builds those pieces and hands them in.
 *
 * @property assetUrl resolves a prover asset path (`pkg/client_wasm.js`, `transport/dist/index.js`) to the URL
 *   `import()` loads. `null`: `runtime.getURL`, i.e. the extension root.
 * @property wasmModule supplies the compiled `pkg/client_wasm_bg.wasm` (a `WebAssembly.Module`), handed to the
 *   wasm init as `module_or_path`. Called once, by the first [BundledWasmProverModule.initialize] that loads the
 *   modules. `null`: the init fetches the `.wasm` next to `client_wasm.js` itself.
 * @property notaryWebSocket supplies the `WebSocket` constructor for **one** notary connect, passed to the
 *   transport's `connectNotary` as its `WebSocketImpl`. Called right before every connect, so a constructor that
 *   carries signed upgrade headers carries fresh ones. The transport still offers `bearer.<token>` in the
 *   subprotocol list; a constructor that authenticates by headers drops that entry. `null`: the global
 *   `WebSocket`, i.e. the bearer subprotocol.
 * @property connectTarget opens the proof's target leg to `serverName:port` and resolves with an open
 *   `IoChannel` (`read()` resolving `null` at EOF, `write(bytes)`, idempotent `close()`). Dialled lazily, on the
 *   prover's first write, exactly like the default (see [lazyTargetChannel]). `null`: the transport's
 *   `connectProxy` through `WasmProveRequest.proxyBaseUrl`, which this connector makes irrelevant.
 * @property onTargetResponse is handed the HTTP status of the target's response once the issuance is done and
 *   before the presentation is built; throwing refuses the proof, so a host can decline to present an error page.
 *   `null`: the status is not looked at, and the presentation is built whatever it is.
 */
class WasmProverHost(
    val assetUrl: ((path: String) -> String)? = null,
    val wasmModule: (suspend () -> Any)? = null,
    val notaryWebSocket: (suspend () -> Any)? = null,
    val connectTarget: ((serverName: String, port: Int) -> Promise<Any?>)? = null,
    val onTargetResponse: ((status: Int) -> Unit)? = null,
)
