package com.dmarket.p2p.headless.node

// Whole Node modules for the prover host, imported rather than required: the library is an ES module, and the same
// imports become `require` calls in a CommonJS build. Kept `dynamic`, as the calls into them were before they
// moved here; each use sits behind a typed function of its own.

@JsModule("node:child_process")
internal external val nodeChildProcess: dynamic

@JsModule("node:fs")
internal external val nodeFs: dynamic

@JsModule("node:path")
internal external val nodePath: dynamic

@JsModule("node:url")
internal external val nodeUrl: dynamic

@JsModule("node:module")
internal external val nodeModule: dynamic

@JsModule("node:worker_threads")
internal external val nodeWorkerThreads: dynamic

@JsModule("node:buffer")
internal external val nodeBuffer: dynamic

/** The `ws` package: its export is the `WebSocket` class. */
@JsModule("ws")
internal external val wsWebSocket: dynamic

/** The running process: a global in Node. */
internal val nodeProcess: dynamic get() = js("process")
