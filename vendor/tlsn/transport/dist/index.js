// client-wasm-transport — the TS companion to the `client-wasm` wasm prover.
//
// Two layers, both wasm-free at runtime (this package emits **no** import of
// the wasm pkg — load-bearing for the extension bundle, see ./prover.ts):
//
//   - **Transport** (`connectNotary` / `connectProxy` / `WsIoChannel`): the
//     WebSocket `IoChannel`s `prove()` consumes.
//   - **Prover façade** (`createProver`): a pass-through mirror of the wasm
//     API (`initialize` / `prove` / `present`) over a caller-injected pkg
//     module — owns instantiation + rayon-pool setup and the wire
//     conversions.
//
// All DTO types are declared locally in ./types.ts (self-contained d.ts) and
// compile-time-checked against the generated pkg in ./wasm-compat.ts.
export { WsIoChannel } from "./channel.js";
export { NOTARY_SUBPROTOCOL, connectNotary, connectProxy } from "./transport.js";
export { createProver } from "./prover.js";
//# sourceMappingURL=index.js.map