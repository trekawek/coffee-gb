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
    for command in \
      dpkg \
      dpkg-query \
      gio \
      mimetype \
      update-mime-database \
      xdg-mime \
      dbus-run-session \
      xvfb-run \
      sudo; do
      command -v "$command" >/dev/null || {
        echo "$command is required for the installed Linux association smoke." >&2
        exit 2
      }
    done
    if dpkg-query -W -f='${db:Status-Status}' coffee-gb 2>/dev/null | grep -qx installed; then
      echo "The Linux runner already has coffee-gb installed." >&2
      exit 2
    fi

    install_attempted=false
    cleanup_linux() {
      local exit_status=$?
      pkill -TERM -f '^/opt/coffee-gb/bin/Coffee GB' 2>/dev/null || true
      if [[ "$install_attempted" == true ]]; then
        if ! sudo dpkg --remove coffee-gb >/dev/null 2>&1; then
          echo "Failed to remove coffee-gb while cleaning up the Linux association smoke." >&2
          (( exit_status != 0 )) || exit_status=1
        fi
      fi
      trap - EXIT
      exit "$exit_status"
    }
    trap cleanup_linux EXIT

    install_attempted=true
    sudo dpkg --install "${installers[0]}"
    [[ $(dpkg-query -W -f='${db:Status-Status}' coffee-gb) == installed ]]
    launcher="/opt/coffee-gb/bin/Coffee GB"
    [[ -x "$launcher" ]]

    desktop_roots=(/usr/local/share/applications /usr/share/applications)
    desktop_files=()
    for desktop_root in "${desktop_roots[@]}"; do
      [[ -d "$desktop_root" && ! -L "$desktop_root" ]] || continue
      while IFS= read -r -d '' desktop_file; do
        desktop_files+=("$desktop_file")
      done < <(
        find "$desktop_root" \
          -maxdepth 1 \
          -type f \
          -name 'coffee-gb-*.desktop' \
          -print0
      )
    done
    (( ${#desktop_files[@]} == 1 )) || {
      echo "Expected exactly one installed Coffee GB desktop entry." >&2
      exit 2
    }
    [[ -f "${desktop_files[0]}" && ! -L "${desktop_files[0]}" ]]
    desktop_id=${desktop_files[0]##*/}
    desktop_mimes=$(sed -n 's/^MimeType=//p' "${desktop_files[0]}")
    for mime_type in application/x-gameboy-rom application/x-gameboy-color-rom; do
      [[ ";$desktop_mimes;" == *";$mime_type;"* ]] || {
        echo "Installed desktop entry does not advertise $mime_type." >&2
        exit 2
      }
    done
    grep -Fx 'Exec="/opt/coffee-gb/bin/Coffee GB" %f' "${desktop_files[0]}" >/dev/null || {
      echo "Installed desktop entry does not launch Coffee GB with one file argument." >&2
      exit 2
    }
    for extension in "${extensions[@]}"; do
      fixture="$smoke_root/Coffee GB association smoke.$extension"
      expected_mime=application/x-gameboy-rom
      [[ "$extension" != gbc ]] || expected_mime=application/x-gameboy-color-rom
      actual_mime=$(xdg-mime query filetype "$fixture") || {
        echo "Could not query the installed .$extension MIME type." >&2
        exit 2
      }
      [[ "$actual_mime" == "$expected_mime" ]] || {
        echo "Installed .$extension MIME type is $actual_mime, expected $expected_mime." >&2
        exit 2
      }
    done

    export XDG_CONFIG_HOME="$smoke_root/xdg-config"
    export XDG_DATA_HOME="$smoke_root/xdg-data"
    mkdir -p "$XDG_CONFIG_HOME" "$XDG_DATA_HOME"
    for mime_type in application/x-gameboy-rom application/x-gameboy-color-rom; do
      xdg-mime default "$desktop_id" "$mime_type"
      actual_default=$(xdg-mime query default "$mime_type") || {
        echo "Could not query the default handler for $mime_type." >&2
        exit 2
      }
      [[ "$actual_default" == "$desktop_id" ]] || {
        echo "Default handler for $mime_type is $actual_default, expected $desktop_id." >&2
        exit 2
      }
    done

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
        gio open "$fixture"
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
      expected_mime=application/x-gameboy-rom
      [[ "$extension" != gbc ]] || expected_mime=application/x-gameboy-color-rom
      actual_default=$(xdg-mime query default "$expected_mime") || {
        echo "Could not re-query the default handler for $expected_mime after opening .$extension." >&2
        exit 2
      }
      [[ "$actual_default" == "$desktop_id" ]] || {
        echo "Opening .$extension changed the default handler for $expected_mime to $actual_default." >&2
        exit 2
      }
    done

    sudo dpkg --remove coffee-gb
    install_attempted=false
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
    app_pid=
    application_log=
    cleanup_macos() {
      local exit_status=$?
      if [[ -n "$app_pid" ]] && kill -0 "$app_pid" 2>/dev/null; then
        kill -TERM "$app_pid" 2>/dev/null || true
        local deadline=$((SECONDS + 10))
        while kill -0 "$app_pid" 2>/dev/null && [[ $SECONDS -lt $deadline ]]; do
          sleep 0.1
        done
        if kill -0 "$app_pid" 2>/dev/null; then
          kill -KILL "$app_pid" 2>/dev/null || true
        fi
        wait "$app_pid" 2>/dev/null || true
      fi
      "$lsregister" -u "$installed_app" >/dev/null 2>&1 || true
      # A failed attach can still leave a device mounted at this dedicated path.
      hdiutil detach "$mount_point" >/dev/null 2>&1 || true
      if (( exit_status != 0 )) &&
          [[ -n "$application_log" && -f "$application_log" ]]; then
        {
          echo "Installed macOS application log after association failure (last 64 KiB):"
          tail -c 65536 "$application_log" || true
        } >&2 || true
      fi
    }
    trap cleanup_macos EXIT

    hdiutil attach \
      -nobrowse \
      -readonly \
      -mountpoint "$mount_point" \
      "${installers[0]}" \
      >/dev/null \
      <<<Y || {
        echo "Could not mount the licensed macOS installer." >&2
        exit 2
      }
    source_apps=("$mount_point"/*.app)
    (( ${#source_apps[@]} == 1 )) || {
      echo "Expected exactly one application bundle in the DMG." >&2
      exit 2
    }
    ditto "${source_apps[0]}" "$installed_app" || {
      echo "Could not copy the application from the mounted DMG." >&2
      exit 2
    }
    hdiutil detach "$mount_point" >/dev/null || {
      echo "Could not detach the mounted DMG after copying the application." >&2
      exit 2
    }

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
    bundle_id=$(plutil -extract CFBundleIdentifier raw -o - "$info") || {
      echo "Could not read CFBundleIdentifier from the installed application." >&2
      exit 2
    }
    [[ "$bundle_id" == eu.rekawek.coffeegb ]] || {
      echo "Installed application has bundle identifier '$bundle_id'; expected eu.rekawek.coffeegb." >&2
      exit 2
    }
    documents=$(plutil -extract CFBundleDocumentTypes json -o - "$info") || {
      echo "Could not read CFBundleDocumentTypes from the installed application." >&2
      exit 2
    }
    for content_type in eu.rekawek.coffeegb.gb eu.rekawek.coffeegb.gbc; do
      grep -F "\"$content_type\"" <<<"$documents" >/dev/null || {
        echo "CFBundleDocumentTypes does not advertise $content_type." >&2
        exit 2
      }
    done
    exported_types=$(plutil -extract UTExportedTypeDeclarations json -o - "$info") || {
      echo "Could not read UTExportedTypeDeclarations from the installed application." >&2
      exit 2
    }
    for extension in gb gbc rom; do
      grep -F "\"$extension\"" <<<"$exported_types" >/dev/null || {
        echo "UTExportedTypeDeclarations does not advertise .$extension." >&2
        exit 2
      }
    done
    "$lsregister" -f "$installed_app" || {
      echo "Could not register the installed application with Launch Services." >&2
      exit 2
    }
    open -Ra "$installed_app" || {
      echo "Launch Services could not resolve the installed application." >&2
      exit 2
    }

    for extension in "${extensions[@]}"; do
      fixture="$smoke_root/Coffee GB association smoke.$extension"
      marker="$smoke_root/association-opened-$extension.marker"
      ready_marker="$smoke_root/desktop-ready-$extension.marker"
      shutdown_marker="$marker.shutdown"
      export COFFEE_GB_ASSOCIATION_SMOKE_MARKER="$marker"
      export COFFEE_GB_ASSOCIATION_SMOKE_ROM="$fixture"
      export COFFEE_GB_DESKTOP_SMOKE_MARKER="$ready_marker"
      application_log="$smoke_root/application-$extension.log"
      "$installed_app/Contents/MacOS/Coffee GB" \
        >"$application_log" \
        2>&1 &
      app_pid=$!
      await_file "$ready_marker" "installed macOS .$extension desktop readiness"
      kill -0 "$app_pid" 2>/dev/null || {
        echo "The installed macOS .$extension application exited before association dispatch." >&2
        exit 2
      }

      # Do not select Coffee GB explicitly here: Launch Services must choose the registered
      # default handler for each supported extension.
      open "$fixture" || {
        echo "Launch Services could not open the .$extension fixture." >&2
        exit 2
      }
      await_evidence "$marker" "default-handler macOS .$extension association result"
      assert_evidence "$marker" "$fixture" DESKTOP_OPEN_FILE || {
        echo "Installed macOS .$extension association evidence was incomplete:" >&2
        sed -n '1,$p' "$marker" >&2
        exit 2
      }
      pid=$(evidence_pid "$marker")
      [[ "$pid" == "$app_pid" ]] || {
        echo "macOS .$extension association evidence came from PID $pid; expected $app_pid." >&2
        exit 2
      }
      await_evidence "$shutdown_marker" "normal macOS .$extension association shutdown"
      assert_shutdown_evidence "$shutdown_marker" "$pid" || {
        echo "Installed macOS .$extension shutdown evidence was incomplete:" >&2
        sed -n '1,$p' "$shutdown_marker" >&2
        exit 2
      }

      deadline=$((SECONDS + 60))
      while kill -0 "$app_pid" 2>/dev/null && [[ $SECONDS -lt $deadline ]]; do
        sleep 0.1
      done
      if kill -0 "$app_pid" 2>/dev/null; then
        echo "The macOS .$extension application did not complete bounded shutdown." >&2
        exit 2
      fi
      wait "$app_pid" || {
        exit_status=$?
        echo "The installed macOS .$extension application exited with status $exit_status." >&2
        exit 2
      }
      app_pid=
      application_log=
    done

    "$lsregister" -u "$installed_app" || {
      echo "Could not unregister the installed application from Launch Services." >&2
      exit 2
    }
    rm -rf -- "$installed_app"
    [[ ! -e "$installed_app" ]] || {
      echo "The installed macOS application remained after cleanup." >&2
      exit 2
    }
    trap - EXIT
    ;;

  *)
    usage
    ;;
esac

echo "Installed file association smoke passed for $target."
