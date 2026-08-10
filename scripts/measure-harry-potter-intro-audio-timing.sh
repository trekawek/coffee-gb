#!/usr/bin/env bash
set -euo pipefail

HARNESS_REF="${HARNESS_REF:-master}"
DEFAULT_ROM="Z:/emu/roms/gbc/H/Harry Potter and the Sorcerer's Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc"
ROM_PATH="${HARRY_POTTER_ROM:-$DEFAULT_ROM}"
BATTERY_SAVE="${HARRY_POTTER_BATTERY_SAVE:-}"
LATENCY_PRESET="${HARRY_POTTER_AUDIO_LATENCY:-BALANCED}"
REWIND_ENABLED="${HARRY_POTTER_REWIND_ENABLED:-true}"
BOOTSTRAP_MODE="${HARRY_POTTER_BOOTSTRAP_MODE:-SKIP}"
SAMPLE_LINE_OCCUPANCY="${HARRY_POTTER_SAMPLE_LINE_OCCUPANCY:-false}"
JFR_PATH="${HARRY_POTTER_JFR:-}"
case "$SAMPLE_LINE_OCCUPANCY" in
  true|false) ;;
  *)
    echo "HARRY_POTTER_SAMPLE_LINE_OCCUPANCY must be true or false: $SAMPLE_LINE_OCCUPANCY" >&2
    exit 2
    ;;
esac
if (($# > 0)); then
  ROM_PATH="$1"
fi
PROBE_PATH="swing/src/test/java/eu/rekawek/coffeegb/swing/io/HarryPotterIntroUiAudioTimingTest.java"
HARNESS_FILE="${HARRY_POTTER_AUDIO_TIMING_HARNESS:-}"

repo_root="$(git rev-parse --show-toplevel)"
cd "$repo_root"

cleanup_probe=false
if [[ ! -f "$PROBE_PATH" ]]; then
  mkdir -p "$(dirname "$PROBE_PATH")"
  if [[ -n "$HARNESS_FILE" ]]; then
    cp "$HARNESS_FILE" "$PROBE_PATH"
  else
    git show "${HARNESS_REF}:${PROBE_PATH}" > "$PROBE_PATH"
  fi
  cleanup_probe=true
fi

cleanup() {
  if [[ "$cleanup_probe" == true ]]; then
    rm -f "$PROBE_PATH"
  fi
  rmdir --ignore-fail-on-non-empty "$(dirname "$PROBE_PATH")"
}
trap cleanup EXIT

mvn -q -pl swing -am \
  -Dtest=HarryPotterIntroUiAudioTimingTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DharryPotterRom="$ROM_PATH" \
  -DharryPotterBatterySave="$BATTERY_SAVE" \
  -DharryPotterAudioLatency="$LATENCY_PRESET" \
  -DharryPotterRewindEnabled="$REWIND_ENABLED" \
  -DharryPotterBootstrapMode="$BOOTSTRAP_MODE" \
  -DharryPotterSampleLineOccupancy="$SAMPLE_LINE_OCCUPANCY" \
  -DharryPotterJfr="$JFR_PATH" \
  -Dsurefire.useFile=false \
  test

report="swing/target/surefire-reports/TEST-eu.rekawek.coffeegb.swing.io.HarryPotterIntroUiAudioTimingTest.xml"
sed -n '/<system-out><!\[CDATA\[/,/]]><\/system-out>/p' "$report" \
  | sed -e 's/^.*<!\[CDATA\[//' -e 's/]]><\/system-out>.*$//' \
  | sed -n '/Full intro/p'
