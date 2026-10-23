// A tracker with a prover, from the installed package: the child script and the prover's files are found from the
// package itself, and a directory without them is refused when the tracker is built.
import * as headless from '@dmarket/p2p-tracker-headless';
import assert from 'node:assert/strict';
import { mkdtempSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';

setTimeout(() => {
  console.error('tracker: the process did not end after stop');
  process.exit(1);
}, 20_000).unref();

const temp = (name) => mkdtempSync(path.join(os.tmpdir(), `headless-smoke-${name}-`));

const tracker = headless.createHeadlessTracker({ dataDir: temp('data'), prover: {} });
const state = headless.trackerState(tracker);
assert.equal(state.hasTradingApiKey, false);
assert.deepEqual(state.trackedDealIds, []);
assert.deepEqual(state.dealDeadlines, {});
await headless.stop(tracker);

assert.throws(
  () => headless.createHeadlessTracker({ dataDir: temp('data'), prover: { assetsDir: temp('empty') } }),
  /prover files not found/,
);

console.log('tracker: ok (default prover files found; an empty assetsDir refused)');
