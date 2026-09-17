#!/usr/bin/env bash
# session-start.sh — branch & deploy hygiene check for After Duty.
#
# Runs the four core git-state checks plus unmerged-branch / stale-branch
# checks, to catch deploy/main drift and orphaned feature branches before
# you start work.
#
# Exit code:
#   0 — clean, no drift detected
#   1 — drift found (commits ahead/behind, dirty tree, or stale branches)
#
# Run from the project root with no arguments to check this repo, or pass
# one or more repo paths explicitly:
#
#   ./tools/session-start.sh               # checks the current project
#   ./tools/session-start.sh /path/to/repo # checks the given repo(s)

set -u

# ANSI colors, but only when stdout is a tty (so pipes/CI stay clean).
if [[ -t 1 ]]; then
  R=$'\033[0;31m'; G=$'\033[0;32m'; Y=$'\033[0;33m'; B=$'\033[1m'; X=$'\033[0m'
else
  R=""; G=""; Y=""; B=""; X=""
fi

# Considered "stale" — surface to user. Tweak if you want a tighter / looser bar.
STALE_COMMIT_DAYS="${STALE_COMMIT_DAYS:-7}"
STALE_COMMIT_COUNT="${STALE_COMMIT_COUNT:-5}"

EXIT=0

check_repo() {
  local repo="$1"
  if [[ ! -d "$repo/.git" ]]; then
    echo "${Y}skip${X} $repo (not a git repo)"
    return
  fi
  echo
  echo "${B}═══ $repo ═══${X}"
  cd "$repo" || return 1

  echo "→ git fetch"
  git fetch --quiet 2>&1 | sed 's/^/  /'

  local branch
  branch=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "?")
  echo "branch: $branch"

  # 1. Working tree clean?
  if ! git diff --quiet || ! git diff --staged --quiet; then
    echo "${R}✗${X} working tree has uncommitted changes — run \`git status\`"
    EXIT=1
  else
    echo "${G}✓${X} working tree clean"
  fi

  # 2. Local ahead of upstream?
  local ahead behind
  ahead=$(git rev-list --count '@{u}..HEAD' 2>/dev/null || echo "?")
  behind=$(git rev-list --count 'HEAD..@{u}' 2>/dev/null || echo "?")
  if [[ "$ahead" == "?" ]]; then
    echo "${Y}!${X} no upstream tracking branch for $branch"
  elif [[ "$ahead" -gt 0 ]]; then
    echo "${R}✗${X} $ahead local commit(s) NOT pushed to upstream:"
    git log --oneline '@{u}..HEAD' | sed 's/^/    /'
    EXIT=1
  else
    echo "${G}✓${X} no unpushed local commits"
  fi

  if [[ "$behind" == "?" ]]; then
    : # already warned above
  elif [[ "$behind" -gt 0 ]]; then
    echo "${R}✗${X} $behind upstream commit(s) NOT pulled:"
    git log --oneline 'HEAD..@{u}' | sed 's/^/    /'
    EXIT=1
  else
    echo "${G}✓${X} up-to-date with upstream"
  fi

  # 3. Unmerged feature branches — the May 8 case.
  local unmerged
  unmerged=$(git branch --no-merged main 2>/dev/null | sed 's/^[* ]*//' | grep -v '^$' || true)
  if [[ -z "$unmerged" ]]; then
    echo "${G}✓${X} no branches unmerged into main"
  else
    echo "${B}unmerged branches (vs main):${X}"
    local issues=0
    while IFS= read -r br; do
      [[ -z "$br" ]] && continue
      local n_commits last_iso last_age
      n_commits=$(git rev-list --count "main..$br" 2>/dev/null || echo 0)
      last_iso=$(git log -1 --format='%cI' "$br" 2>/dev/null || echo "")
      if [[ -z "$last_iso" ]]; then
        last_age="?"
      else
        last_age=$(( ( $(date -u +%s) - $(date -j -f '%Y-%m-%dT%H:%M:%S%z' "${last_iso}" +%s 2>/dev/null || echo $(date -u +%s)) ) / 86400 ))
      fi
      local marker="${G}·${X}"
      if [[ "$n_commits" -ge "$STALE_COMMIT_COUNT" ]] || [[ "$last_age" != "?" && "$last_age" -ge "$STALE_COMMIT_DAYS" ]]; then
        marker="${R}!${X}"; issues=$((issues+1))
      fi
      printf "  %b %-40s  %3d commits  last: %sd ago\n" "$marker" "$br" "$n_commits" "$last_age"
    done <<< "$unmerged"
    if [[ "$issues" -gt 0 ]]; then
      echo "${R}✗${X} $issues stale or significant unmerged branch(es) — surface to user before working"
      EXIT=1
    fi
  fi

  # 4. Recently active branches (information only).
  echo "${B}recent branch activity (top 5):${X}"
  git for-each-ref --sort=-committerdate --count=5 \
      --format='  %(committerdate:short) %(refname:short) (%(authorname))' \
      refs/heads/
}

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ "$#" -ge 1 ]]; then
  for r in "$@"; do check_repo "$r"; done
else
  check_repo "$ROOT"
fi

echo
if [[ "$EXIT" -eq 0 ]]; then
  echo "${G}clean — safe to start work${X}"
else
  echo "${R}drift detected — review the items above with the user before any further action${X}"
fi
exit "$EXIT"
