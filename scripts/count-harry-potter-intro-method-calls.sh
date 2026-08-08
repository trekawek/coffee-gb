#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HARNESS_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
AGENT_PROJECT="${SCRIPT_DIR}/method-call-agent"
AGENT_JAR="${AGENT_PROJECT}/target/core-method-call-agent-1.0.0.jar"

DEFAULT_ROM="Z:/emu/roms/gbc/H/Harry Potter and the Sorcerer's Stone (USA, Europe) (En,Fr,De,Es,It,Nl,Pt,Sv,No,Da,Fi).gbc"
ROM_PATH="${HARRY_POTTER_ROM:-$DEFAULT_ROM}"
BATTERY_SAVE="${HARRY_POTTER_BATTERY_SAVE:-}"
TARGET_FRAMES="${HARRY_POTTER_TARGET_FRAMES:-1800}"

if [[ ! -f "${AGENT_JAR}" ]]; then
  mvn -q -f "${AGENT_PROJECT}/pom.xml" package
fi

TARGET_ROOT="$(git rev-parse --show-toplevel)"
TARGET_COMMIT="$(git rev-parse HEAD)"
TEST_PATH="core/src/test/java/eu/rekawek/coffeegb/core/performance/HarryPotterIntroMethodCallTest.java"
SUPPORT_PATH="core/src/test/java/eu/rekawek/coffeegb/core/performance/HarryPotterIntroHarness.java"
TEST_SOURCE="${HARNESS_ROOT}/${TEST_PATH}"
SUPPORT_SOURCE="${HARNESS_ROOT}/${SUPPORT_PATH}"

cleanup_test=false
cleanup_support=false
MAVEN_LOG=""
cd "${TARGET_ROOT}"
mkdir -p "$(dirname "${TEST_PATH}")"
if [[ ! -f "${TEST_PATH}" ]]; then
  cp "${TEST_SOURCE}" "${TEST_PATH}"
  cleanup_test=true
fi
if [[ ! -f "${SUPPORT_PATH}" ]]; then
  cp "${SUPPORT_SOURCE}" "${SUPPORT_PATH}"
  cleanup_support=true
fi

cleanup() {
  if [[ "${cleanup_test}" == true ]]; then
    rm -f "${TEST_PATH}"
  fi
  if [[ "${cleanup_support}" == true ]]; then
    rm -f "${SUPPORT_PATH}"
  fi
  if [[ -n "${MAVEN_LOG}" ]]; then
    rm -f "${MAVEN_LOG}"
  fi
  rmdir --ignore-fail-on-non-empty "$(dirname "${TEST_PATH}")"
}
trap cleanup EXIT

if command -v cygpath >/dev/null 2>&1; then
  AGENT_JAR_JVM="$(cygpath -m "${AGENT_JAR}")"
else
  AGENT_JAR_JVM="${AGENT_JAR}"
fi

MAVEN_LOG="$(mktemp)"
if ! JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:+${JAVA_TOOL_OPTIONS} }-javaagent:${AGENT_JAR_JVM}" \
  mvn -q -pl core \
    -Dtest=HarryPotterIntroMethodCallTest \
    -DharryPotterRom="${ROM_PATH}" \
    -DharryPotterBatterySave="${BATTERY_SAVE}" \
    -DharryPotterTargetFrames="${TARGET_FRAMES}" \
    -Dsurefire.useFile=true \
    clean test >"${MAVEN_LOG}" 2>&1; then
  cat "${MAVEN_LOG}" >&2
  exit 1
fi

REPORT="core/target/surefire-reports/TEST-eu.rekawek.coffeegb.core.performance.HarryPotterIntroMethodCallTest.xml"
RESULT="$(sed -n '/<system-out><!\[CDATA\[/,/]]><\/system-out>/p' "${REPORT}" \
  | sed -e 's/^.*<!\[CDATA\[//' -e 's/]]><\/system-out>.*$//' \
  | sed -n '/^METHOD_CALL_RESULT /p' \
  | tail -n 1)"

if [[ -z "${RESULT}" ]]; then
  echo "Method-call result was not found in ${REPORT}" >&2
  exit 1
fi
echo "METHOD_CALL_RESULT commit=${TARGET_COMMIT} ${RESULT#METHOD_CALL_RESULT }"
