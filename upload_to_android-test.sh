#!/usr/bin/env bash

set -euo pipefail

source_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
subject="$source_root/upload_to_android.sh"
test_root="$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-upload-test.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT HUP INT TERM

fake_repo="$test_root/repo"
fake_sdk="$test_root/sdk"
fake_home="$test_root/home"
fake_bin="$test_root/bin"
calls="$test_root/adb.calls"

mkdir -p "$fake_repo/android" "$fake_sdk/build-tools/36.0.0" \
  "$fake_home/.android" "$fake_bin"
cp -- "$subject" "$fake_repo/upload_to_android.sh"
chmod 700 "$fake_repo/upload_to_android.sh"
printf '<project/>\n' >"$fake_repo/pom.xml"
printf 'fixture key\n' >"$fake_home/.android/debug.keystore"

cat >"$fake_bin/mvn" <<'STUB'
#!/usr/bin/env bash
exit 0
STUB

cat >"$fake_sdk/build-tools/36.0.0/zipalign" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
[[ "$#" -eq 5 && "$1" == -f && "$2" == -p && "$3" == 4 ]]
cp -- "$4" "$5"
STUB

cat >"$fake_sdk/build-tools/36.0.0/apksigner" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "${1:-}" in
  verify)
    exit 0
    ;;
  sign)
    shift
    output=
    input=
    while [[ "$#" -gt 0 ]]; do
      if [[ "$1" == --out ]]; then
        output="$2"
        shift 2
      else
        input="$1"
        shift
      fi
    done
    [[ -n "$output" && -f "$input" ]]
    cp -- "$input" "$output"
    ;;
  *)
    exit 1
    ;;
esac
STUB

cat >"$fake_bin/adb" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"${FAKE_CALLS:?}"
if [[ "${1:-}" == shell && "${2:-}" == getprop \
    && "${3:-}" == ro.build.version.sdk ]]; then
  printf '%s\n' "${FAKE_API:?}"
  exit 0
fi
if [[ "${1:-}" == install-multiple ]]; then
  [[ "$#" -eq 4 && "$2" == -r && "$3" == *.apk \
    && "$4" == "${3%.apk}.dm" && -s "$3" && -s "$4" ]]
  echo Success
  exit 0
fi
if [[ "${1:-}" == install ]]; then
  [[ "$#" -eq 3 && "$2" == -r && "$3" == *.apk && -s "$3" ]]
  echo Success
  exit 0
fi
exit 1
STUB

cat >"$fake_repo/android/gradlew" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "$*" in
  *:app:assembleBenchmark*)
    variant=benchmark
    apk_name=app-benchmark.apk
    ;;
  *:app:assembleRelease*)
    variant=release
    apk_name=app-release-unsigned.apk
    ;;
  *)
    exit 1
    ;;
esac
output="${FAKE_REPO:?}/android/app/build/outputs/apk/$variant"
mkdir -p "$output/baselineProfiles/0" "$output/baselineProfiles/1"
printf 'fixture %s apk\n' "$variant" >"$output/$apk_name"
if [[ "${FAKE_MISSING_DM:-0}" != 1 ]]; then
  if [[ "${FAKE_EMPTY_DM:-0}" == 1 ]]; then
    : >"$output/baselineProfiles/0/${apk_name%.apk}.dm"
  else
    printf 'api31 %s profile\n' "$variant" \
      >"$output/baselineProfiles/0/${apk_name%.apk}.dm"
  fi
  printf 'api28 %s profile\n' "$variant" \
    >"$output/baselineProfiles/1/${apk_name%.apk}.dm"
fi
if [[ "${FAKE_BAD_METADATA:-0}" == 1 ]]; then
  printf '{"baselineProfiles": []}\n' >"$output/output-metadata.json"
  exit 0
