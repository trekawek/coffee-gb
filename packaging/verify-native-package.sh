#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: packaging/verify-native-package.sh TARGET BUILD_ROOT" >&2
  echo "TARGET must be linux-x86-64, macos-x86-64, or macos-aarch64." >&2
  exit 2
}

(( $# == 2 )) || usage
target=$1
build_root=$2

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)
case "$target" in
  linux-x86-64)
    package_type=deb
    installer_suffix=.deb
    extraction="$build_root/extracted-installer"
    [[ ! -e "$extraction" ]] || {
      echo "Extraction output already exists: $extraction" >&2
      exit 2
    }
    mkdir -p "$extraction"
    ;;
  macos-x86-64|macos-aarch64)
    package_type=dmg
    installer_suffix=.dmg
    extraction="$build_root/mounted-installer"
    [[ ! -e "$extraction" ]] || {
      echo "Mount output already exists: $extraction" >&2
      exit 2
    }
    mkdir -p "$extraction"
    ;;
  *)
    usage
    ;;
esac

shopt -s nullglob
app_jars=("$repository_root"/swing/target/coffee-gb-*-app.jar)
sboms=("$repository_root"/swing/target/coffee-gb-*-sbom.cdx.json)
installers=("$build_root"/dist/*"$installer_suffix")
(( ${#app_jars[@]} == 1 )) || {
  echo "Expected exactly one Maven -app.jar, found ${#app_jars[@]}." >&2
  exit 2
}
(( ${#sboms[@]} == 1 )) || {
  echo "Expected exactly one Maven SBOM, found ${#sboms[@]}." >&2
  exit 2
}
(( ${#installers[@]} == 1 )) || {
  echo "Expected exactly one $installer_suffix installer, found ${#installers[@]}." >&2
  exit 2
}

if [[ "$package_type" == deb ]]; then
  command -v dpkg-deb >/dev/null 2>&1 || {
    echo "dpkg-deb is required to unpack the Linux package." >&2
    exit 2
  }
  dpkg-deb --extract "${installers[0]}" "$extraction"
else
  command -v hdiutil >/dev/null 2>&1 || {
    echo "hdiutil is required to mount the macOS package." >&2
    exit 2
  }
  cleanup_mount() {
    # A failed attach can still leave a device mounted. This is a dedicated empty
    # mount point, so an unconditional best-effort detach is safe on every exit.
    hdiutil detach "$extraction" >/dev/null 2>&1 || true
  }
  trap cleanup_mount EXIT
  hdiutil attach \
    -nobrowse \
    -readonly \
    -mountpoint "$extraction" \
    "${installers[0]}" \
    >/dev/null \
    <<<Y || {
      echo "Could not mount the licensed macOS installer." >&2
      exit 2
    }
fi

java \
  -cp "${app_jars[0]}" \
  eu.rekawek.coffeegb.swing.packaging.NativePackageVerifier \
  verify \
  --target "$target" \
  --type "$package_type" \
  --root "$extraction" \
  --source-app-jar "${app_jars[0]}" \
  --source-sbom "${sboms[0]}" \
  --source-legal "$repository_root/packaging/resources/legal" \
  --dist "$build_root/dist" \
  --run-smoke \
  --smoke-home "$build_root/unpacked-smoke-home"
