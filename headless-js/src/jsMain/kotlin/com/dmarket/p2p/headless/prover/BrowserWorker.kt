package com.dmarket.p2p.headless.prover

import com.dmarket.p2p.headless.node.nodeBuffer
import com.dmarket.p2p.headless.node.nodeWorkerThreads

/**
 * Installs a browser `Worker` over `worker_threads` as the global `Worker`, once per process.
 *
 * The prover starts its threads the browser's way: web-spawn's `spawn.js` and the wasm itself (`web_sys::Worker`)
 * call `new Worker(url, { type })`, post the shared memory to it and listen for its messages. Node has no such
 * global, so each one becomes a worker thread that first builds a dedicated-worker scope (`self`, `postMessage`,
 * `onmessage`, `addEventListener`, `close`, a nested `Worker`), then loads the script: a module by `import()`, a
 * classic one (the wasm's `Atomics.wait` helper, from a `blob:` URL) by evaluating its source.
 *
 * Each thread also gets a `fetch` shim. `spawn.js` hands the wasm init `{ module, memory }` while the init reads
 * `module_or_path`, so every thread falls back to fetching the `.wasm` next to the glue, which Node's own `fetch`
 * refuses for a `file:` URL. The shim answers with the compiled module the spawner's start message carried, so a
 * thread instantiates it instead of compiling the file again: measured in a `node:22` container, that halves a
 * proof's peak (about 1.1 GB with a compile per thread, about 550 MB without). Before such a message it reads the
 * file from disk.
 *
 * Messages a thread receives before its script has loaded are held and delivered once it has: the spawner posts
 * its start message right after `new Worker`, before the module has registered its listener.
 */
internal fun installBrowserWorker() {
    val global: dynamic = js("globalThis")
    if (global.__dmarketBrowserWorker == true) return
    // The wasm glue reaches its global through `self`, as on a browser page, and the spawner's module registers a
    // message listener on it wherever it is imported; on the main thread nothing is ever posted to it.
    if (global.self == undefined) global.self = global
    if (global.addEventListener == undefined) {
        val scope: dynamic = js("new EventTarget()")
        global.addEventListener = scope.addEventListener.bind(scope)
        global.removeEventListener = scope.removeEventListener.bind(scope)
        global.dispatchEvent = scope.dispatchEvent.bind(scope)
    }
    // Trimmed: `return` and a newline would return nothing.
    val workerClass = WORKER_CLASS.trim()
    val bootstrap = BOOTSTRAP
    val make: dynamic = js("new Function('return ' + workerClass)()")
    global.Worker = make(::mainThreadModule, bootstrap, workerClass)
    global.__dmarketBrowserWorker = true
}

/**
 * The two modules [WORKER_CLASS] asks for on the main thread, where an ES module has no `require`. In a worker
 * thread it gets the thread's own `require`: [BOOTSTRAP] runs as a CommonJS script there.
 */
private fun mainThreadModule(name: String): dynamic = when (name) {
    "node:worker_threads" -> nodeWorkerThreads
    "node:buffer" -> nodeBuffer
    else -> error("no module $name for the prover's worker")
}

