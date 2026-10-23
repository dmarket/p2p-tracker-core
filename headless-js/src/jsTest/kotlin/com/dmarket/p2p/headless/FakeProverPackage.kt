package com.dmarket.p2p.headless

import com.dmarket.p2p.headless.node.join
import com.dmarket.p2p.headless.node.nodeFs
import com.dmarket.p2p.headless.node.tempDir
import kotlinx.coroutines.await
import kotlin.js.Promise

/**
 * A directory laid out like the package for the prover: a fake `prover-child.mjs` speaking the IPC in [mode], and
 * empty stand-ins for the prover's files. [report], when set, is a file the child writes its argv, execArgv and env
 * into. Returns the directory.
 *
 * The child also prints what it was sent, as a careless prover's trace would, so the redaction is tested on the
 * real output path. Modes: `prove`; `hang-before-connect` (never asks for headers); `hang-after-connect` (asks,
 * never answers); `refuse-401-once` (the first process answers a refused upgrade, later ones prove);
 * `fail-with-token` (answers a failure whose text quotes the token).
 */
suspend fun fakeProverPackage(mode: String, report: String = "", assets: Boolean = true): String {
    val root = tempDir()
    write(join(root, "prover-child.mjs"), FAKE_CHILD.replace("__MODE__", mode).replace("__REPORT__", report))
    if (assets) {
        mkdirs(join(root, "pkg"))
        mkdirs(join(join(root, "transport"), "dist"))
        write(join(join(root, "pkg"), "client_wasm_bg.wasm"), "")
        write(join(join(join(root, "transport"), "dist"), "node.js"), "")
    }
    return root
}

private suspend fun write(path: String, text: String) {
    nodeFs.promises.writeFile(path, text).unsafeCast<Promise<Any?>>().await()
}

private suspend fun mkdirs(path: String) {
    val options: dynamic = js("({recursive: true})")
    nodeFs.promises.mkdir(path, options).unsafeCast<Promise<Any?>>().await()
}

private const val FAKE_CHILD = """
import fs from 'node:fs';
import { fileURLToPath } from 'node:url';
const mode = '__MODE__';
const report = '__REPORT__';
const self = fileURLToPath(import.meta.url);
if (report) fs.writeFileSync(report, JSON.stringify({ argv: process.argv, execArgv: process.execArgv, env: process.env }));
let token = '';
process.on('message', (raw) => {
  const msg = JSON.parse(raw);
  if (msg.type === 'prove') {
    token = msg.steamAccessToken;
    console.log('proving deal ' + msg.request.dealId + ' with access_token=' + token + ' sessionid=' + msg.steamSessionId);
    if (mode === 'hang-before-connect') return;
    if (mode === 'fail-with-token') {
      process.send(JSON.stringify({ type: 'failed', id: msg.id, failure: { type: 'other', detail: 'GET failed: access_token=' + token + ' ' + token } }));
      return;
    }
    if (mode === 'refuse-401-once' && !fs.existsSync(self + '.refused')) {
      fs.writeFileSync(self + '.refused', '1');
      process.send(JSON.stringify({ type: 'failed', id: msg.id, failure: { type: 'upgrade_refused', status: 401 } }));
      return;
    }
    process.send(JSON.stringify({ type: 'headers_wanted', id: msg.id }));
  } else if (msg.type === 'headers') {
    console.log('upgrade headers ' + JSON.stringify(msg.headers));
    console.error('X-Request-Sign: ' + msg.headers['X-Request-Sign'] + ' X-Api-Key=' + msg.headers['X-Api-Key']);
    console.log('bare ' + token);
    if (mode === 'hang-after-connect') return;
    process.send(JSON.stringify({ type: 'proved', id: msg.id, presentation: 'cHJvb2Y=' }));
  }
});
process.on('disconnect', () => process.exit(0));
"""
