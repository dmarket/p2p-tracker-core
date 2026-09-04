#!/usr/bin/env bash
#
# prepare-package.sh — bridge the Kotlin/JS distribution into a publishable npm package.
#
# The Kotlin/JS generator emits core/build/dist/js/productionLibrary/package.json with a bad name
# ("dmarket-p2p-tracker-core-core") and version ("0.0.0-unspecified"). This script overlays the
# checked-in package.partial.json (scoped name, license, repo, publishConfig, …) and stamps the
# effective version derived from gradle.properties:VERSION_NAME.
#
# Version rules:
#   - "<base>-SNAPSHOT"  -> "<base>-SNAPSHOT.<commit-count>" under dist-tag "snapshot"
#     (unique per commit, so npm never rejects a re-publish).
#   - "<base>"           -> "<base>" under dist-tag "latest".
#
# Opt-out flag:
#   A commit message containing "[skip publish]" (case-insensitive; "[skip-publish]" / "[publish skip]"
#   also work) sets SKIP_PUBLISH=true, which makes the publish job a clean no-op — build, tests and
#   this packaging step still run. Read from HEAD (a squash-merge carries the PR title/body) and, for
#   a true merge commit, from the merged-in commits too. Presetting SKIP_PUBLISH in the environment
#   overrides the commit scan (for a forced CI re-run).
#
# Outputs (for a later, separate publish job that may run on a different image):
#   - rewrites the dist package.json in place,
#   - writes $REPO_ROOT/release.env with `export VERSION/DIST_TAG/IS_SNAPSHOT/SKIP_PUBLISH` (persisted
#     via the CircleCI workspace and `cat release.env >> $BASH_ENV` in the publish job),
#   - also appends the same exports to $BASH_ENV when running inside CircleCI (same-job convenience).
#
# Uses Node (present on both the JDK-node and the Node images) — no jq dependency.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DIST_DIR="$REPO_ROOT/core/build/dist/js/productionLibrary"
PARTIAL="$SCRIPT_DIR/package.partial.json"
GENERATED="$DIST_DIR/package.json"

if [[ ! -f "$GENERATED" ]]; then
  echo "ERROR: $GENERATED not found." >&2
  echo "       Run ./gradlew :core:jsBrowserProductionLibraryDistribution first." >&2
  exit 1
fi

VERSION_NAME="$(grep -E '^VERSION_NAME=' "$REPO_ROOT/gradle.properties" | head -n1 | cut -d= -f2 | tr -d '[:space:]')"
if [[ -z "${VERSION_NAME:-}" ]]; then
  echo "ERROR: VERSION_NAME not found in gradle.properties." >&2
  exit 1
fi

# --- "[skip publish]" opt-out ---------------------------------------------------------------------
# An explicit SKIP_PUBLISH from the environment wins; otherwise scan the commit message(s).
if [[ -z "${SKIP_PUBLISH:-}" ]]; then
  SKIP_PUBLISH="false"
  COMMIT_MESSAGES=""
  if git -C "$REPO_ROOT" rev-parse --verify -q HEAD >/dev/null 2>&1; then
    COMMIT_MESSAGES="$(git -C "$REPO_ROOT" log -1 --pretty=%B HEAD 2>/dev/null || true)"
    # A true merge commit's own message is boilerplate ("Merge pull request #12 …") — the flag, if any,
    # lives on the commits being merged in. (Squash-merges need no special case: HEAD carries the text.)
    if git -C "$REPO_ROOT" rev-parse --verify -q 'HEAD^2' >/dev/null 2>&1; then
      COMMIT_MESSAGES+="
$(git -C "$REPO_ROOT" log --pretty=%B 'HEAD^1..HEAD^2' 2>/dev/null || true)"
    fi
  fi
  if printf '%s' "$COMMIT_MESSAGES" |
    grep -Eiq '\[[[:space:]]*(skip[[:space:]_-]*publish|publish[[:space:]_-]*skip)[[:space:]]*\]'; then
    SKIP_PUBLISH="true"
  fi
fi

if [[ "$VERSION_NAME" == *-SNAPSHOT ]]; then
  BASE="${VERSION_NAME%-SNAPSHOT}"
  COMMIT_COUNT="$(git -C "$REPO_ROOT" rev-list --count HEAD)"
  VERSION="${BASE}-SNAPSHOT.${COMMIT_COUNT}"
  DIST_TAG="snapshot"
  IS_SNAPSHOT="true"
else
  VERSION="$VERSION_NAME"
  DIST_TAG="latest"
  IS_SNAPSHOT="false"
fi

# Shallow-merge: generated (main/types/dependencies/…) is the base, partial overrides, version stamped.
# The partial deliberately overrides "dependencies" with {}: the Kotlin/JS generator declares an exact
# pin on `ws` (Node websocket plumbing for ktor-client-js) that the browser distribution never imports —
# there is not a single require()/import of it in the shipped chunks. Shipping that phantom pin only
# forwarded ws advisories to every consumer and, because it is exact, npm could not resolve a fix for
# them (`npm audit fix --force` died on an undefined@undefined target). Re-add an entry here — not in
# the generated file — if a real runtime dependency ever appears.
# Then declare an explicit modern-ESM entry point derived from the merged main/types (robust to the
# generated filename): "type": "module", a "module" alias, and an "exports" map so consumers resolve a
# single well-defined root. The whole productionLibrary dir still ships (the entry imports sibling
# chunks), so no "files" allowlist is added.
node -e '
  const fs = require("fs");
  const [gen, partial, version] = process.argv.slice(1);
  const g = JSON.parse(fs.readFileSync(gen, "utf8"));
  const p = JSON.parse(fs.readFileSync(partial, "utf8"));
  const merged = { ...g, ...p, version };
  merged.type = "module";
  if (merged.main) merged.module = merged.main;
  if (merged.main) {
    merged.exports = {
      ".": {
        ...(merged.types ? { types: "./" + merged.types } : {}),
        import: "./" + merged.main,
        default: "./" + merged.main,
      },
    };
  }
  fs.writeFileSync(gen, JSON.stringify(merged, null, 2) + "\n");
' "$GENERATED" "$PARTIAL" "$VERSION"

echo "Prepared $GENERATED:"
node -e 'const p=require(process.argv[1]);console.log(JSON.stringify({name:p.name,version:p.version,type:p.type,main:p.main,module:p.module,types:p.types,exports:p.exports,dependencies:p.dependencies},null,2))' "$GENERATED"

# Emit release vars for the publish job (workspace file) and, if same-job, $BASH_ENV.
{
  echo "export VERSION='$VERSION'"
  echo "export DIST_TAG='$DIST_TAG'"
  echo "export IS_SNAPSHOT='$IS_SNAPSHOT'"
  echo "export SKIP_PUBLISH='$SKIP_PUBLISH'"
} >"$REPO_ROOT/release.env"
[[ -n "${BASH_ENV:-}" ]] && cat "$REPO_ROOT/release.env" >>"$BASH_ENV"

echo "VERSION=$VERSION"
echo "DIST_TAG=$DIST_TAG"
echo "IS_SNAPSHOT=$IS_SNAPSHOT"
echo "SKIP_PUBLISH=$SKIP_PUBLISH"
if [[ "$SKIP_PUBLISH" == "true" ]]; then
  echo "  -> [skip publish] requested: the publish job will be a no-op for this commit."
fi