/** `(require, boot, cls) => class`: the browser `Worker`, starting a thread that runs [BOOTSTRAP]. */
private const val WORKER_CLASS = """
(function (require, boot, cls) {
  const threads = require('node:worker_threads');
  const { resolveObjectURL } = require('node:buffer');
  return class Worker extends EventTarget {
    constructor(url, options) {
      super();
      this.onmessage = null;
      this.onerror = null;
      this._pending = [];
      this._thread = null;
      const href = String(url instanceof URL ? url.href : url);
      const type = (options && options.type) || 'classic';
      const name = (options && options.name) || '';
      const start = (code) => {
        this._thread = new threads.Worker(boot, { eval: true, workerData: { href: href, type: type, name: name, code: code, boot: boot, cls: cls } });
        this._thread.on('message', (data) => {
          const event = new MessageEvent('message', { data: data });
          this.dispatchEvent(event);
          if (typeof this.onmessage === 'function') this.onmessage(event);
        });
        this._thread.on('error', (error) => {
          const event = new Event('error');
          event.error = error;
          event.message = error && error.message;
          this.dispatchEvent(event);
          if (typeof this.onerror === 'function') this.onerror(event);
        });
        for (const [data, transfer] of this._pending) this._thread.postMessage(data, transfer);
        this._pending = [];
      };
      if (href.startsWith('blob:')) {
        const blob = resolveObjectURL(href);
        if (!blob) throw new Error('worker script blob is gone: ' + href);
        blob.text().then(start, (error) => this.dispatchEvent(Object.assign(new Event('error'), { error: error })));
      } else {
        start(null);
      }
    }
    postMessage(data, transfer) {
      const list = Array.isArray(transfer) ? transfer : undefined;
      if (this._thread) this._thread.postMessage(data, list); else this._pending.push([data, list]);
    }
    terminate() {
      if (this._thread) this._thread.terminate();
    }
  };
})
"""

/** Runs first in every worker thread: the dedicated-worker scope, the `file:` fetch, then the script. */
private const val BOOTSTRAP = """
const { parentPort, workerData } = require('node:worker_threads');
const fs = require('node:fs');
const vm = require('node:vm');
const { fileURLToPath } = require('node:url');
const scope = new EventTarget();
globalThis.self = globalThis;
globalThis.name = workerData.name;
globalThis.addEventListener = scope.addEventListener.bind(scope);
globalThis.removeEventListener = scope.removeEventListener.bind(scope);
globalThis.dispatchEvent = scope.dispatchEvent.bind(scope);
globalThis.postMessage = (data, transfer) => parentPort.postMessage(data, Array.isArray(transfer) ? transfer : undefined);
globalThis.close = () => process.exit(0);
globalThis.WorkerGlobalScope = class WorkerGlobalScope { static [Symbol.hasInstance](value) { return value === globalThis; } };
globalThis.DedicatedWorkerGlobalScope = globalThis.WorkerGlobalScope;
globalThis.Worker = (new Function('return ' + workerData.cls.trim())())(require, workerData.boot, workerData.cls);
const nodeFetch = globalThis.fetch;
let sharedModule = null;
globalThis.fetch = async (input, init) => {
  const href = typeof input === 'string' ? input : (input instanceof URL ? input.href : input.url);
  if (href.endsWith('.wasm') && sharedModule) return sharedModule;
  if (href.startsWith('file:')) {
    const bytes = await fs.promises.readFile(fileURLToPath(href));
    const type = href.endsWith('.wasm') ? 'application/wasm' : 'application/octet-stream';
    return new Response(bytes, { headers: { 'content-type': type } });
  }
  return nodeFetch(input, init);
};
let loaded = false;
const held = [];
const deliver = (data) => {
  const event = new MessageEvent('message', { data: data });
  scope.dispatchEvent(event);
  if (typeof globalThis.onmessage === 'function') globalThis.onmessage(event);
};
parentPort.on('message', (data) => {
  // web-spawn's start messages carry the compiled module the main thread instantiated: the init in this thread
  // instantiates that one rather than compiling the file again.
  const first = data && Array.isArray(data.data) ? data.data[0] : null;
  if (first instanceof WebAssembly.Module) sharedModule = first;
  if (loaded) deliver(data); else held.push(data);
});
const load = async () => {
  const href = workerData.href;
  if (workerData.type === 'module') {
    await import(href);
  } else {
    let code = workerData.code;
    if (code === null) {
      code = href.startsWith('data:') ? decodeURIComponent(href.slice(href.indexOf(',') + 1)) : fs.readFileSync(fileURLToPath(href), 'utf8');
    }
    vm.runInThisContext(code, { filename: href.startsWith('file:') ? href : 'worker-script.js' });
  }
};
load().then(() => {
  loaded = true;
  for (const data of held.splice(0)) deliver(data);
}, (error) => { throw error; });
"""
