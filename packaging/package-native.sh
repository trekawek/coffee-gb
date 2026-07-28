#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: packaging/package-native.sh TARGET [TYPE] [--release-sign]" >&2
  echo "Targets: linux-x86-64, windows-x86-64, macos-x86-64, macos-aarch64" >&2
  echo "TYPE defaults to deb, msi, or dmg for the selected host; app-image is portable per host." >&2
  exit 2
}

(( $# >= 1 && $# <= 3 )) || usage

target=$1
package_type=${2:-}
release_flag=${3:-}
if [[ "$package_type" == "--release-sign" ]]; then
  release_flag=$package_type
  package_type=
fi
[[ -z "$release_flag" || "$release_flag" == "--release-sign" ]] || usage

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)
maven_command=${COFFEE_GB_MAVEN_COMMAND:-mvn}
pom_file="$repository_root/pom.xml"

if [[ "$maven_command" == */* ]]; then
  [[ -x "$maven_command" ]] || {
    echo "Maven is not executable at $maven_command; set COFFEE_GB_MAVEN_COMMAND." >&2
    exit 2
  }
elif ! command -v "$maven_command" >/dev/null 2>&1; then
  echo "Maven command '$maven_command' is not on PATH; set COFFEE_GB_MAVEN_COMMAND." >&2
  exit 2
fi

"$maven_command" -B -f "$pom_file" -pl swing -am clean package

shopt -s nullglob
app_jars=("$repository_root"/swing/target/coffee-gb-*-app.jar)
if (( ${#app_jars[@]} != 1 )); then
  echo "Expected exactly one Maven -app.jar, found ${#app_jars[@]}." >&2
  exit 2
fi
app_jar=${app_jars[0]}
artifact_prefix=${app_jar%-app.jar}
native_source_jar="${artifact_prefix}.jar"
sbom="${artifact_prefix}-sbom.cdx.json"

[[ -f "$native_source_jar" ]] || {
  echo "Universal Maven artifact is missing: $native_source_jar" >&2
  exit 2
}
[[ -f "$sbom" ]] || {
  echo "CycloneDX SBOM is missing: $sbom" >&2
  exit 2
}

output="$repository_root/swing/target/native-package-${target}${package_type:+-$package_type}"
arguments=(
  -cp "$app_jar"
  eu.rekawek.coffeegb.swing.packaging.NativePackageTool
  build
  --target "$target"
  --app-jar "$app_jar"
  --native-source-jar "$native_source_jar"
  --sbom "$sbom"
  --resources "$repository_root/packaging/resources"
  --output "$output"
)
if [[ -n "$package_type" ]]; then
  arguments+=(--type "$package_type")
fi
if [[ "$release_flag" == "--release-sign" ]]; then
  arguments+=(--release-sign)
fi

java "${arguments[@]}"
