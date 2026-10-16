#!/usr/bin/env bash
# Maintainer intake of contributed capture files (docs/capture-guide.md, docs/profile-steward.md).
#
#   tools/capture-intake/intake.sh --input DIR [--store DIR] [--apply] [--link GENERATION=PROFILE-ID]... [--branch NAME]
#
# Contributors share their capture JSON to a Drive or Slack folder; a maintainer downloads it and runs this. It runs the
# app's own redaction lint, strict JSON decode and fingerprint check (Kotlin, through the Gradle task
# :composeApp:captureIntake: there is no second copy of any rule here), rejects bad files with their violation list and
# quarantines them in DIR/rejected/, stores good ones in the RESTRICTED per-capture store (outside the repository), merges
# them per generation into profiles/, runs the hold-out rule and the fidelity diff, regenerates the evidence and coverage
# docs, and prints a report.
#
# DRY RUN BY DEFAULT: nothing is written or moved until --apply.
#
# --branch NAME (needs --apply) makes a LOCAL branch and ONE local commit of the profile and generated-doc changes
# (never the per-capture records, which are not in the repository, and never profiles/pending/). It does not push. It
# prints the push command for you to run yourself, and opening the pull request is left to you after that.
#
# This script never runs git push, and nothing here does.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

input=""
store="${CAPTURE_STORE:-$HOME/captures-store}"
repo="$ROOT"
apply=false
branch=""
links=()

usage() {
  sed -n '2,19p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

die() { echo "intake: $*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --input)  [ $# -ge 2 ] || die "--input needs a value"; input="$2"; shift 2 ;;
    --store)  [ $# -ge 2 ] || die "--store needs a value"; store="$2"; shift 2 ;;
    --repo)   [ $# -ge 2 ] || die "--repo needs a value"; repo="$2"; shift 2 ;;
    --link)   [ $# -ge 2 ] || die "--link needs GENERATION=PROFILE-ID"; links+=("$2"); shift 2 ;;
    --branch) [ $# -ge 2 ] || die "--branch needs a name"; branch="$2"; shift 2 ;;
    --apply)  apply=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; die "unknown argument: $1" ;;
  esac
done

[ -n "$input" ] || { usage >&2; die "--input is required"; }
[ -d "$input" ] || die "the inbox $input is not a directory"
[ -d "$repo" ] || die "the repository $repo is not a directory"
if [ -n "$branch" ] && [ "$apply" != true ]; then die "--branch needs --apply (a dry run writes nothing, so there is nothing to commit)"; fi

abs() { (cd "$1" && pwd); }
input="$(abs "$input")"
repo="$(abs "$repo")"
case "$store" in /*) ;; *) store="$(pwd)/$store" ;; esac

# The paths a commit may contain: the shared profiles and the pages generated from them, nothing else
gen_paths=(profiles docs/chip-profile-evidence.measured.md docs/coverage-matrix.md docs/coverage-matrix.json)
exclude=(':(exclude)profiles/pending')

if [ -n "$branch" ]; then
  git -C "$repo" rev-parse --git-dir >/dev/null 2>&1 || die "$repo is not a git repository"
  if git -C "$repo" show-ref --verify --quiet "refs/heads/$branch"; then die "the branch $branch already exists"; fi
  # Their changes to these files would end up in the commit: make the maintainer deal with them first
  if [ -n "$(git -C "$repo" status --porcelain -- "${gen_paths[@]}" "${exclude[@]}")" ]; then
    git -C "$repo" status --short -- "${gen_paths[@]}" "${exclude[@]}" >&2
    die "uncommitted changes in the files the intake writes (listed above); commit or stash them first, or run without --branch"
  fi
fi

log="$(mktemp "${TMPDIR:-/tmp}/capture-intake.XXXXXX")"
trap 'rm -f "$log"' EXIT

gradle_args=(-q --console=plain ":composeApp:captureIntake"
  "-Pintake.input=$input" "-Pintake.store=$store" "-Pintake.repo=$repo")
if [ "$apply" = true ]; then gradle_args+=("-Pintake.apply=true"); fi
if [ ${#links[@]} -gt 0 ]; then
  joined="$(IFS=,; echo "${links[*]}")"
  gradle_args+=("-Pintake.links=$joined")
fi

out="$(mktemp "${TMPDIR:-/tmp}/capture-intake-out.XXXXXX")"
trap 'rm -f "$log" "$out"' EXIT
if ! (cd "$ROOT" && ./gradlew "${gradle_args[@]}" >"$out" 2>"$log"); then
  cat "$out"
  echo "--- gradle / tool messages (last 40 lines) ---" >&2
  tail -n 40 "$log" >&2
  die "the intake tool failed; nothing was committed"
fi
cat "$out"

if [ -n "$branch" ]; then
  # Rejected files are quarantined and do not change the profiles; a deviation in the diff must be looked at first
  if grep -q '^RESULT: DEVIATIONS' "$out"; then
    echo
    echo "intake: the fidelity diff shows deviations (above); not creating a branch. Fix or allow them (docs/fidelity-allowed-deviations.json) first." >&2
    exit 3
  fi
  if [ -z "$(git -C "$repo" status --porcelain -- "${gen_paths[@]}" "${exclude[@]}")" ]; then
    echo
    echo "Nothing changed in profiles/ or the generated docs: no branch created."
    exit 0
  fi
  git -C "$repo" switch -c "$branch" >/dev/null
  git -C "$repo" add -A -- "${gen_paths[@]}" "${exclude[@]}"
  message="Merge captures into measured profiles

Made by tools/capture-intake/intake.sh. Per-capture records are in the restricted store and are not part of this commit."
  git -C "$repo" commit --quiet -m "$message" -- "${gen_paths[@]}" "${exclude[@]}"
  echo
  echo "Created the local branch '$branch' with one commit:"
  git -C "$repo" show --stat --format='%h %s' HEAD | sed 's/^/  /'
  echo
  echo "Not pushed. Now push it yourself:"
  echo "  git -C \"$repo\" push -u origin $branch"
  echo "Then open the pull request (template: .github/pull_request_template.md, profile PRs section)."
fi

if ! grep -q '^RESULT: CLEAN' "$out"; then exit 3; fi
