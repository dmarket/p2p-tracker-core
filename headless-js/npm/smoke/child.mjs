// child.mjs <prove.json> [<core VERSION>] — the packaged prover child, forked from node_modules the way the library
// forks it: a `prove` over IPC, the upgrade headers when it asks, and a stub notary on loopback that refuses every
// upgrade with 503. Passes when the child answers `failed` with that refusal after loading and initializing the wasm
// from pkg/ (its `issuance` line is printed only after that), ends when it is killed, and a run without an IPC
// channel ends by itself.
import assert from 'node:assert/strict';
import { execFileSync, fork, spawnSync } from 'node:child_process';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import http from 'node:http';
import { createRequire } from 'node:module';
import path from 'node:path';

const [provePath, coreVersionPath] = process.argv.slice(2);
const require = createRequire(import.meta.url);
const root = path.dirname(require.resolve('@dmarket/p2p-tracker-headless/package.json'));
const script = path.join(root, 'prover-child.mjs');

// The prover's files, and the same prover as the extension's package.
for (const file of ['prover-child.mjs', 'pkg/client_wasm_bg.wasm', 'pkg/client_wasm.js', 'transport/dist/node.js', 'VERSION']) {
  assert.ok(existsSync(path.join(root, file)), `${file} is not in the package`);
}
const version = readFileSync(path.join(root, 'VERSION'), 'utf8');
if (coreVersionPath && existsSync(coreVersionPath)) {
  assert.equal(version, readFileSync(coreVersionPath, 'utf8'), 'VERSION differs from the core package');
  console.log('child: VERSION equals the core package');
} else {
  console.log('child: no core VERSION to compare with');
}

let upgrades = 0;
const notary = http.createServer((_, response) => response.writeHead(404).end());
notary.on('upgrade', (_, socket) => {
  upgrades += 1;
  socket.end('HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n');
});
await new Promise((resolve) => notary.listen(0, '127.0.0.1', resolve));

const prove = JSON.parse(readFileSync(provePath, 'utf8'));
prove.settings.notaryUrl = `ws://127.0.0.1:${notary.address().port}/provenance/v1/`;
prove.settings.assetsDir = root;

const child = fork(script, [], { stdio: ['ignore', 'pipe', 'pipe', 'ipc'], serialization: 'json', execArgv: [] });
const output = [];
child.stdout.on('data', (data) => output.push(...String(data).split('\n').filter(Boolean)));
child.stderr.on('data', (data) => output.push(...String(data).split('\n').filter(Boolean).map((line) => `stderr: ${line}`)));

let headersWanted = false;
const answer = await new Promise((resolve, reject) => {
  const timer = setTimeout(() => reject(new Error(`no answer in 120 s; last output: ${output.slice(-5).join(' | ')}`)), 120_000);
  child.on('exit', (code, signal) => reject(new Error(`the child exited first (${code ?? signal}): ${output.slice(-5).join(' | ')}`)));
  child.on('message', (raw) => {
    const message = JSON.parse(raw);
    if (message.type === 'headers_wanted') {
      headersWanted = true;
      child.send(JSON.stringify({ type: 'headers', id: message.id, headers: {} }));
    } else {
      clearTimeout(timer);
      resolve(message);
    }
  });
  child.send(JSON.stringify(prove));
});
child.removeAllListeners('exit');
const exited = new Promise((resolve) => child.on('exit', (code, signal) => resolve(signal ?? code)));
child.kill('SIGKILL');
const ended = await exited;
notary.close();

const initialized = output.some((line) => line.startsWith('prover: issuance '));
console.log(`child: answer ${JSON.stringify(answer.failure ?? answer.type)}`);
console.log(`child: wasm initialized ${initialized}, headers asked ${headersWanted}, upgrades at the stub ${upgrades}`);
assert.equal(output.some((line) => /ERR_MODULE_NOT_FOUND|ENOENT|Cannot find (module|package)/.test(line)), false, output.join('\n'));
assert.equal(answer.type, 'failed');
assert.equal(answer.failure.type, 'upgrade_refused');
assert.equal(answer.failure.status, 503);
assert.ok(headersWanted, 'the child never asked for the upgrade headers');
assert.ok(upgrades > 0, 'the upgrade never reached the stub notary');
assert.ok(initialized, 'the wasm prover was never initialized');
assert.equal(ended, 'SIGKILL');
assert.deepEqual(liveChildren(), [], 'a prover child is still running');

// Without an IPC channel there is nothing to prove: the child ends by itself.
const alone = spawnSync(process.execPath, [script], { timeout: 15_000, encoding: 'utf8' });
assert.equal(alone.signal, null, `the child without IPC was still running after 15 s: ${alone.stderr}`);
assert.equal(alone.status, 0, alone.stderr);

console.log('child: ok');

/** The pids of processes running the packaged child script. */
function liveChildren() {
  try {
    return execFileSync('pgrep', ['-f', script], { encoding: 'utf8' }).split('\n').filter(Boolean);
  } catch (error) {
    if (error.status === 1) return [];
    if (error.code !== 'ENOENT') throw error;
  }
  // No pgrep (a slim Linux image): read the command lines under /proc.
  return readdirSync('/proc').filter((pid) => {
    if (!/^\d+$/.test(pid)) return false;
    try {
      return readFileSync(`/proc/${pid}/cmdline`, 'utf8').includes(script);
    } catch {
      return false;
    }
  });
}
