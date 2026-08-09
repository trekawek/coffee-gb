#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HARNESS_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

START_COMMIT="${1:-ba648de56d4df327fc408a17912a059a9cc5b1d3}"
END_COMMIT="${2:-6ef530f5f28659ebf0fda4bc9c2b80673388ff12}"
WORKERS="${HARRY_POTTER_WORKERS:-4}"
TARGET_FRAMES="${HARRY_POTTER_TARGET_FRAMES:-1800}"
DEFAULT_ROM="Z:/emu/roms/gbc/H/Harry Potter and the Sorcerer's Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc"
ROM_PATH="${HARRY_POTTER_ROM:-$DEFAULT_ROM}"
RUN_ID="$(date +%Y%m%d-%H%M%S)-$$"
RESULTS_DIR="${HARRY_POTTER_RESULTS_DIR:-${HARNESS_ROOT}/target/harry-potter-method-calls-${RUN_ID}}"
RESULTS_FILE="${RESULTS_DIR}/method-call-results.txt"
SUMMARY_FILE="${RESULTS_DIR}/complexity-summary.txt"

if [[ ! "$WORKERS" =~ ^[1-9][0-9]*$ ]]; then
  echo "HARRY_POTTER_WORKERS must be a positive integer" >&2
  exit 2
fi
if [[ ! "$TARGET_FRAMES" =~ ^[1-9][0-9]*$ ]]; then
  echo "HARRY_POTTER_TARGET_FRAMES must be a positive integer" >&2
  exit 2
fi
if [[ ! -f "$ROM_PATH" ]]; then
  echo "Harry Potter ROM not found: $ROM_PATH" >&2
  echo "Set HARRY_POTTER_ROM to the ROM's absolute path." >&2
  exit 2
fi
if [[ -e "$RESULTS_DIR" ]]; then
  echo "Results directory already exists: $RESULTS_DIR" >&2
  exit 2
fi

mapfile -t COMMITS < <(
  git -C "$HARNESS_ROOT" rev-list --first-parent --reverse "${START_COMMIT}^..${END_COMMIT}"
)
if ((${#COMMITS[@]} == 0)); then
  echo "No commits found in the requested first-parent range" >&2
  exit 2
fi
if ((${#COMMITS[@]} < WORKERS)); then
  WORKERS="${#COMMITS[@]}"
fi

mkdir -p "$RESULTS_DIR"
WORKTREE_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-method-calls.XXXXXX")"
declare -a WORKTREES=()

cleanup() {
  for worktree in "${WORKTREES[@]:-}"; do
    if [[ -n "$worktree" ]]; then
      git -C "$HARNESS_ROOT" worktree remove --force "$worktree" >/dev/null 2>&1 || true
    fi
  done
  git -C "$HARNESS_ROOT" worktree prune >/dev/null 2>&1 || true
  rmdir "$WORKTREE_ROOT" >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "Building the method-call agent once before starting concurrent workers..."
mvn -q -f "${SCRIPT_DIR}/method-call-agent/pom.xml" package

for ((index = 0; index < ${#COMMITS[@]}; index++)); do
  worker=$((index % WORKERS))
  echo "${COMMITS[$index]}" >> "${RESULTS_DIR}/commits-${worker}.txt"
done

run_worker() {
  local worker="$1"
  local worktree="$2"
  local commit

  while IFS= read -r commit; do
    git -C "$worktree" checkout --quiet --detach --force "$commit"
    echo "worker=${worker} commit=${commit}" >&2
    (
      cd "$worktree"
      HARRY_POTTER_ROM="$ROM_PATH" \
      HARRY_POTTER_BATTERY_SAVE="${HARRY_POTTER_BATTERY_SAVE:-}" \
      HARRY_POTTER_TARGET_FRAMES="$TARGET_FRAMES" \
        bash "${SCRIPT_DIR}/count-harry-potter-intro-method-calls.sh"
    ) >> "${RESULTS_DIR}/worker-${worker}.txt" \
      2>> "${RESULTS_DIR}/worker-${worker}.log"
  done < "${RESULTS_DIR}/commits-${worker}.txt"
}

declare -a PIDS=()
for ((worker = 0; worker < WORKERS; worker++)); do
  worktree="${WORKTREE_ROOT}/worker-${worker}"
  git -C "$HARNESS_ROOT" worktree add --quiet --detach "$worktree" "$START_COMMIT"
  WORKTREES+=("$worktree")
  run_worker "$worker" "$worktree" &
  PIDS+=("$!")
done

failed=false
for pid in "${PIDS[@]}"; do
  if ! wait "$pid"; then
    failed=true
  fi
done
if [[ "$failed" == true ]]; then
  echo "At least one worker failed. Logs are in: $RESULTS_DIR" >&2
  exit 1
fi

: > "$RESULTS_FILE"
for ((worker = 0; worker < WORKERS; worker++)); do
  cat "${RESULTS_DIR}/worker-${worker}.txt" >> "$RESULTS_FILE"
done

expected=${#COMMITS[@]}
actual="$(grep -c '^METHOD_CALL_RESULT ' "$RESULTS_FILE" || true)"
if [[ "$actual" -ne "$expected" ]]; then
  echo "Expected $expected results, found $actual in $RESULTS_FILE" >&2
  exit 1
fi

if command -v pwsh >/dev/null 2>&1; then
  POWERSHELL=pwsh
  RANK_SCRIPT="${SCRIPT_DIR}/rank-method-call-complexity.ps1"
  PS_RESULTS="$RESULTS_FILE"
  PS_REPOSITORY="$HARNESS_ROOT"
elif command -v powershell.exe >/dev/null 2>&1; then
  POWERSHELL=powershell.exe
  RANK_SCRIPT="$(cygpath -w "${SCRIPT_DIR}/rank-method-call-complexity.ps1")"
  PS_RESULTS="$(cygpath -w "$RESULTS_FILE")"
  PS_REPOSITORY="$(cygpath -w "$HARNESS_ROOT")"
else
  echo "PowerShell was not found; raw results are ready at: $RESULTS_FILE"
  exit 0
fi

POWERSHELL_ARGS=(-NoProfile)
if [[ "$POWERSHELL" == powershell.exe ]]; then
  POWERSHELL_ARGS+=(-ExecutionPolicy Bypass)
fi

"$POWERSHELL" "${POWERSHELL_ARGS[@]}" -File "$RANK_SCRIPT" \
  -StartCommit "$START_COMMIT" \
  -EndCommit "$END_COMMIT" \
  -ResultsFile "$PS_RESULTS" \
  -Coverage 0.80 \
  -Repository "$PS_REPOSITORY" | tee "$SUMMARY_FILE"

echo "Raw results: $RESULTS_FILE"
echo "80% complexity summary: $SUMMARY_FILE"
