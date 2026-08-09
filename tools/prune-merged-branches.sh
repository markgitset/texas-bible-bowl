#!/usr/bin/env bash
# Collect the local branches and worktrees left behind by merged PRs.
#
#   tools/prune-merged-branches.sh           # dry run: report only, change nothing
#   tools/prune-merged-branches.sh --apply   # actually delete
#
# Why this exists: `gh pr merge --delete-branch` deletes the REMOTE branch, but squash-merging
# rewrites the SHA, so `git branch -d` can't prove the merge and refuses. Nothing ever collects
# the local side, and it accumulates one branch (and often one worktree) per session — 120 of
# them by 2026-08. The merging session can't clean up after itself either: it is standing inside
# a worktree on the branch being merged. So this runs later, from the primary worktree.
#
# A branch is deleted only if it passes one of two independent tests:
#   1. `git cherry` finds every one of its commits already upstream by patch-id. Holds for a
#      squashed single-commit PR, which is most of them.
#   2. Its name is the head branch of a MERGED pull request. This is the fallback for
#      multi-commit PRs, where squashing collapses N patches into one and defeats test 1.
# Anything that passes neither is reported and kept — including branches whose tip commit
# postdates their PR merge, which is the "I kept working after it merged" case.
set -euo pipefail
cd "$(dirname "$0")/.."

APPLY=false
[ "${1:-}" = "--apply" ] && APPLY=true

git fetch -q origin main --prune

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

git for-each-ref --format='%(refname:short) %(objectname)' refs/heads/ > "$WORK/branches"
gh pr list --state merged --limit 400 --json headRefName,mergedAt \
  --jq '.[]|"\(.headRefName)\t\(.mergedAt)"' | sort -u > "$WORK/merged-prs"
# A branch checked out in ANY worktree (including this one) can't be deleted and mustn't be.
git worktree list --porcelain | awk '/^branch /{sub("refs/heads/","",$2); print $2}' \
  | sort -u > "$WORK/in-use"

# The restore map: every branch and its SHA, so a mistake is `git branch <name> <sha>` away for
# as long as git keeps the unreachable objects (~90 days by default).
BACKUP="$(git rev-parse --git-common-dir)/pruned-branches-backup.txt"
cp "$WORK/branches" "$BACKUP"

delete=() ; kept=()
while read -r branch sha; do
  if grep -qxF "$branch" "$WORK/in-use"; then
    kept+=("$branch|checked out in a worktree"); continue
  fi
  pr_line=$(grep -P "^\Q$branch\E\t" "$WORK/merged-prs" | sort -k2 -r | head -1 || true)

  if [ "$(git cherry origin/main "$branch" | grep -c '^+')" = 0 ]; then
    delete+=("$branch|all commits upstream")
  elif [ -n "$pr_line" ] && [[ "$(git log -1 --format=%cI "$sha")" > "$(echo "$pr_line" | cut -f2)" ]]; then
    # Merged, but there are commits newer than the merge — could be work added afterwards.
    kept+=("$branch|tip postdates its PR merge, review by hand")
  elif [ -n "$pr_line" ]; then
    delete+=("$branch|squash-merged PR")
  else
    kept+=("$branch|unique commits, no merged PR")
  fi
done < "$WORK/branches"

# Returns 0 even for an empty list — an empty set is a normal outcome, not a failure for `set -e`.
report() {
  [ $# -eq 0 ] && return 0
  printf '%s\n' "$@" | awk -F'|' -v tag="$TAG" '{printf "  %-7s %-42s %s\n", tag, $1, $2}'
}
TAG=KEEP   report ${kept[@]+"${kept[@]}"}
TAG=DELETE report ${delete[@]+"${delete[@]}"}
echo "  ---- ${#delete[@]} to delete, ${#kept[@]} kept, restore map at $BACKUP"

# Worktrees whose directory is gone are always safe to forget; git refuses the rest anyway.
echo "  ---- prunable worktrees:"; git worktree prune --dry-run -v | sed 's/^/  /' || true

if ! $APPLY; then
  echo "  ---- dry run; re-run with --apply to delete"
  exit 0
fi

if [ ${#delete[@]} -gt 0 ]; then
  printf '%s\n' "${delete[@]}" | cut -d'|' -f1 | xargs -d '\n' git branch -D
fi
git worktree prune -v
