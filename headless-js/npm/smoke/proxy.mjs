// A tracker whose Steam traffic goes through a proxy, from the installed package: `undici` must resolve as one of the
// package's own dependencies, the Steam calls must reach the proxy as CONNECTs (refused here, so nothing leaves the
// machine) and DMarket must be reached directly. A proxy refusing every CONNECT is unreachable, and the state says so.
import * as headless from '@dmarket/p2p-tracker-headless';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';

setTimeout(() => {
  console.error('proxy: no CONNECT reached the proxy in time');
  process.exit(1);
}, 30_000).unref();

const STEAM_ID = '76561198000000001';
// RFC 8032 TEST 1: a real Ed25519 pair, so the package's own signer takes it.
const PUBLIC_KEY = 'd75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a';
const SECRET_SEED = '9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60';
const NOW_MS = 1_781_611_200_000;

const encode = (text) => Buffer.from(text).toString('base64url');
const token = [
  encode('{"alg":"EdDSA","typ":"JWT"}'),
  encode(JSON.stringify({ iss: 'r:smoke', sub: STEAM_ID, aud: ['web', 'mobile'], exp: NOW_MS / 1000 + 86_400, iat: NOW_MS / 1000 })),
  'c2lnbmF0dXJl',
].join('.');

const listen = (server) => new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server.address().port)));

const dmarketSeen = [];
const dmarket = http.createServer((request, response) => {
  dmarketSeen.push(request.url);
  request.resume();
  request.on('end', () => {
    response.writeHead(200, { 'content-type': 'application/json' });
    response.end(request.url.endsWith('/p2p/ext/heartbeat')
      ? JSON.stringify({ activeTracking: [{ dealId: 'deal-1', watch: ['GetTradeOffer'], role: 'seller' }], directives: [], ttlSeconds: 100, linkedSteamId: STEAM_ID })
      : '{"results":[]}');
  });
});
const dmarketPort = await listen(dmarket);

const connects = [];
const proxy = http.createServer((_, response) => response.writeHead(405).end());
proxy.on('connect', (request, socket) => {
  connects.push({ target: request.url, auth: request.headers['proxy-authorization'] ?? null });
  socket.end('HTTP/1.1 502 Refused\r\n\r\n');
});
const proxyPort = await listen(proxy);

const tracker = headless.createHeadlessTracker({
  dataDir: mkdtempSync(path.join(os.tmpdir(), 'headless-smoke-proxy-')),
  baseUrl: `http://127.0.0.1:${dmarketPort}`,
  nowMs: () => NOW_MS,
});
await headless.pushTradingApiKey(tracker, PUBLIC_KEY, SECRET_SEED);
const held = await headless.pushSteamToken(tracker, token, { proxy: `http://smoke:smoke-pass@127.0.0.1:${proxyPort}` });
assert.equal(held.type, 'held');

while (connects.length === 0 || !headless.trackerState(tracker).steamProxyUnreachable) {
  await new Promise((resolve) => setTimeout(resolve, 50));
}

assert.ok(dmarketSeen.some((url) => url.endsWith('/p2p/ext/heartbeat')), 'DMarket was reached directly');
assert.ok(connects.every(({ target }) => /^(steamcommunity\.com|api\.steampowered\.com):443$/.test(target)), JSON.stringify(connects));
assert.ok(connects.every(({ auth }) => auth === `Basic ${Buffer.from('smoke:smoke-pass').toString('base64')}`));
assert.ok(!JSON.stringify(headless.trackerState(tracker)).includes('smoke-pass'));

await headless.stop(tracker);
dmarket.close();
proxy.close();
console.log(`proxy: ok (${connects.length} CONNECTs through the proxy, DMarket direct)`);
