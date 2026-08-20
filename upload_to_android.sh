#!/usr/bin/env bash

set -euo pipefail

die() {
  echo "upload_to_android.sh: error: $*" >&2
  exit 1
}

requested_variant="${1:-release}"
[[ "$#" -le 1 ]] || die "usage: $0 [release|benchmark]"
case "$requested_variant" in
  release|--release) build_variant="release" ;;
  benchmark|qa|--benchmark|--qa) build_variant="benchmark" ;;
  *) die "unknown build variant '$requested_variant' (expected release or benchmark)" ;;
esac

resolve_executable() {
  local requested="$1"

  if [[ "$requested" == */* ]]; then
    [[ -x "$requested" ]] || return 1
    printf '%s\n' "$requested"
  else
    command -v "$requested"
  fi
}

version_is_newer() {
  local left_version="$1"
  local right_version="$2"
  local left_remaining="$left_version"
  local right_remaining="$right_version"

  while [[ -n "$left_remaining" || -n "$right_remaining" ]]; do
    local left_component=0
    local right_component=0

    if [[ -n "$left_remaining" ]]; then
      if [[ "$left_remaining" == *.* ]]; then
        left_component="${left_remaining%%.*}"
        left_remaining="${left_remaining#*.}"
      else
        left_component="$left_remaining"
        left_remaining=""
      fi
    fi

    if [[ -n "$right_remaining" ]]; then
      if [[ "$right_remaining" == *.* ]]; then
        right_component="${right_remaining%%.*}"
        right_remaining="${right_remaining#*.}"
      else
        right_component="$right_remaining"
        right_remaining=""
      fi
    fi

    if ((10#$left_component > 10#$right_component)); then
      return 0
    fi
    if ((10#$left_component < 10#$right_component)); then
      return 1
    fi
  done

  return 1
}

script_path="${BASH_SOURCE[0]}"
script_dir="$(cd -- "$(dirname -- "$script_path")" && pwd -P)"
repo_root="$script_dir"

[[ -f "$repo_root/pom.xml" ]] || die "repository root is missing pom.xml: $repo_root"
[[ -f "$repo_root/android/gradlew" ]] || die "Android Gradle wrapper is missing: $repo_root/android/gradlew"
[[ -x "$repo_root/android/gradlew" ]] || die "Android Gradle wrapper is not executable: $repo_root/android/gradlew"

if [[ -n "${MAVEN_BIN:-}" ]]; then
  maven_cmd="$(resolve_executable "$MAVEN_BIN")" || die "Maven executable not found: $MAVEN_BIN"
elif [[ -x /opt/maven/bin/mvn ]]; then
  maven_cmd="/opt/maven/bin/mvn"
else
  maven_cmd="$(resolve_executable mvn)" || die "Maven is required; install it or set MAVEN_BIN"
fi

user_home="${HOME:-}"
if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then
  sdk_root="$ANDROID_SDK_ROOT"
elif [[ -n "${ANDROID_HOME:-}" ]]; then
  sdk_root="$ANDROID_HOME"
elif [[ "$(uname -s)" == "Darwin" && -n "$user_home" ]]; then
  sdk_root="$user_home/Library/Android/sdk"
else
  die "Android SDK not found; set ANDROID_SDK_ROOT or ANDROID_HOME"
fi

[[ -d "$sdk_root" ]] || die "Android SDK directory does not exist: $sdk_root"
sdk_root="$(cd -- "$sdk_root" && pwd -P)"

build_tools_dir=""
build_tools_version=""
for candidate_dir in "$sdk_root"/build-tools/*; do
  [[ -d "$candidate_dir" ]] || continue
  [[ -x "$candidate_dir/apksigner" && -x "$candidate_dir/zipalign" ]] || continue

  candidate_version="${candidate_dir##*/}"
  [[ "$candidate_version" =~ ^[0-9]+([.][0-9]+)*$ ]] || continue
  if [[ -z "$build_tools_dir" ]] || version_is_newer "$candidate_version" "$build_tools_version"; then
    build_tools_dir="$candidate_dir"
    build_tools_version="$candidate_version"
  fi
done
[[ -n "$build_tools_dir" ]] || die "no Android build-tools directory contains both apksigner and zipalign under $sdk_root/build-tools"

apksigner_bin="$build_tools_dir/apksigner"
zipalign_bin="$build_tools_dir/zipalign"

if [[ -n "${ADB_BIN:-}" ]]; then
  adb_bin="$(resolve_executable "$ADB_BIN")" || die "adb executable not found: $ADB_BIN"
elif [[ -x "$sdk_root/platform-tools/adb" ]]; then
  adb_bin="$sdk_root/platform-tools/adb"
else
  adb_bin="$(resolve_executable adb)" || die "adb is required; install platform-tools or set ADB_BIN"
fi

[[ -n "$user_home" ]] || die "HOME is not set; cannot locate the standard Android debug keystore"
debug_keystore="$user_home/.android/debug.keystore"
[[ -f "$debug_keystore" ]] || die "standard Android debug keystore not found: $debug_keystore"

android_maven_repo="$repo_root/build/android-m2"
apk_dir="$repo_root/android/app/build/outputs/apk/$build_variant"
unsigned_apk="$apk_dir/app-$build_variant-unsigned.apk"
if [[ "$build_variant" == "release" ]]; then
  aligned_apk="$apk_dir/app-release-qa-r8-aligned.apk"
  signed_apk="$apk_dir/coffee-gb-qa-r8.apk"
  gradle_task="assembleRelease"
else
  aligned_apk="$apk_dir/app-benchmark-qa-r8-aligned.apk"
  signed_apk="$apk_dir/coffee-gb-benchmark-qa-r8.apk"
  gradle_task="assembleBenchmark"
fi

mkdir -p "$android_maven_repo"
cd -- "$repo_root"

echo "Installing controller, ui-portable, and dependencies into $android_maven_repo"
"$maven_cmd" -B -pl controller,ui-portable -am install -DskipTests \
  -Dkotlin.compiler.daemon=false \
  "-Dmaven.repo.local=$android_maven_repo"

echo "Building the R8 $build_variant APK"
ANDROID_SDK_ROOT="$sdk_root" ANDROID_HOME="$sdk_root" \
  "$repo_root/android/gradlew" -p "$repo_root/android" \
  "-PcoffeeGbMavenRepository=$android_maven_repo" ":app:$gradle_task"

[[ -f "$unsigned_apk" ]] || die "$build_variant build did not produce the unsigned APK: $unsigned_apk"

echo "Using Android build-tools $build_tools_version"
echo "Aligning the $build_variant APK"
"$zipalign_bin" -f -p 4 "$unsigned_apk" "$aligned_apk"

echo "Signing the QA APK with the debug keystore"
"$apksigner_bin" sign \
  --ks "$debug_keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$signed_apk" \
  "$aligned_apk"
"$apksigner_bin" verify --verbose "$signed_apk"

echo "Installing the $build_variant QA APK with adb -r (app data preserved)"
"$adb_bin" install -r "$signed_apk"

echo "Installed $signed_apk"
