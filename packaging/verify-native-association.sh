#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo "Usage: packaging/verify-native-association.sh TARGET BUILD_ROOT" >&2
  echo "TARGET must be linux-x86-64, macos-x86-64, or macos-aarch64." >&2
  exit 2
}

(( $# == 2 )) || usage
target=$1
build_root=$2

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repository_root=$(cd -- "$script_dir/.." && pwd)
if [[ "$build_root" != /* ]]; then
  build_root="$repository_root/$build_root"
fi
build_root=$(cd -- "$build_root" && pwd)
smoke_root="$build_root/installed-association-smoke"
[[ ! -e "$smoke_root" ]] || {
  echo "Association smoke output already exists: $smoke_root" >&2
  exit 2
}
mkdir -p "$smoke_root/home" "$smoke_root/native-cache"

shopt -s nullglob
app_jars=("$repository_root"/swing/target/coffee-gb-*-app.jar)
(( ${#app_jars[@]} == 1 )) || {
  echo "Expected exactly one Maven -app.jar, found ${#app_jars[@]}." >&2
  exit 2
}

extensions=(gb gbc rom)
for extension in "${extensions[@]}"; do
  fixture="$smoke_root/Coffee GB association smoke.$extension"
  java \
    -cp "${app_jars[0]}" \
    eu.rekawek.coffeegb.swing.PackageAssociationFixture \
    "$fixture"
  [[ -f "$fixture" && $(wc -c < "$fixture") -eq 32768 ]] || {
    echo "Generated .$extension association fixture is missing or has the wrong size." >&2
    exit 2
  }
done

export _JAVA_OPTIONS="-Djava.awt.headless=false -Duser.home=$smoke_root/home -Dcoffee-gb.native.cache=$smoke_root/native-cache"
await_file() {
  local path=$1
  local description=$2
  local deadline=$((SECONDS + 60))
  while [[ ! -f "$path" && $SECONDS -lt $deadline ]]; do
    sleep 0.1
  done
  [[ -f "$path" ]] || {
    echo "Timed out waiting for $description: $path" >&2
    return 1
  }
}

await_evidence() {
  local path=$1
  local description=$2
  local deadline=$((SECONDS + 60))
  local pid_count=0
  while [[ $SECONDS -lt $deadline ]]; do
    if [[ -f "$path" ]]; then
      pid_count=$(grep -Ec '^pid=[1-9][0-9]*$' "$path" || true)
      (( pid_count == 1 )) && return 0
    fi
    sleep 0.1
  done
  echo "Timed out waiting for complete $description: $path" >&2
  return 1
}

assert_evidence() {
  local marker=$1
  local fixture=$2
  local expected_source=$3
  grep -Fx "Coffee GB association open OK" "$marker" >/dev/null
  grep -Fx "source=$expected_source" "$marker" >/dev/null
  grep -Fx "rom=$fixture" "$marker" >/dev/null
  grep -Fx "origin=$fixture" "$marker" >/dev/null
  grep -Fx "title=COFFEE-CI-SMOKE" "$marker" >/dev/null
  local pid_count
  pid_count=$(grep -Ec '^pid=[1-9][0-9]*$' "$marker" || true)
  (( pid_count == 1 )) || {
    echo "Association evidence has no unique process ID: $marker" >&2
    return 1
  }
}

evidence_pid() {
  sed -nE 's/^pid=([1-9][0-9]*)$/\1/p' "$1"
}

assert_shutdown_evidence() {
  local marker=$1
  local expected_pid=$2
  grep -Fx "Coffee GB association shutdown OK" "$marker" >/dev/null
  grep -Fx "pid=$expected_pid" "$marker" >/dev/null
}

case "$target" in
  linux-x86-64)
    installers=("$build_root"/dist/*.deb)
    (( ${#installers[@]} == 1 )) || {
      echo "Expected exactly one DEB installer, found ${#installers[@]}." >&2
      exit 2
    }
    for command in dpkg dpkg-query xdg-mime xdg-open dbus-run-session xvfb-run sudo; do
      command -v "$command" >/dev/null || {
        echo "$command is required for the installed Linux association smoke." >&2
        exit 2
      }
    done
    if dpkg-query -W -f='${db:Status-Status}' coffee-gb 2>/dev/null | grep -qx installed; then
      echo "The Linux runner already has coffee-gb installed." >&2
      exit 2
    fi

    installed=false
    cleanup_linux() {
      pkill -TERM -f '^/opt/coffee-gb/bin/Coffee GB' 2>/dev/null || true
      if [[ "$installed" == true ]]; then
        sudo dpkg --remove coffee-gb >/dev/null 2>&1 || true
      fi
    }
    trap cleanup_linux EXIT

    sudo dpkg --install "${installers[0]}"
    installed=true
    [[ $(dpkg-query -W -f='${db:Status-Status}' coffee-gb) == installed ]]
    launcher="/opt/coffee-gb/bin/Coffee GB"
    [[ -x "$launcher" ]]

    mapfile -t desktop_files < <(
      find /usr/share/applications \
        -maxdepth 1 \
        -type f \
        -name 'coffee-gb-*.desktop' \
        -print
    )
    (( ${#desktop_files[@]} == 1 )) || {
      echo "Expected exactly one installed Coffee GB desktop entry." >&2
      exit 2
    }
    desktop_id=${desktop_files[0]##*/}
    grep -Fx 'MimeType=application/x-gameboy-rom' "${desktop_files[0]}" >/dev/null
    grep -Fx 'Exec="/opt/coffee-gb/bin/Coffee GB" %f' "${desktop_files[0]}" >/dev/null
    for extension in "${extensions[@]}"; do
      fixture="$smoke_root/Coffee GB association smoke.$extension"
      [[ $(xdg-mime query filetype "$fixture") == application/x-gameboy-rom ]]
    done

    export XDG_CONFIG_HOME="$smoke_root/xdg-config"
    export XDG_DATA_HOME="$smoke_root/xdg-data"
    mkdir -p "$XDG_CONFIG_HOME" "$XDG_DATA_HOME"
    xdg-mime default "$desktop_id" application/x-gameboy-rom
    [[ $(xdg-mime query default application/x-gameboy-rom) == "$desktop_id" ]]

    for extension in "${extensions[@]}"; do
      fixture="$smoke_root/Coffee GB association smoke.$extension"
      marker="$smoke_root/association-opened-$extension.marker"
      shutdown_marker="$marker.shutdown"
      export COFFEE_GB_ASSOCIATION_SMOKE_MARKER="$marker"
      export COFFEE_GB_ASSOCIATION_SMOKE_ROM="$fixture"
      # Keep the private desktop session alive until the exact process that reported the
      # correlated Opened update has committed normal shutdown and exited.
      dbus-run-session -- xvfb-run -a bash -c '
        set -euo pipefail
        fixture=$1
        marker=$2
        shutdown_marker=$3
        launcher=$4
        xdg-open "$fixture"
        deadline=$((SECONDS + 60))
        while [[ $SECONDS -lt $deadline ]]; do
          if [[ -f "$marker" ]] &&
              [[ $(grep -Ec "^pid=[1-9][0-9]*$" "$marker" || true) -eq 1 ]]; then
            break
          fi
          sleep 0.1
        done
        [[ $(grep -Ec "^pid=[1-9][0-9]*$" "$marker" || true) -eq 1 ]]
        pid=$(sed -nE "s/^pid=([1-9][0-9]*)$/\\1/p" "$marker")
        [[ "$pid" =~ ^[1-9][0-9]*$ ]]
        if actual_exe=$(readlink -f "/proc/$pid/exe" 2>/dev/null); then
          [[ "$actual_exe" == "$launcher" ]]
        fi
        while [[ $SECONDS -lt $deadline ]]; do
          if [[ -f "$shutdown_marker" ]] &&
              [[ $(grep -Ec "^pid=[1-9][0-9]*$" "$shutdown_marker" || true) -eq 1 ]]; then
            break
          fi
          sleep 0.1
        done
        [[ $(grep -Ec "^pid=[1-9][0-9]*$" "$shutdown_marker" || true) -eq 1 ]]
        grep -Fx "Coffee GB association shutdown OK" "$shutdown_marker" >/dev/null
        grep -Fx "pid=$pid" "$shutdown_marker" >/dev/null
        while kill -0 "$pid" 2>/dev/null && [[ $SECONDS -lt $deadline ]]; do sleep 0.1; done
        ! kill -0 "$pid" 2>/dev/null
      ' association-smoke "$fixture" "$marker" "$shutdown_marker" "$launcher"
      assert_evidence "$marker" "$fixture" INITIAL_ARGUMENT
      pid=$(evidence_pid "$marker")
      assert_shutdown_evidence "$shutdown_marker" "$pid"
      [[ $(xdg-mime query default application/x-gameboy-rom) == "$desktop_id" ]]
    done

    sudo dpkg --remove coffee-gb
    installed=false
    if dpkg-query -W -f='${db:Status-Status}' coffee-gb 2>/dev/null | grep -qx installed; then
      echo "The Linux package remained installed after removal." >&2
      exit 2
    fi
    [[ ! -e "$launcher" ]]
    [[ ! -e "${desktop_files[0]}" ]]
    trap - EXIT
    ;;

  macos-x86-64|macos-aarch64)
    installers=("$build_root"/dist/*.dmg)
    (( ${#installers[@]} == 1 )) || {
      echo "Expected exactly one DMG installer, found ${#installers[@]}." >&2
      exit 2
    }
    for command in hdiutil ditto plutil open; do
      command -v "$command" >/dev/null || {
        echo "$command is required for the installed macOS association smoke." >&2
        exit 2
      }
    done
    lsregister="/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister"
    [[ -x "$lsregister" ]] || {
      echo "The macOS Launch Services registration tool is unavailable." >&2
      exit 2
    }

    mount_point="$smoke_root/mounted-dmg"
    applications="$smoke_root/Applications"
    installed_app="$applications/Coffee GB.app"
    mkdir -p "$mount_point" "$applications"
    mounted=false
    app_pid=
    cleanup_macos() {
      if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
        kill -TERM "$app_pid" 2>/dev/null || true
      fi
      "$lsregister" -u "$installed_app" >/dev/null 2>&1 || true
      if [[ "$mounted" == true ]]; then
        hdiutil detach "$mount_point" >/dev/null 2>&1 || true
      fi
    }
    trap cleanup_macos EXIT

    hdiutil attach "${installers[0]}" \
      -mountpoint "$mount_point" \
      -nobrowse \
      -readonly \
      >/dev/null
    mounted=true
    source_apps=("$mount_point"/*.app)
    (( ${#source_apps[@]} == 1 )) || {
      echo "Expected exactly one application bundle in the DMG." >&2
      exit 2
    }
    ditto "${source_apps[0]}" "$installed_app"
    hdiutil detach "$mount_point" >/dev/null
    mounted=false

    if [[ ${COFFEE_GB_RELEASE_SIGNING:-} == true ]]; then
      codesign --verify --deep --strict --verbose=2 "$installed_app"
      codesign \
        --verify \
        --verbose=2 \
        '-R=entitlement["com.apple.security.cs.disable-library-validation"] = true' \
        "$installed_app"
      spctl --assess --type execute --verbose=2 "$installed_app"
    fi

    info="$installed_app/Contents/Info.plist"
    [[ $(plutil -extract CFBundleIdentifier raw -o - "$info") == eu.rekawek.coffeegb ]]
    documents=$(plutil -extract CFBundleDocumentTypes json -o - "$info")
    for extension in gb gbc rom; do
      grep -F "\"$extension\"" <<<"$documents" >/dev/null
    done
    "$lsregister" -f "$installed_app"
    open -Ra "$installed_app"

    for extension in "${extensions[@]}"; do
      fixture="$smoke_root/Coffee GB association smoke.$extension"
      marker="$smoke_root/association-opened-$extension.marker"
      ready_marker="$smoke_root/desktop-ready-$extension.marker"
      shutdown_marker="$marker.shutdown"
      export COFFEE_GB_ASSOCIATION_SMOKE_MARKER="$marker"
      export COFFEE_GB_ASSOCIATION_SMOKE_ROM="$fixture"
      export COFFEE_GB_DESKTOP_SMOKE_MARKER="$ready_marker"
      "$installed_app/Contents/MacOS/Coffee GB" \
        >"$smoke_root/application-$extension.log" \
        2>&1 &
      app_pid=$!
      await_file "$ready_marker" "installed macOS .$extension desktop readiness"
      kill -0 "$app_pid"

      # Do not select Coffee GB explicitly here: Launch Services must choose the registered
      # default handler for each supported extension.
      open "$fixture"
      await_evidence "$marker" "default-handler macOS .$extension association result"
      assert_evidence "$marker" "$fixture" DESKTOP_OPEN_FILE
      pid=$(evidence_pid "$marker")
      [[ "$pid" == "$app_pid" ]]
      await_evidence "$shutdown_marker" "normal macOS .$extension association shutdown"
      assert_shutdown_evidence "$shutdown_marker" "$pid"

      deadline=$((SECONDS + 60))
      while kill -0 "$app_pid" 2>/dev/null && [[ $SECONDS -lt $deadline ]]; do
        sleep 0.1
      done
      if kill -0 "$app_pid" 2>/dev/null; then
        echo "The macOS .$extension application did not complete bounded shutdown." >&2
        exit 2
      fi
      wait "$app_pid"
      app_pid=
    done

    "$lsregister" -u "$installed_app"
    rm -rf -- "$installed_app"
    [[ ! -e "$installed_app" ]]
    trap - EXIT
    ;;

  *)
    usage
    ;;
esac

echo "Installed file association smoke passed for $target."