fi
extra=
if [[ "${FAKE_AMBIGUOUS_METADATA:-0}" == 1 ]]; then
  mkdir -p "$output/baselineProfiles/2"
  printf 'ambiguous %s profile\n' "$variant" \
    >"$output/baselineProfiles/2/${apk_name%.apk}.dm"
  extra=',
    {
      "minApi": 31,
      "maxApi": 2147483647,
      "baselineProfiles": [
        "baselineProfiles/2/'"${apk_name%.apk}"'.dm"
      ]
    }'
fi
cat >"$output/output-metadata.json" <<JSON
{
  "baselineProfiles": [
    {
      "minApi": 28,
      "maxApi": 30,
      "baselineProfiles": [
        "baselineProfiles/1/${apk_name%.apk}.dm"
      ]
    },
    {
      "minApi": 31,
      "maxApi": 2147483647,
      "baselineProfiles": [
        "baselineProfiles/0/${apk_name%.apk}.dm"
      ]
    }$extra
  ]
}
JSON
STUB

chmod 700 "$fake_bin/mvn" "$fake_bin/adb" "$fake_repo/android/gradlew" \
  "$fake_sdk/build-tools/36.0.0/zipalign" \
  "$fake_sdk/build-tools/36.0.0/apksigner"

reset_fixture() {
  rm -rf "$fake_repo/android/app" "$fake_repo/build"
  : >"$calls"
}

run_subject() {
  local api="$1"
  local variant="$2"
  shift 2
  HOME="$fake_home" ANDROID_SDK_ROOT="$fake_sdk" MAVEN_BIN="$fake_bin/mvn" \
    ADB_BIN="$fake_bin/adb" FAKE_REPO="$fake_repo" FAKE_CALLS="$calls" \
    FAKE_API="$api" "$@" "$fake_repo/upload_to_android.sh" "$variant" \
    >"$test_root/$variant-$api.stdout" 2>"$test_root/$variant-$api.stderr"
}

assert_profiled_install() {
  local variant="$1"
  local profile_marker="$2"
  local final_stem
  if [[ "$variant" == benchmark ]]; then
    final_stem=coffee-gb-benchmark-qa-r8
  else
    final_stem=coffee-gb-qa-r8
  fi
  local output="$fake_repo/android/app/build/outputs/apk/$variant"
  [[ -s "$output/$final_stem.apk" && -s "$output/$final_stem.dm" ]]
  grep -q "$profile_marker" "$output/$final_stem.dm"
  grep -Fxq "install-multiple -r $output/$final_stem.apk $output/$final_stem.dm" "$calls"
}

reset_fixture
run_subject 35 benchmark env
assert_profiled_install benchmark api31

reset_fixture
run_subject 35 release env
assert_profiled_install release api31

reset_fixture
run_subject 29 benchmark env
assert_profiled_install benchmark api28

reset_fixture
run_subject 27 release env
release_apk="$fake_repo/android/app/build/outputs/apk/release/coffee-gb-qa-r8.apk"
grep -Fxq "install -r $release_apk" "$calls"
if grep -q '^install-multiple ' "$calls"; then
  echo 'API 27 unexpectedly attempted an install-time profile' >&2
  exit 1
fi

expect_profile_failure() {
  local mode_name="$1"
  local mode_variable="$2"
  reset_fixture
  set +e
  run_subject 35 benchmark env "$mode_variable=1"
  local status=$?
  set -e
  [[ "$status" -ne 0 ]] || {
    echo "$mode_name profile fixture unexpectedly installed" >&2
    exit 1
  }
  if grep -q '^install' "$calls"; then
    echo "$mode_name profile fixture reached adb installation" >&2
    exit 1
  fi
}

expect_profile_failure empty FAKE_EMPTY_DM
expect_profile_failure missing FAKE_MISSING_DM
expect_profile_failure malformed FAKE_BAD_METADATA
expect_profile_failure ambiguous FAKE_AMBIGUOUS_METADATA

bash -n "$subject"
echo 'upload_to_android profile install fixture: PASS'
