// The installed package's surface: the entry exports exactly the facade, the types declare exactly those names, and
// package.json points at the handwritten types and closes everything but its three subpaths.
import * as headless from '@dmarket/p2p-tracker-headless';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import path from 'node:path';

const FACADE = [
  'createHeadlessTracker',
  'cycle',
  'defaultBaseUrl',
  'defaultNotaryUrl',
  'directiveActions',
  'failedCancelKinds',
  'headlessVersion',
  'maxClockSkewMs',
  'minWriteTokenLifetimeMs',
  'pushSteamToken',
  'pushTradingApiKey',
  'reasons',
  'runProverChild',
  'start',
  'steamNotLinkedCode',
  'stop',
  'trackerState',
];

const require = createRequire(import.meta.url);
const root = path.dirname(require.resolve('@dmarket/p2p-tracker-headless/package.json'));
const pkg = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));

assert.deepEqual(Object.keys(headless).sort(), FACADE);

const types = readFileSync(path.join(root, 'index.d.ts'), 'utf8');
const declared = [...types.matchAll(/^export declare function (\w+)/gm)].map((m) => m[1]).sort();
assert.deepEqual(declared, FACADE, 'index.d.ts declares the facade');
assert.equal(/\bany\b/.test(types), false, 'index.d.ts leaves a type undescribed');
assert.equal(existsSync(path.join(root, 'p2p-tracker-headless.d.mts')), false, 'the generated declarations ship');

assert.equal(pkg.name, '@dmarket/p2p-tracker-headless');
assert.equal(pkg.type, 'module');
assert.equal(pkg.types, './index.d.ts');
assert.deepEqual(pkg.exports, {
  '.': { types: './index.d.ts', import: './p2p-tracker-headless.mjs', default: './p2p-tracker-headless.mjs' },
  './prover-child.mjs': './prover-child.mjs',
  './package.json': './package.json',
});
assert.deepEqual(pkg.dependencies, { ws: '8.22.0' });
assert.ok(pkg.version.startsWith(headless.headlessVersion()), `${pkg.version} is not ${headless.headlessVersion()}`);

await assert.rejects(import('@dmarket/p2p-tracker-headless/p2p-tracker-core.mjs'), { code: 'ERR_PACKAGE_PATH_NOT_EXPORTED' });

console.log(`surface: ok (${pkg.name}@${pkg.version}, ${FACADE.length} names)`);
