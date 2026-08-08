#!/usr/bin/env bash
set -euo pipefail

HARNESS_REF="${HARNESS_REF:-codex/a3e0ef8-split3}"
DEFAULT_ROM="Z:/emu/roms/gbc/H/Harry Potter and the Sorcerer's Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc"
ROM_PATH="${HARRY_POTTER_ROM:-$DEFAULT_ROM}"
BATTERY_SAVE="${HARRY_POTTER_BATTERY_SAVE:-}"
if (($# > 0)); then
  ROM_PATH="$1"
fi
PROBE_PATH="core/src/test/java/eu/rekawek/coffeegb/core/performance/HarryPotterIntroAudioTimingTest.java"
SUPPORT_PATH="core/src/test/java/eu/rekawek/coffeegb/core/performance/HarryPotterIntroHarness.java"
HARNESS_FILE="${HARRY_POTTER_AUDIO_TIMING_HARNESS:-}"

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

cleanup_probe=false
cleanup_support=false
if [[ ! -f "$PROBE_PATH" ]]; then
  mkdir -p "$(dirname "$PROBE_PATH")"
  if [[ -n "$HARNESS_FILE" ]]; then
    cp "$HARNESS_FILE" "$PROBE_PATH"
  else
    git show "${HARNESS_REF}:${PROBE_PATH}" > "$PROBE_PATH"
  fi
  cleanup_probe=true
fi
if [[ ! -f "$SUPPORT_PATH" ]]; then
  mkdir -p "$(dirname "$SUPPORT_PATH")"
  git show "${HARNESS_REF}:${SUPPORT_PATH}" > "$SUPPORT_PATH"
  cleanup_support=true
fi

cleanup() {
  if [[ "$cleanup_probe" == true ]]; then
    rm -f "$PROBE_PATH"
  fi
  if [[ "$cleanup_support" == true ]]; then
    rm -f "$SUPPORT_PATH"
  fi
  rmdir --ignore-fail-on-non-empty "$(dirname "$PROBE_PATH")"
}
trap cleanup EXIT

mvn -q -pl core \
  -Dtest=HarryPotterIntroAudioTimingTest \
  -DharryPotterRom="$ROM_PATH" \
  -DharryPotterBatterySave="$BATTERY_SAVE" \
  -Dsurefire.useFile=false \
  test

report="core/target/surefire-reports/TEST-eu.rekawek.coffeegb.core.performance.HarryPotterIntroAudioTimingTest.xml"
sed -n '/<system-out><!\[CDATA\[/,/]]><\/system-out>/p' "$report" \
  | sed -e 's/^.*<!\[CDATA\[//' -e 's/]]><\/system-out>.*$//' \
  | sed -n '/Timed audio\|Audio event\|Audio gaps\|Maximum simulated/p'
