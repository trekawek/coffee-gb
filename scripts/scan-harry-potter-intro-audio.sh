#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
DEFAULT_ROM="Z:/emu/roms/gbc/H/Harry Potter and the Sorcerer's Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc"
ROM_PATH="${HARRY_POTTER_ROM:-$DEFAULT_ROM}"
SCAN_EVENTS="${HARRY_POTTER_AUDIO_SCAN_EVENTS:-3600}"
PRESS_START_AT_EVENT="${HARRY_POTTER_AUDIO_PRESS_START_AT_EVENT:--1}"
BOOTSTRAP_MODE="${HARRY_POTTER_AUDIO_BOOTSTRAP_MODE:-NORMAL}"
BATTERY_SAVE="${HARRY_POTTER_BATTERY_SAVE:-}"

if [[ -n "${HARRY_POTTER_AUDIO_DISCOVERY_HARNESS:-}" ]]; then
  HARNESS_DIR="${HARRY_POTTER_AUDIO_DISCOVERY_HARNESS}"
else
  HARNESS_DIR="${PROJECT_DIR}"
fi

cd "${HARNESS_DIR}"
mvn -pl core \
  -Dtest=HarryPotterIntroAudioDiscoveryTest \
  -DharryPotterRom="${ROM_PATH}" \
  -DharryPotterBatterySave="${BATTERY_SAVE}" \
  -DharryPotterAudioScanEvents="${SCAN_EVENTS}" \
  -DharryPotterAudioPressStartAtEvent="${PRESS_START_AT_EVENT}" \
  -DharryPotterAudioBootstrapMode="${BOOTSTRAP_MODE}" \
  -Dsurefire.useFile=false \
  test 2>&1 | tee "${PROJECT_DIR}/harry-potter-audio-discovery.log"
