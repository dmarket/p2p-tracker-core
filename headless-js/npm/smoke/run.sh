#!/usr/bin/env bash
#
# run.sh <tarball> [<work-dir>] — installs a packed @dmarket/p2p-tracker-headless into an empty directory and uses
# it the way a host does: imports the entry, builds a tracker with a prover, forks the packaged prover child over
# IPC against a stub notary, and type-checks a host file against the package's types.
#
# The tests in the build import the package from the build directory and hand the child script in explicitly. What
# only an installed package exercises — resolving the package root, the default prover files, `ws` as a dependency,
# the wasm under pkg/ — runs here.
#
# Needs node, npm and the network (to install `ws` and typescript). The work directory defaults to a fresh temporary
# one; the core distribution's VERSION, when built, is compared with the packaged one.
set -euo pipefail

if [[ $# -lt 1 || ! -f "$1" ]]; then
  echo "usage: $0 <tarball> [<work-dir>]" >&2
  exit 2
fi

SMOKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SMOKE_DIR/../../.." && pwd)"
TARBALL="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
WORK="${2:-$(mktemp -d)}"
TYPESCRIPT_VERSION="6.0.3"

mkdir -p "$WORK"
cd "$WORK"
echo "smoke: $(node --version) in $WORK"
npm init -y >/dev/null
npm pkg set type=module
npm install --no-audit --no-fund --loglevel=error "$TARBALL"
npm install --no-audit --no-fund --loglevel=error --save-dev "typescript@$TYPESCRIPT_VERSION"
cp "$SMOKE_DIR"/surface.mjs "$SMOKE_DIR"/tracker.mjs "$SMOKE_DIR"/child.mjs "$SMOKE_DIR"/types-check.ts "$WORK"/

node --input-type=module -e "import * as h from '@dmarket/p2p-tracker-headless'; console.log(Object.keys(h))"
node surface.mjs
node tracker.mjs
node child.mjs "$REPO_ROOT/headless-js/src/jsTest/resources/prover-ipc-prove.json" \
  "$REPO_ROOT/core/build/dist/js/productionLibrary/VERSION"
npx tsc --noEmit --strict --module nodenext --moduleResolution nodenext types-check.ts
echo "smoke: ok"
