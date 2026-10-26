#!/usr/bin/env bash
#
# delete-release-branch.sh — the CircleCI `delete_release_branch` job: the last step of a release. Once
# the release is published, its `release/v…` branch has done its job, and it is deleted so the branch
# list holds only releases still in progress. The extension repository runs the same script under the
# same name for its stable releases; this one also deletes snapshot branches, which the extension never has.
#
# Called as `bash scripts/ci/delete-release-branch.sh` from .circleci/config.yml.
#
# Nothing is lost by the deletion, and the script checks that before it deletes:
#   - a stable release (`release/vX.Y.Z`) keeps its commit in the tag `vX.Y.Z`. The branch is NOT merged
#     anywhere — the version bump and the CHANGELOG section land on `main` through a pull request before
#     the branch is cut, so the branch carries no commit `main` needs.
#   - a snapshot (`release/vX.Y.Z-SNAPSHOT`, `release/vX.Y.Z-SNAPSHOT-<label>`) is never tagged, so its
#     commit must be on another, non-release branch: `main`, or the branch a labelled snapshot was built
#     from. A snapshot branch goes even when its run published nothing (`[skip publish]`, a commit count
#     already on npm); the next snapshot push creates it again.
# And the branch must still point at the commit this pipeline built: a newer push to it, waiting for its
# own approval, is left alone.
#
# CONTRACT: exits 0 when the branch is deleted, already gone, or kept because deleting it would lose
# something — a stable commit no tag holds (a `[skip publish]` commit on top of a released tag), a
# snapshot commit no other branch holds, a branch that moved on — and non-zero only when a lookup or the
# deletion itself failed. An approval that was never given never reaches this job.
#
# Environment:
#   REPO_SLUG      owner/repo (set by the executor in .circleci/config.yml)
#   GITHUB_TOKEN   contents:write — the same credential `publish_npm` pushes the tag with
#   CIRCLE_BRANCH  the release branch (`release/vX.Y.Z`, `release/vX.Y.Z-SNAPSHOT[-<label>]`)
#   CIRCLE_SHA1    the commit this pipeline built and released
set -euo pipefail

: "${REPO_SLUG:?REPO_SLUG must be set (the CircleCI executor sets it)}"
: "${CIRCLE_BRANCH:?CIRCLE_BRANCH must be set — this job runs on release branches only}"
: "${CIRCLE_SHA1:?CIRCLE_SHA1 must be set}"
: "${GITHUB_TOKEN:?GITHUB_TOKEN must be set (CircleCI project environment variable)}"

# A trailing newline inside the push URL is a bare 403.
GITHUB_TOKEN="$(printf '%s' "$GITHUB_TOKEN" | tr -d '[:space:]')"
REMOTE="https://x-access-token:${GITHUB_TOKEN}@github.com/${REPO_SLUG}.git"

# The workflow filter already limits this job to release branches; checked again because deleting the
# wrong branch is the one mistake this script can make.
case "$CIRCLE_BRANCH" in
  release/v*) ;;
  *)
    echo "ERROR: $CIRCLE_BRANCH is not a release branch; refusing to delete it." >&2
    exit 1
    ;;
esac

# Same parse as core/npm/prepare-package.sh.
VERSION_NAME="$(grep -E '^VERSION_NAME=' gradle.properties | head -n1 | cut -d= -f2 | tr -d '[:space:]')"
if [[ -z "${VERSION_NAME:-}" ]]; then
  echo "ERROR: VERSION_NAME not found in gradle.properties." >&2
  exit 1
fi

# HOLDER = what keeps the released commit reachable once the branch is gone.
if [[ "$VERSION_NAME" == *-SNAPSHOT ]]; then
  # Release branches do not count as a holder: each of them is deleted the same way.
  git fetch -q "$REMOTE" '+refs/heads/*:refs/remotes/github/*' '^refs/heads/release/*'
  HOLDER="$(git for-each-ref --contains "$CIRCLE_SHA1" --count=1 --format='%(refname:lstrip=3)' refs/remotes/github/)"
  if [[ -z "$HOLDER" ]]; then
    echo "Keeping $CIRCLE_BRANCH: no other branch holds $CIRCLE_SHA1, so deleting this one would lose it."
    exit 0
  fi
  HOLDER="branch $HOLDER"
else
  TAG="v$VERSION_NAME"
  # `ls-remote --exit-code` answers 2 for "no such ref" and something else for a failed request, and
  # only the first is a clean skip: a network or token failure must not pass for "nothing was released".
  rc=0
  git ls-remote --exit-code --tags "$REMOTE" "refs/tags/$TAG" >/dev/null || rc=$?
  case "$rc" in
    0) git fetch -q "$REMOTE" "+refs/tags/$TAG:refs/tags/$TAG" ;;
    2)
      echo "Keeping $CIRCLE_BRANCH: tag $TAG does not exist, so this branch released nothing."
      exit 0
      ;;
    *)
      echo "ERROR: could not ask GitHub whether tag $TAG exists (git exit $rc); keeping $CIRCLE_BRANCH." >&2
      exit 1
      ;;
  esac

  # A tag cut from some other commit means this push released nothing, and deleting the branch would
  # drop commits no tag holds.
  if ! git merge-base --is-ancestor "$CIRCLE_SHA1" "$TAG^{commit}"; then
    echo "Keeping $CIRCLE_BRANCH: $CIRCLE_SHA1 is not part of $TAG, so deleting the branch would lose it."
    exit 0
  fi
  HOLDER="tag $TAG"
fi

# A newer push to this branch, waiting for its own approval, is not this job's to delete. The lease
# closes the gap between this read and the push.
rc=0
TIP="$(git ls-remote --exit-code --heads "$REMOTE" "refs/heads/$CIRCLE_BRANCH")" || rc=$?
case "$rc" in
  0) ;;
  2)
    echo "$CIRCLE_BRANCH is already gone."
    exit 0
    ;;
  *)
    echo "ERROR: could not ask GitHub whether $CIRCLE_BRANCH exists (git exit $rc)." >&2
    exit 1
    ;;
esac
TIP="${TIP%%[[:space:]]*}"
if [[ "$TIP" != "$CIRCLE_SHA1" ]]; then
  echo "Keeping $CIRCLE_BRANCH: it has moved on to $TIP since $CIRCLE_SHA1 was built."
  exit 0
fi
git push -q --force-with-lease="refs/heads/$CIRCLE_BRANCH:$CIRCLE_SHA1" "$REMOTE" --delete "refs/heads/$CIRCLE_BRANCH"
echo "Deleted $CIRCLE_BRANCH; $CIRCLE_SHA1 stays reachable from $HOLDER."
