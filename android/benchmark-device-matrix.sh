#!/bin/sh
set -eu

# Physical-device M2 scheduler.
#
# The benchmark wire is intentionally kept here as one small list of constants.  The Android
# diagnostics side owns the event names; this host runner assumes the current contract is:
#   event=scenario_complete ... completed_frames=<exact> source_closed=true audio_drained=true
#   event=warmup_complete completed=true phase=warming
#   event=benchmark_anchor success=true phase=anchor_ready
#   event=final_result ... frame=600 ...
# The anchor event is a one-time, visible buffer on the same BLAST SurfaceView while the core is
# paused.  It is not inferred from process start or from a merely created Activity.  If that
# contract changes, update this block and the bounded event checks together.
#
# No argument below is a ROM, save, title, URI, path, or content selector.  Recent catalog slots
# are app-owned integers; they are passed only as --ei coffee_gb_recent_slot.  The only logs that
# leave the private temporary directory are CoffeeGbBench key/value records and the numeric
# compositor_result emitted by surface-timestats-gate.sh.

umask 077

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PACKAGE=eu.rekawek.coffeegb.android
ACTIVITY="$PACKAGE/.MainActivity"
DEVICE_SERIAL=4LJJSS7L8PWWMNWW
EXPECTED_MODEL=25078RA3EE
EXPECTED_DEVICE=dew
EXPECTED_API=35

EVENT_WARMUP=warmup_complete
EVENT_ANCHOR=benchmark_anchor
EVENT_SCENARIO=scenario_complete
EVENT_FINAL=final_result
EVENT_INVALIDATED=benchmark_invalidated

RUN_BLOCKS=12
RUN_ROWS=7
RUNS_PER_BLOCK=14
TOTAL_RUNS=$((RUN_BLOCKS * RUNS_PER_BLOCK))
ANCHOR_POLL_LIMIT=${COFFEE_GB_M2_ANCHOR_POLLS:-60}
FINAL_POLL_LIMIT=${COFFEE_GB_M2_FINAL_POLLS:-90}
POLL_SECONDS=${COFFEE_GB_M2_POLL_SECONDS:-1}
COOLDOWN_SECONDS=${COFFEE_GB_M2_COOLDOWN_SECONDS:-1}
REFRESH_WAIT_SECONDS=${COFFEE_GB_M2_REFRESH_WAIT_SECONDS:-1}

ADB_BIN=${COFFEE_GB_M2_ADB:-adb}
APKSIGNER_BIN=${COFFEE_GB_M2_APKSIGNER:-apksigner}
MATRIX_SCRIPT=${COFFEE_GB_M2_MATRIX_SCRIPT:-$SCRIPT_DIR/benchmark-matrix.sh}
GATE_SCRIPT=${COFFEE_GB_M2_GATE_SCRIPT:-$SCRIPT_DIR/surface-timestats-gate.sh}

usage() {
  echo "usage: benchmark-device-matrix.sh --parent-apk <signed.apk> --candidate-apk <signed.apk>" >&2
  echo "       --color-slot <0..9> --non-color-slot <0..9>" >&2
  echo "       [--execution-mode accuracy|performance] [--output-dir <dir>]" >&2
}

fatal() {
  echo "benchmark-device-matrix: $1" >&2
  exit 1
}

require_arg() {
  [ "$#" -ge 2 ] || { usage; exit 2; }
  [ -n "$2" ] || { usage; exit 2; }
}

validate_waits() {
  for wait_value in "$POLL_SECONDS" "$COOLDOWN_SECONDS" "$REFRESH_WAIT_SECONDS"; do
    case "$wait_value" in ''|*[!0-9]*) fatal "wait values must be non-negative integers" ;; esac
    [ "$wait_value" -le 10 ] || fatal "wait values must not exceed 10 seconds"
  done
  for poll_value in "$ANCHOR_POLL_LIMIT" "$FINAL_POLL_LIMIT"; do
    case "$poll_value" in ''|*[!0-9]*) fatal "poll limits must be positive integers" ;; esac
    [ "$poll_value" -gt 0 ] && [ "$poll_value" -le 600 ] \
      || fatal "poll limits must be between 1 and 600"
  done
}

validate_waits

parent_apk=
candidate_apk=
color_slot=
non_color_slot=
output_dir=
execution_mode=accuracy
parent_seen=false
candidate_seen=false
color_seen=false
non_color_seen=false
output_seen=false
execution_mode_seen=false

while [ "$#" -gt 0 ]; do
  case "$1" in
    --parent-apk)
      require_arg "$@"
      [ "$parent_seen" = false ] || { usage; exit 2; }
      parent_apk=$2
      parent_seen=true
      shift 2
      ;;
    --candidate-apk)
      require_arg "$@"
      [ "$candidate_seen" = false ] || { usage; exit 2; }
      candidate_apk=$2
      candidate_seen=true
      shift 2
      ;;
    --color-slot|--color-recent-slot|--color-recent-catalog-slot)
      require_arg "$@"
      [ "$color_seen" = false ] || { usage; exit 2; }
      color_slot=$2
      color_seen=true
      shift 2
      ;;
    --non-color-slot|--noncolor-slot|--non-color-recent-slot|--non-color-recent-catalog-slot)
      require_arg "$@"
      [ "$non_color_seen" = false ] || { usage; exit 2; }
      non_color_slot=$2
      non_color_seen=true
      shift 2
      ;;
    --output-dir|-o)
      require_arg "$@"
      [ "$output_seen" = false ] || { usage; exit 2; }
      output_dir=$2
      output_seen=true
      shift 2
      ;;
    --execution-mode|--mode)
      require_arg "$@"
      [ "$execution_mode_seen" = false ] || { usage; exit 2; }
      execution_mode=$(printf '%s' "$2" | tr 'A-Z' 'a-z')
      execution_mode_seen=true
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

[ -n "$parent_apk" ] && [ -n "$candidate_apk" ] \
  && [ -n "$color_slot" ] && [ -n "$non_color_slot" ] || { usage; exit 2; }

case "$execution_mode" in
  accuracy|performance) : ;;
  *) fatal "execution mode must be accuracy or performance" ;;
esac

case "$color_slot" in 0|1|2|3|4|5|6|7|8|9) : ;; *) fatal "color catalog slot must be 0..9" ;; esac
case "$non_color_slot" in 0|1|2|3|4|5|6|7|8|9) : ;; *) fatal "non-color catalog slot must be 0..9" ;; esac
[ "$color_slot" != "$non_color_slot" ] || fatal "color and non-color catalog slots must differ"

[ -f "$parent_apk" ] && [ -r "$parent_apk" ] || fatal "parent APK is not a readable regular file"
[ -f "$candidate_apk" ] && [ -r "$candidate_apk" ] || fatal "candidate APK is not a readable regular file"

if [ -n "$output_dir" ]; then
  if [ -e "$output_dir" ] && [ ! -d "$output_dir" ]; then
    fatal "output path is not a directory"
  fi
  mkdir -p "$output_dir" || fatal "could not create output directory"
  chmod 700 "$output_dir" || fatal "could not make output directory private"
  [ ! -e "$output_dir/matrix.log" ] || fatal "output matrix.log already exists"
  [ ! -e "$output_dir/matrix-report.txt" ] || fatal "output matrix-report.txt already exists"
fi

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-m2.XXXXXX") \
  || fatal "could not create private temporary directory"
display_settings_saved=false
cleanup() {
  if [ "${display_settings_saved:-false}" = true ]; then
    for setting in peak_refresh_rate min_refresh_rate user_refresh_rate; do
      original_file="$tmp_dir/original.$setting"
      if [ -f "$original_file" ]; then
        original_value=$(awk 'NF { print $1; exit }' "$original_file")
        if [ -z "$original_value" ] || [ "$original_value" = null ]; then
          "$ADB_BIN" -s "$DEVICE_SERIAL" shell settings delete system "$setting" \
            >/dev/null 2>&1 || :
        else
          "$ADB_BIN" -s "$DEVICE_SERIAL" shell settings put system "$setting" "$original_value" \
            >/dev/null 2>&1 || :
        fi
      fi
    done
  fi
  rm -rf "$tmp_dir"
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

verify_apk() {
  apk=$1
  label=$2
  verbose_file=$tmp_dir/$label.verify
  cert_file=$tmp_dir/$label.certs
  if ! "$APKSIGNER_BIN" verify --verbose "$apk" >"$verbose_file" 2>&1; then
    fatal "$label APK did not verify"
  fi
  if awk 'BEGIN { bad=0 } tolower($0) ~ /does not verify|verification failed|error:/ { bad=1 } END { exit bad }' \
      "$verbose_file"; then
    :
  else
    fatal "$label APK verification output was not clean"
  fi
  if ! "$APKSIGNER_BIN" verify --print-certs "$apk" >"$cert_file" 2>&1; then
    fatal "$label APK certificate inspection failed"
  fi
  apk_cert=$(awk '
    /certificate SHA-256 digest:/ {
      line=$0
      sub(/^.*certificate SHA-256 digest:[[:space:]]*/, "", line)
      gsub(/[^0-9A-Fa-f]/, "", line)
      count++
      value=tolower(line)
      if (length(value) != 64) bad=1
    }
    END {
      if (count != 1 || bad) exit 1
      print value
    }
  ' "$cert_file" 2>/dev/null) || fatal "$label APK does not have exactly one SHA-256 signer certificate"
  case "$apk_cert" in
    ''|*[!0-9a-f]*) fatal "$label APK signer certificate is malformed" ;;
  esac
  [ "${#apk_cert}" -eq 64 ] || fatal "$label APK signer certificate is malformed"
  printf '%s\n' "$apk_cert"
}

hash_file() {
  file=$1
  hash_output=
  if [ -n "${COFFEE_GB_M2_SHA256:-}" ]; then
    if ! hash_output=$("$COFFEE_GB_M2_SHA256" "$file" 2>/dev/null); then
      return 1
    fi
  elif command -v sha256sum >/dev/null 2>&1; then
    if ! hash_output=$(sha256sum "$file" 2>/dev/null); then
      return 1
    fi
  elif command -v shasum >/dev/null 2>&1; then
    if ! hash_output=$(shasum -a 256 "$file" 2>/dev/null); then
      return 1
    fi
  else
    return 1
  fi
  hash_value=$(printf '%s\n' "$hash_output" | awk 'NR == 1 { print $1; exit }')
  case "$hash_value" in
    ''|*[!0-9A-Fa-f]*) return 1 ;;
  esac
  [ "${#hash_value}" -eq 64 ] || return 1
  printf '%s\n' "$hash_value" | tr 'A-F' 'a-f'
}

parent_cert=$(verify_apk "$parent_apk" parent)
candidate_cert=$(verify_apk "$candidate_apk" candidate)
[ "$parent_cert" = "$candidate_cert" ] || fatal "parent and candidate signer certificates differ"
parent_hash=$(hash_file "$parent_apk") || fatal "could not hash parent signed APK"
candidate_hash=$(hash_file "$candidate_apk") || fatal "could not hash candidate signed APK"
[ "$parent_hash" != "$candidate_hash" ] || fatal "parent and candidate signed APKs are identical"

if [ -n "$output_dir" ]; then
  aggregate_copy=$output_dir/matrix.log
  report_copy=$output_dir/matrix-report.txt
fi

# Every command that can return device text writes to a private file first.  In particular, no
# adb diagnostic output is allowed to reach the terminal: SurfaceFlinger dumps can contain layer
# names, and package/activity diagnostics are not matrix evidence.
adb_global_capture() {
  destination=$1
  shift
  "$ADB_BIN" "$@" >"$destination" 2>&1
}

adb_capture() {
  destination=$1
  shift
  "$ADB_BIN" -s "$DEVICE_SERIAL" "$@" >"$destination" 2>&1
}

adb_global_capture "$tmp_dir/devices" devices -l \
  || fatal "adb device discovery failed"
device_lines=$(awk '
  $1 !~ /^List$/ && (($2 ~ /^(device|offline|unauthorized|bootloader|recovery)$/) \
      || ($2 == "no" && $3 == "permissions")) { count++ }
  END { print count + 0 }
' "$tmp_dir/devices")
[ "$device_lines" -eq 1 ] || fatal "expected exactly one connected device"
device_line=$(awk '
  $1 !~ /^List$/ && (($2 ~ /^(device|offline|unauthorized|bootloader|recovery)$/) \
      || ($2 == "no" && $3 == "permissions")) { print $1 "\t" $2 }
' "$tmp_dir/devices")
device_name=$(printf '%s\n' "$device_line" | awk -F '\t' '{ print $1 }')
device_state=$(printf '%s\n' "$device_line" | awk -F '\t' '{ print $2 }')
[ "$device_name" = "$DEVICE_SERIAL" ] || fatal "connected device serial is not the required Redmi device"
[ "$device_state" = device ] || fatal "required device is not ready"

getprop_one() {
  property=$1
  destination=$2
  adb_capture "$destination" shell getprop "$property" \
    || fatal "device property query failed"
  value_count=$(awk 'NF { count++ } END { print count + 0 }' "$destination")
  [ "$value_count" -eq 1 ] || fatal "device property response was ambiguous"
  awk 'NF { print; exit }' "$destination"
}

model=$(getprop_one ro.product.model "$tmp_dir/model")
device=$(getprop_one ro.product.device "$tmp_dir/device")
api=$(getprop_one ro.build.version.sdk "$tmp_dir/api")
[ "$model" = "$EXPECTED_MODEL" ] || fatal "device model is not the required Redmi model"
[ "$device" = "$EXPECTED_DEVICE" ] || fatal "device product is not dew"
[ "$api" = "$EXPECTED_API" ] || fatal "device API level is not 35"

adb_capture "$tmp_dir/state" get-state || fatal "required device state query failed"
[ "$(awk 'NF { print $1; exit }' "$tmp_dir/state")" = device ] \
  || fatal "required device did not report device state"

adb_shell_capture() {
  destination=$1
  shift
  adb_capture "$destination" shell "$@"
}

adb_shell_checked() {
  destination=$tmp_dir/cmd.$$.${adb_command_counter:-0}
  adb_command_counter=$((${adb_command_counter:-0} + 1))
  adb_shell_capture "$destination" "$@" \
    || fatal "device command failed"
}

bounded_sleep() {
  seconds=$1
  [ "${COFFEE_GB_M2_FAST:-0}" = 1 ] && return 0
  # Constants are deliberately below ten seconds; this is a refresh/cooldown wait, never an
  # unbounded sleep.  A test may set COFFEE_GB_M2_FAST=1 without touching a physical device.
  sleep "$seconds"
}

check_host_environment() {
  adb_shell_capture "$tmp_dir/power" dumpsys power \
    || fatal "power state query failed"
  if ! awk '
    tolower($0) ~ /minteractive[[:space:]]*=[[:space:]]*true/ { interactive=1 }
    tolower($0) ~ /mwakefulness[[:space:]]*=[[:space:]]*awake/ { awake=1 }
    END { exit(interactive && awake ? 0 : 1) }
  ' "$tmp_dir/power"; then
    fatal "display is not interactive and awake"
  fi

  adb_shell_capture "$tmp_dir/battery" dumpsys battery \
    || fatal "battery state query failed"
  if ! awk 'tolower($0) ~ /(ac|usb|wireless) powered[[:space:]]*:[[:space:]]*true/ \
      || tolower($0) ~ /^[[:space:]]*powered[[:space:]]*:[[:space:]]*true/ { ok=1 }
      END { exit(ok ? 0 : 1) }' "$tmp_dir/battery"; then
    fatal "device is not physically plugged in"
  fi
  plugged_mask=$(awk -F: 'tolower($1) ~ /^[[:space:]]*plugged[[:space:]]*$/ { gsub(/[[:space:]]/, "", $2); print $2; exit }' "$tmp_dir/battery")
  case "$plugged_mask" in ''|*[!0-9]*) fatal "battery plugged mask is unavailable" ;; esac
  [ "$plugged_mask" -gt 0 ] || fatal "device is not plugged into a power source"

  adb_shell_capture "$tmp_dir/stay" settings get global stay_on_while_plugged_in \
    || fatal "stay-awake setting query failed"
  stay_value=$(awk 'NF { print $1; exit }' "$tmp_dir/stay")
  case "$stay_value" in ''|*[!0-9]*) fatal "stay-awake setting is unavailable" ;; esac
  if ! awk -v stay="$stay_value" -v plugged="$plugged_mask" '
    BEGIN {
      # POSIX awk has no portable bitwise operators.  Check every set plug bit by
      # repeated division, so combinations such as AC|USB (3) are handled too.
      if (plugged < 1) exit 1
      for (bit = 1; bit <= plugged; bit *= 2) {
        if (int(plugged / bit) % 2 == 1 && int(stay / bit) % 2 != 1) exit 1
      }
      exit 0
    }
  '; then
    fatal "stay-awake is not enabled for the connected power source"
  fi

  adb_shell_capture "$tmp_dir/low-power" settings get global low_power \
    || fatal "power-save setting query failed"
  low_power_value=$(awk 'NF { print tolower($1); exit }' "$tmp_dir/low-power")
  case "$low_power_value" in 0|false|off) : ;; *) fatal "power-save mode is enabled" ;; esac

  adb_shell_capture "$tmp_dir/thermal" dumpsys thermalservice \
    || fatal "thermal state query failed"
  if ! awk '
    {
      lower=tolower($0)
      # API35 prints a non-authoritative `ThermalStatusListeners:` header followed by the
      # authoritative scalar line.  Do not treat the header as a status sample.
      if (lower ~ /^[[:space:]]*thermal status[[:space:]]*:/) {
        value=lower
        sub(/^[^:]*:[[:space:]]*/, "", value)
        if (value == "0" || value ~ /^none([[:space:]]|$)/) { ok=1 } else { bad=1 }
        seen++
      }
    }
    END { exit(seen == 1 && ok && !bad ? 0 : 1) }
  ' "$tmp_dir/thermal"; then
    fatal "thermal status is unavailable or not NONE"
  fi
}

pin_display_rate() {
  rate=$1
  adb_shell_checked settings put system peak_refresh_rate "$rate"
  adb_shell_checked settings put system min_refresh_rate "$rate"
  adb_shell_checked settings put system user_refresh_rate "$rate"
  verify_display_rate "$rate"
}

verify_display_rate() {
  rate=$1
  adb_shell_capture "$tmp_dir/display" cmd display get-displays \
    || fatal "display mode query failed"
  if ! awk -v wanted="$rate" '
    function line_rate(line, lower, text) {
      lower=tolower(line)
      if (lower ~ /renderframerate[[:space:]]*[0-9]/) {
        text=lower
        sub(/^.*renderframerate[[:space:]]*/, "", text)
        if (match(text, /^[0-9]+([.][0-9]+)?/)) {
          return int(substr(text, RSTART, RLENGTH) + 0.5)
        }
      }
      if (lower ~ /refresh[[:space:]_]*rate([^a-z]|$)/) {
        text=lower
        sub(/^.*refresh[[:space:]_]*rate[^0-9]*/, "", text)
        if (match(text, /^[0-9]+([.][0-9]+)?/)) {
          return int(substr(text, RSTART, RLENGTH) + 0.5)
        }
      }
      if (lower ~ /fps/) {
        text=lower
        if (match(text, /[0-9]+([.][0-9]+)?[[:space:]]*fps/)) {
          text=substr(text, RSTART, RLENGTH)
          sub(/[[:space:]]*fps.*$/, "", text)
          return int(text + 0.5)
        }
      }
      return -1
    }
    {
      lower=tolower($0)
      value=line_rate($0)
      if (value >= 0 && lower ~ /(active|current|committed|displayinfo|displaymode|refresh[[:space:]_]*rate([^a-z]|$)|fps)/) {
        if (lower ~ /(active|current|committed)/) { active_count++; active_bad += value != wanted }
        else { all_count++; all_bad += value != wanted }
      }
    }
    END {
      if (active_count > 0) exit(active_bad == 0 ? 0 : 1)
      exit(all_count > 0 && all_bad == 0 ? 0 : 1)
    }
  ' "$tmp_dir/display"; then
    fatal "active display mode did not match the pinned refresh rate"
  fi
}

save_display_settings() {
  for setting in peak_refresh_rate min_refresh_rate user_refresh_rate; do
    adb_shell_capture "$tmp_dir/original.$setting" settings get system "$setting" \
      || fatal "display setting query failed"
  done
  display_settings_saved=true
}

# Verify once, then again before each measured side.  No stay/power setting is silently changed:
# a plugged device with the corresponding stay-on bit and low_power=0 is an eligibility condition.
# No package manager/data reset is ever issued; install -r is the only installation form and
# preserves the app-owned catalog.
check_host_environment
save_display_settings

uid=
resolve_uid() {
  adb_shell_capture "$tmp_dir/package-uid" cmd package list packages -U "$PACKAGE" \
    || fatal "package UID query failed"
  uid_value=$(awk -v wanted="package:$PACKAGE" '
    $1 == wanted {
      for (i = 2; i <= NF; i++) if ($i ~ /^uid:[0-9]+$/) {
        count++
        value=$i
        sub(/^uid:/, "", value)
      }
    }
    END {
      if (count != 1) exit 1
      print value
    }
  ' "$tmp_dir/package-uid" 2>/dev/null) || fatal "package UID response was ambiguous"
  case "$uid_value" in ''|*[!0-9]*) fatal "package UID is malformed" ;; esac
  [ "$uid_value" -gt 0 ] || fatal "package UID is invalid"
  uid=$uid_value
}

verify_installed_profile() {
  adb_shell_capture "$tmp_dir/package-dump" dumpsys package "$PACKAGE" \
    || fatal "installed package metadata query failed"
  if ! awk '
    /ApplicationInfo/ || /pkgFlags/ || /privateFlags/ { seen=1 }
    tolower($0) ~ /debuggable/ { debug=1 }
    END { exit(seen && !debug ? 0 : 1) }
  ' "$tmp_dir/package-dump"; then
    fatal "installed benchmark package is missing or debuggable"
  fi
}

resolve_layer() {
  destination=$tmp_dir/layers
  adb_shell_capture "$destination" dumpsys SurfaceFlinger --list \
    || fatal "SurfaceFlinger layer listing failed"
  # --list commonly wraps the layer as RequestedLayerState{...}.  Extract only the exact
  # SurfaceView[...](BLAST)#id token; passing the wrapper to the TimeStats gate would be an
  # ambiguous layer identity.
  sed -n 's/.*\(SurfaceView\[[^]]*\](BLAST)#[0-9][0-9]*\).*/\1/p' "$destination" \
    | awk -v package_name="$PACKAGE" 'index($0, package_name) > 0 { print }' \
    >"$tmp_dir/layer-candidates"
  layer_count=$(awk 'NF { count++ } END { print count + 0 }' "$tmp_dir/layer-candidates")
  [ "$layer_count" -eq 1 ] || fatal "SurfaceFlinger did not expose exactly one active BLAST layer"
  layer_value=$(awk 'NF { print; exit }' "$tmp_dir/layer-candidates")
  case "$layer_value" in
    *"="*|*"|"*|*";"*|*'\`'*|*'$'*|*'\\'*)
      fatal "SurfaceFlinger layer name is ambiguous"
      ;;
  esac
  layer=$layer_value
}

clear_benchmark_log() {
  # logcat filters bound both clear/capture to the diagnostic tag.  No `pm clear`, `rm`, or other
  # application-data operation is present in this workflow.
  adb_capture "$tmp_dir/logcat-clear" logcat -c -s CoffeeGbBench:I '*:S' \
    || fatal "bounded CoffeeGbBench log clear failed"
}

capture_benchmark_log() {
  destination=$1
  adb_capture "$destination" logcat -d -v threadtime -s CoffeeGbBench:I '*:S' \
    || fatal "bounded CoffeeGbBench log capture failed"
}

event_line() {
  file=$1
  event=$2
  awk -v wanted="event=$event" '
    {
      for (i = 1; i <= NF; i++) if ($i == wanted) { print; exit }
    }
  ' "$file"
}

event_count() {
  file=$1
  event=$2
  awk -v wanted="event=$event" '
    { for (i = 1; i <= NF; i++) if ($i == wanted) { count++; break } }
    END { print count + 0 }
  ' "$file"
}

line_field() {
  key=$1
  line=$2
  printf '%s\n' "$line" | awk -v wanted="$key" '
    {
      for (i = 1; i <= NF; i++) {
        if (index($i, wanted "=") == 1) {
          value=$i
          sub("^" wanted "=", "", value)
          print value
          exit
        }
      }
    }
  '
}

require_line_field() {
  line=$1
  key=$2
  expected=$3
  actual=$(line_field "$key" "$line")
  [ "$actual" = "$expected" ] || fatal "final benchmark evidence has an unexpected $key"
}

valid_opaque_token() {
  token=$1
  case "$token" in ''|unknown|invalid|*[!a-z0-9._-]*) return 1 ;; esac
  [ "${#token}" -ge 16 ] && [ "${#token}" -le 64 ]
}

check_redacted_lines() {
  file=$1
  # The event `rom_open_start` is an allowed lifecycle label.  Scan the complete original log
  # line before copying any substring: a forbidden prefix before event= must not evade the check.
  # Threadtime's `HH:MM:SS` prefix is benign, so avoid a generic colon heuristic and inspect only
  # workload keys/extensions and path separators.  Case-folding also catches GAME.GB probes.
  if awk '
    {
      raw=tolower($0)
      scan=$0
      tag=index(scan, "CoffeeGbBench:")
      if (tag > 0) scan=substr(scan, tag + length("CoffeeGbBench:"))
      lower=tolower(scan)
      if (index($0, "CoffeeGbBench") > 0 && index($0, "event=") == 0) bad=1
      if (raw ~ /(^|[[:space:]])(rom|save|uri|title|content|payload|path|file|filename|rom_path|save_path|rom_uri|save_uri)=/) bad=1
      if (lower ~ /[\/\\]|:\/\//) bad=1
      if (raw ~ /\.(gb|gbc|sgb|sav|rom|cgbstate)([^a-z]|$)/) bad=1
    }
    END { exit bad ? 1 : 0 }
  ' "$file"; then
    :
  else
    fatal "CoffeeGbBench emitted a workload-bearing field"
  fi
}

wait_for_anchor() {
  warmup_seen=false
  anchor_seen=false
  poll=0
  while [ "$poll" -lt "$ANCHOR_POLL_LIMIT" ]; do
    poll=$((poll + 1))
    capture_benchmark_log "$tmp_dir/logcat"
    check_redacted_lines "$tmp_dir/logcat"
    [ "$(event_count "$tmp_dir/logcat" "$EVENT_WARMUP")" -le 1 ] \
      || fatal "multiple warmup_complete events were observed"
    [ "$(event_count "$tmp_dir/logcat" "$EVENT_ANCHOR")" -le 1 ] \
      || fatal "multiple benchmark_anchor events were observed"
    warmup_line=$(event_line "$tmp_dir/logcat" "$EVENT_WARMUP")
    anchor_line=$(event_line "$tmp_dir/logcat" "$EVENT_ANCHOR")
    if [ -n "$warmup_line" ]; then
      [ "$(line_field completed "$warmup_line")" = true ] || fatal "warmup did not complete"
      [ "$(line_field phase "$warmup_line")" = warming ] || fatal "warmup phase is not warming"
      warmup_seen=true
    fi
    if [ -n "$anchor_line" ]; then
      [ "$(line_field success "$anchor_line")" = true ] || fatal "benchmark anchor was unsuccessful"
      [ "$(line_field phase "$anchor_line")" = anchor_ready ] || fatal "anchor phase is not anchor_ready"
      anchor_seen=true
    fi
    if [ "$warmup_seen" = true ] && [ "$anchor_seen" = true ]; then
      return 0
    fi
    if ! lifecycle_ok; then
      fatal "benchmark Activity lost visibility while waiting for anchor"
    fi
    if [ $((poll % 10)) -eq 0 ]; then
      printf 'wait=anchor poll=%s/%s\n' "$poll" "$ANCHOR_POLL_LIMIT"
    fi
    bounded_sleep "$POLL_SECONDS"
  done
  fatal "timed out waiting for benchmark anchor"
}

lifecycle_ok() {
  adb_shell_capture "$tmp_dir/activity" dumpsys activity activities \
    || return 1
  awk -v package_name="$PACKAGE" '
    tolower($0) ~ /(mresumedactivity|resumedactivity)/ && index($0, package_name) > 0 \
      && index($0, "MainActivity") > 0 { ok=1 }
    END { exit(ok ? 0 : 1) }
  ' "$tmp_dir/activity"
}

wait_for_final() {
  poll=0
  while [ "$poll" -lt "$FINAL_POLL_LIMIT" ]; do
    poll=$((poll + 1))
    capture_benchmark_log "$tmp_dir/logcat"
    check_redacted_lines "$tmp_dir/logcat"
    if [ "$(event_count "$tmp_dir/logcat" "$EVENT_FINAL")" -gt 1 ]; then
      fatal "multiple final_result events were observed"
    fi
    final_line=$(event_line "$tmp_dir/logcat" "$EVENT_FINAL")
    if [ -n "$final_line" ]; then
      final_result_line=$final_line
      return 0
    fi
    if ! lifecycle_ok; then
      fatal "benchmark Activity lost visibility while waiting for final_result"
    fi
    if [ $((poll % 10)) -eq 0 ]; then
      printf 'wait=final poll=%s/%s\n' "$poll" "$FINAL_POLL_LIMIT"
    fi
    bounded_sleep "$POLL_SECONDS"
  done
  fatal "timed out waiting for final_result"
}

make_token() {
  prefix=$1
  token_bytes=
  if [ -r /dev/urandom ]; then
    token_bytes=$(od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d '[:space:]') || token_bytes=
  fi
  if [ -z "$token_bytes" ]; then
    token_counter=$((${token_counter:-0} + 1))
    token_bytes=$(awk -v seed="$random_seed" -v n="$token_counter" \
      'BEGIN { srand(seed + n); for (i = 0; i < 4; i++) printf "%08x", int(rand() * 4294967295) }')
  fi
  printf '%s%s\n' "$prefix" "$token_bytes"
}

random_seed=${COFFEE_GB_M2_SEED:-${COFFEE_GB_TEST_SEED:-}}
if [ -z "$random_seed" ]; then
  random_seed=$(od -An -N4 -tu4 /dev/urandom 2>/dev/null | awk 'NF { print $1; exit }') || random_seed=
fi
[ -n "$random_seed" ] || random_seed=$$
case "$random_seed" in *[!0-9]*) random_seed=$$ ;; esac

schedule_file=$tmp_dir/schedule
awk -v seed="$random_seed" '
  BEGIN {
    srand(seed + 0)
    row[0]="dmg"; row[1]="mgb"; row[2]="cgb-native"; row[3]="cgb0-native"
    row[4]="cgb-dmg-compat"; row[5]="sgb"; row[6]="sgb2"
    for (block = 0; block < 12; block++) {
      for (i = 0; i < 7; i++) order[i] = i
      for (i = 6; i > 0; i--) {
        j = int(rand() * (i + 1))
        temp = order[i]; order[i] = order[j]; order[j] = temp
      }
      first = (block % 2 == 0) ? "parent" : "candidate"
      for (i = 0; i < 7; i++) printf "%02d %d %s %s\n", block, i, row[order[i]], first
    }
  }
' >"$schedule_file" || fatal "could not create randomized schedule"

schedule_signatures=$(awk '{ signature[$1] = signature[$1] $3 "/" } END { for (b in signature) print signature[b] }' \
  "$schedule_file" | sort -u | awk 'NF { count++ } END { print count + 0 }')
[ "$schedule_signatures" -ge 2 ] || fatal "randomized schedule did not produce two row orders"

aggregate=$tmp_dir/matrix.log
: >"$aggregate"
layer=
device_id=
color_emitted_nonce=
non_color_emitted_nonce=
run_number=0

row_profile() {
  case "$1" in
    dmg) printf '%s\n' dmg ;;
    mgb) printf '%s\n' mgb ;;
    cgb-native|cgb-dmg-compat) printf '%s\n' cgb ;;
    cgb0-native) printf '%s\n' cgb0 ;;
    sgb) printf '%s\n' sgb ;;
    sgb2) printf '%s\n' sgb2 ;;
    *) return 1 ;;
  esac
}

row_slot() {
  case "$1" in
    cgb-native|cgb0-native) printf '%s\n' "$color_slot" ;;
    dmg|mgb|cgb-dmg-compat|sgb|sgb2) printf '%s\n' "$non_color_slot" ;;
    *) return 1 ;;
  esac
}

row_rate() {
  case "$1" in
    sgb) printf '%s\n' 120 ;;
    dmg|mgb|cgb-native|cgb0-native|cgb-dmg-compat|sgb2) printf '%s\n' 60 ;;
    *) return 1 ;;
  esac
}

row_content_rate_millihz() {
  case "$1" in
    sgb) printf '%s\n' 61168 ;;
    dmg|mgb|cgb-native|cgb0-native|cgb-dmg-compat|sgb2) printf '%s\n' 59728 ;;
    *) return 1 ;;
  esac
}

row_nominal_fps() {
  case "$1" in
    sgb) printf '%s\n' 61.1679 ;;
    dmg|mgb|cgb-native|cgb0-native|cgb-dmg-compat|sgb2) printf '%s\n' 59.7275 ;;
    *) return 1 ;;
  esac
}

row_effective_gbc() {
  case "$1" in
    cgb-native|cgb0-native|cgb-dmg-compat) printf '%s\n' true ;;
    dmg|mgb|sgb|sgb2) printf '%s\n' false ;;
    *) return 1 ;;
  esac
}

row_effective_dmg_compat() {
  case "$1" in
    cgb-dmg-compat) printf '%s\n' true ;;
    dmg|mgb|cgb-native|cgb0-native|sgb|sgb2) printf '%s\n' false ;;
    *) return 1 ;;
  esac
}

row_input_contract() {
  case "$1" in
    dmg|mgb) printf '%s\n' dmg-action-v1 ;;
    cgb-native|cgb0-native) printf '%s\n' cgb-action-v1 ;;
    cgb-dmg-compat) printf '%s\n' dmg-action-v1 ;;
    sgb|sgb2) printf '%s\n' none ;;
    *) return 1 ;;
  esac
}

row_scenario_frames() {
  case "$1" in
    dmg|mgb|cgb-dmg-compat) printf '%s\n' 313 ;;
    cgb-native|cgb0-native) printf '%s\n' 923 ;;
    sgb|sgb2) printf '%s\n' 0 ;;
    *) return 1 ;;
  esac
}

run_one() {
  block=$1
  row_order=$2
  row=$3
  first_side=$4
  run_side=$5
  apk=$6
  artifact_id=$7
  profile=$(row_profile "$row") || fatal "unknown scheduled row"
  slot=$(row_slot "$row") || fatal "unknown scheduled slot"
  rate=$(row_rate "$row") || fatal "unknown scheduled display rate"
  content_rate=$(row_content_rate_millihz "$row") || fatal "unknown scheduled content rate"
  nominal_fps=$(row_nominal_fps "$row") || fatal "unknown scheduled nominal FPS"
  expected_gbc=$(row_effective_gbc "$row") || fatal "unknown scheduled CGB mode"
  expected_compat=$(row_effective_dmg_compat "$row") || fatal "unknown scheduled compatibility mode"
  input_contract=$(row_input_contract "$row") || fatal "unknown scheduled input contract"
  scenario_frames=$(row_scenario_frames "$row") || fatal "unknown scheduled scenario length"
  pair_id=p${block}-${row}
  matrix_block=mb${block}
  arm_token=$(make_token a)

  run_number=$((run_number + 1))
  printf 'run=%s/%s block=%s row_order=%s row=%s side=%s first_side=%s mode=%s rate=%s\n' \
    "$run_number" "$TOTAL_RUNS" "$matrix_block" "$row_order" "$row" "$run_side" \
    "$first_side" "$execution_mode" "$rate"

  adb_shell_checked am force-stop "$PACKAGE"
  if ! adb_capture "$tmp_dir/install" install -r -d --no-streaming "$apk"; then
    fatal "selected signed APK installation failed"
  fi
  if ! awk 'tolower($0) ~ /(^|[^a-z])success([^a-z]|$)/ { ok=1 } END { exit(ok ? 0 : 1) }' \
      "$tmp_dir/install"; then
    fatal "selected signed APK installation was not confirmed"
  fi
  # Verify the environment after installation and before the visible launch; install -r never
  # receives a clear-data flag, so app-owned recent data remains intact.
  pin_display_rate "$rate"
  check_host_environment
  resolve_uid
  verify_installed_profile
  clear_benchmark_log
  # API35 keeps SurfaceFlinger TimeStats disabled unless explicitly enabled.  Clear its bounded
  # counters before this launch so historical Coffee GB layers cannot enter the after-minus-before
  # delta.  This is diagnostic state only; no app/ROM/save data is touched.
  adb_shell_checked dumpsys SurfaceFlinger --timestats -enable
  adb_shell_checked dumpsys SurfaceFlinger --timestats -clear

  adb_capture "$tmp_dir/launch" shell am start -W -n "$ACTIVITY" \
    --ez coffee_gb_benchmark true \
    --es coffee_gb_hardware "$profile" \
    --ez coffee_gb_audio true \
    --es coffee_gb_render presentation \
    --ez coffee_gb_warmup true \
    --ez coffee_gb_recent true \
    --es coffee_gb_build_id "$artifact_id" \
    --es coffee_gb_pair_id "$pair_id" \
    --es coffee_gb_matrix_block "$matrix_block" \
    --ei coffee_gb_row_order "$row_order" \
    --es coffee_gb_run_side "$run_side" \
    --es coffee_gb_first_side "$first_side" \
    --es coffee_gb_device_build 25078ra3ee-dew-api35 \
    --es coffee_gb_thermal_window m2 \
    --ez coffee_gb_thermal_valid true \
    --ei coffee_gb_surface_rate_hz "$rate" \
    --es coffee_gb_execution_mode "$execution_mode" \
    --es coffee_gb_benchmark_scenario "$input_contract" \
    --ei coffee_gb_recent_slot "$slot" \
    || fatal "visible benchmark Activity launch failed"
  if ! awk 'tolower($0) ~ /status:[[:space:]]*ok/ { ok=1 } END { exit(ok ? 0 : 1) }' "$tmp_dir/launch"; then
    fatal "visible benchmark Activity did not report a successful launch"
  fi
  if ! lifecycle_ok; then
    fatal "benchmark Activity is not visible after launch"
  fi

  wait_for_anchor
  bounded_sleep "$REFRESH_WAIT_SECONDS"
  verify_display_rate "$rate"
  resolve_layer
  check_host_environment
  if ! adb_shell_capture "$tmp_dir/before.txt" dumpsys SurfaceFlinger --timestats -dump; then
    fatal "SurfaceFlinger TimeStats baseline failed"
  fi

  # Exactly one arm intent is sent.  0x20000000 is FLAG_ACTIVITY_SINGLE_TOP; MainActivity's
  # onNewIntent consumes the opaque token and does not reload the catalog selection.
  adb_capture "$tmp_dir/arm" shell am start -W -n "$ACTIVITY" -f 0x20000000 \
    --es coffee_gb_benchmark_arm_token "$arm_token" \
    || fatal "singleTop benchmark arm intent failed"
  if ! awk 'tolower($0) ~ /status:[[:space:]]*ok/ { ok=1 } END { exit(ok ? 0 : 1) }' "$tmp_dir/arm"; then
    fatal "singleTop benchmark arm intent was not confirmed"
  fi
  wait_for_final
  final_line=$final_result_line

  # Matrix identity is app evidence, not a host relabel.  Pin every field which can otherwise
  # turn a stale lifecycle/log record into an accepted row.
  scenario_count=$(event_count "$tmp_dir/logcat" "$EVENT_SCENARIO")
  scenario_generation=0
  if [ "$input_contract" = none ]; then
    [ "$scenario_count" -eq 0 ] \
      || fatal "no-input row emitted unexpected scenario_complete evidence"
  else
    [ "$scenario_count" -eq 1 ] \
      || fatal "scripted row scenario_complete evidence was absent or ambiguous"
    scenario_line=$(event_line "$tmp_dir/logcat" "$EVENT_SCENARIO")
    require_line_field "$scenario_line" artifact_id "$artifact_id"
    require_line_field "$scenario_line" pair_id "$pair_id"
    require_line_field "$scenario_line" matrix_block "$matrix_block"
    require_line_field "$scenario_line" row_order "$row_order"
    require_line_field "$scenario_line" run_side "$run_side"
    require_line_field "$scenario_line" input_contract "$input_contract"
    require_line_field "$scenario_line" completed true
    require_line_field "$scenario_line" completed_frames "$scenario_frames"
    require_line_field "$scenario_line" expected_frames "$scenario_frames"
    require_line_field "$scenario_line" source_closed true
    require_line_field "$scenario_line" audio_drained true
    scenario_generation=$(line_field session_generation "$scenario_line")
    case "$scenario_generation" in
      ''|*[!0-9]*) fatal "scenario session generation is malformed" ;;
    esac
    [ "$scenario_generation" -gt 0 ] || fatal "scenario session generation is invalid"
  fi
  matrix_run_count=$(event_count "$tmp_dir/logcat" matrix_run)
  [ "$matrix_run_count" -eq 1 ] || fatal "matrix_run evidence was absent or ambiguous"
  matrix_run_line=$(event_line "$tmp_dir/logcat" matrix_run)
  benchmark_generation=$(line_field benchmark_generation "$final_line")
  case "$benchmark_generation" in ''|*[!0-9]*) fatal "final benchmark generation is malformed" ;; esac
  [ "$benchmark_generation" -gt 0 ] || fatal "final benchmark generation is invalid"
  ready_fps=$(line_field ready_interval_fps "$final_line")
  case "$ready_fps" in ''|*[!0-9.]*|.*|*.*.*) fatal "final ready FPS is malformed" ;; esac
  awk -v value="$ready_fps" 'BEGIN { exit(value > 0 ? 0 : 1) }' \
    || fatal "final ready FPS is not positive"
  require_line_field "$matrix_run_line" event matrix_run
  require_line_field "$matrix_run_line" build_profile benchmark
  require_line_field "$matrix_run_line" artifact_id "$artifact_id"
  require_line_field "$matrix_run_line" pair_id "$pair_id"
  require_line_field "$matrix_run_line" matrix_block "$matrix_block"
  require_line_field "$matrix_run_line" row_order "$row_order"
  require_line_field "$matrix_run_line" run_side "$run_side"
  require_line_field "$matrix_run_line" first_side "$first_side"
  matrix_session_generation=$(line_field session_generation "$matrix_run_line")
  case "$matrix_session_generation" in
    ''|*[!0-9]*) fatal "matrix_run session generation is malformed" ;;
  esac
  [ "$matrix_session_generation" -gt 0 ] || fatal "matrix_run session generation is invalid"
  if [ "$input_contract" != none ]; then
    [ "$matrix_session_generation" = "$scenario_generation" ] \
      || fatal "scenario_complete and matrix_run session generations differ"
  fi
  require_line_field "$matrix_run_line" benchmark_generation "$benchmark_generation"
  require_line_field "$matrix_run_line" execution_mode "$execution_mode"
  require_line_field "$matrix_run_line" requested_hardware "$profile"
  require_line_field "$matrix_run_line" warmup true
  require_line_field "$matrix_run_line" input_contract "$input_contract"
  require_line_field "$matrix_run_line" scenario_session_generation "$scenario_generation"
  require_line_field "$matrix_run_line" scenario_completed true
  require_line_field "$matrix_run_line" scenario_completed_frames "$scenario_frames"
  require_line_field "$matrix_run_line" scenario_expected_frames "$scenario_frames"
  require_line_field "$matrix_run_line" scenario_source_closed true
  require_line_field "$matrix_run_line" scenario_audio_drained true
  require_line_field "$matrix_run_line" audio_start_pending_bytes 0
  require_line_field "$matrix_run_line" audio_start_queued_bytes 0
  require_line_field "$matrix_run_line" audio_start_output_playing true
  require_line_field "$matrix_run_line" thermal_window m2
  require_line_field "$matrix_run_line" audio on
  require_line_field "$matrix_run_line" render presentation
  require_line_field "$matrix_run_line" availability available
  require_line_field "$matrix_run_line" surface_vote_hz "$rate"
  require_line_field "$matrix_run_line" display_target_hz "$rate"
  require_line_field "$matrix_run_line" surface_content_rate_millihz "$content_rate"
  require_line_field "$matrix_run_line" profile "$profile"
  require_line_field "$matrix_run_line" effective_gbc "$expected_gbc"
  require_line_field "$matrix_run_line" effective_dmg_compat "$expected_compat"
  require_line_field "$matrix_run_line" effective_mode "$row"
  matrix_nonce=$(line_field workload_nonce "$matrix_run_line")
  valid_opaque_token "$matrix_nonce" || fatal "matrix_run workload nonce is not opaque"
  matrix_device_id=$(line_field device_id "$matrix_run_line")
  case "$matrix_device_id" in ''|*[!0-9a-f]*) fatal "matrix_run device identity is malformed" ;; esac
  [ "${#matrix_device_id}" -eq 64 ] || fatal "matrix_run device identity is malformed"
  require_line_field "$final_line" event "$EVENT_FINAL"
  require_line_field "$final_line" build_profile benchmark
  require_line_field "$final_line" artifact_id "$artifact_id"
  require_line_field "$final_line" pair_id "$pair_id"
  require_line_field "$final_line" matrix_block "$matrix_block"
  require_line_field "$final_line" row_order "$row_order"
  require_line_field "$final_line" run_side "$run_side"
  require_line_field "$final_line" session_generation "$matrix_session_generation"
  require_line_field "$final_line" execution_mode "$execution_mode"
  require_line_field "$final_line" frame 600
  require_line_field "$final_line" ready_count 600
  require_line_field "$final_line" submitted_count 600
  require_line_field "$final_line" drain_success true
  require_line_field "$final_line" dropped_count 0
  require_line_field "$final_line" duplicate_count 0
  require_line_field "$final_line" late_count 0
  require_line_field "$final_line" corrupt_count 0
  require_line_field "$final_line" requested_profile "$profile"
  require_line_field "$final_line" profile "$profile"
  require_line_field "$final_line" effective_gbc "$expected_gbc"
  require_line_field "$final_line" effective_dmg_compat "$expected_compat"
  require_line_field "$final_line" effective_mode "$row"
  require_line_field "$final_line" surface_vote_hz "$rate"
  require_line_field "$final_line" display_target_hz "$rate"
  require_line_field "$final_line" surface_content_rate_millihz "$content_rate"
  require_line_field "$final_line" warmup true
  require_line_field "$final_line" input_contract "$input_contract"
  require_line_field "$final_line" scenario_session_generation "$scenario_generation"
  require_line_field "$final_line" scenario_completed true
  require_line_field "$final_line" scenario_completed_frames "$scenario_frames"
  require_line_field "$final_line" scenario_expected_frames "$scenario_frames"
  require_line_field "$final_line" scenario_source_closed true
  require_line_field "$final_line" scenario_audio_drained true
  # The ARM baseline is bound once in matrix_run. final_result intentionally omits its duplicated
  # 21-field copy to retain Android log-payload headroom.
  require_line_field "$final_line" audio_active true
  require_line_field "$final_line" audio_output_playing true
  require_line_field "$final_line" audio_muted false
  require_line_field "$final_line" audio_system_music_muted false
  require_line_field "$final_line" live_input_mutations 0
  require_line_field "$final_line" thermal_worst 0
  require_line_field "$final_line" display_bad_count 0
  require_line_field "$final_line" interactive_bad_count 0
  require_line_field "$final_line" plugged_bad_count 0
  require_line_field "$final_line" power_save_bad_count 0
  require_line_field "$final_line" stay_awake_bad_count 0
  workload_nonce=$(line_field workload_nonce "$final_line")
  valid_opaque_token "$workload_nonce" || fatal "final benchmark workload nonce is not opaque"
  [ "$workload_nonce" = "$matrix_nonce" ] || fatal "matrix_run and final workload selection differ"
  if [ "$slot" = "$color_slot" ]; then
    if [ -z "$color_emitted_nonce" ]; then
      color_emitted_nonce=$workload_nonce
    else
      [ "$color_emitted_nonce" = "$workload_nonce" ] || fatal "color workload selection changed"
    fi
  else
    if [ -z "$non_color_emitted_nonce" ]; then
      non_color_emitted_nonce=$workload_nonce
    else
      [ "$non_color_emitted_nonce" = "$workload_nonce" ] \
        || fatal "non-color workload selection changed"
    fi
  fi
  current_device_id=$(line_field device_id "$final_line")
  case "$current_device_id" in ''|*[!0-9a-f]*) fatal "final benchmark device identity is malformed" ;; esac
  [ "${#current_device_id}" -eq 64 ] || fatal "final benchmark device identity is malformed"
  [ "$current_device_id" = "$matrix_device_id" ] \
    || fatal "matrix_run and final device identities differ"
  if [ -z "$device_id" ]; then
    device_id=$current_device_id
  else
    [ "$device_id" = "$current_device_id" ] || fatal "device identity changed during matrix"
  fi
  check_host_environment
  verify_display_rate "$rate"
  bounded_sleep "$REFRESH_WAIT_SECONDS"
  if ! lifecycle_ok; then
    fatal "benchmark Activity lost visibility before compositor after-dump"
  fi
  if ! adb_shell_capture "$tmp_dir/after.txt" dumpsys SurfaceFlinger --timestats -dump; then
    fatal "SurfaceFlinger TimeStats after-dump failed"
  fi

  gate_output=$tmp_dir/gate
  if ! "$GATE_SCRIPT" \
      --uid "$uid" --layer "$layer" --display-refresh-hz "$rate" --ready-fps "$ready_fps" \
      --before "$tmp_dir/before.txt" --after "$tmp_dir/after.txt" \
      --artifact-id "$artifact_id" --device-id "$device_id" \
      --pair-id "$pair_id" --matrix-block "$matrix_block" \
      --row-order "$row_order" --run-side "$run_side" \
      --benchmark-generation "$benchmark_generation" >"$gate_output" 2>&1; then
    fatal "SurfaceFlinger TimeStats gate rejected the measured window"
  fi
  gate_line_count=$(awk '/^event=compositor_result[[:space:]]/ { count++ } END { print count + 0 }' "$gate_output")
  [ "$gate_line_count" -eq 1 ] || fatal "compositor gate output was ambiguous"
  gate_line=$(awk '/^event=compositor_result[[:space:]]/ { print; exit }' "$gate_output")
  check_redacted_lines "$gate_output"
  require_line_field "$gate_line" event compositor_result
  require_line_field "$gate_line" artifact_id "$artifact_id"
  require_line_field "$gate_line" device_id "$device_id"
  require_line_field "$gate_line" pair_id "$pair_id"
  require_line_field "$gate_line" matrix_block "$matrix_block"
  require_line_field "$gate_line" row_order "$row_order"
  require_line_field "$gate_line" run_side "$run_side"
  require_line_field "$gate_line" benchmark_generation "$benchmark_generation"
  require_line_field "$gate_line" display_refresh_hz "$rate"
  require_line_field "$gate_line" total_frames 600
  require_line_field "$gate_line" histogram_frames 599
  require_line_field "$gate_line" raw_total_frames 601
  require_line_field "$gate_line" raw_histogram_frames 601
  require_line_field "$gate_line" boundary_frames 1
  require_line_field "$gate_line" boundary_intervals 2
  require_line_field "$gate_line" present_interval_count 599
  require_line_field "$gate_line" cadence_good_frames 599
  require_line_field "$gate_line" cadence_bad_frames 0
  require_line_field "$gate_line" cadence_boundary_200_frames 1
  require_line_field "$gate_line" cadence_boundary_1000_frames 1
  require_line_field "$gate_line" dropped_frames 0
  require_line_field "$gate_line" late_acquire_frames 0
  require_line_field "$gate_line" bad_desired_present_frames 0
  require_line_field "$gate_line" measurement surfaceflinger_timestats

  # The first final_result capture precedes SurfaceFlinger collection. Re-read the app evidence
  # after that gate so a visibility/focus loss during the compositor tail cannot be hidden behind
  # the stale capture. An invalidation is terminal even if final_result was already emitted.
  capture_benchmark_log "$tmp_dir/logcat"
  check_redacted_lines "$tmp_dir/logcat"
  [ "$(event_count "$tmp_dir/logcat" "$EVENT_INVALIDATED")" -eq 0 ] \
    || fatal "benchmark session was invalidated before evidence collection completed"
  [ "$(event_count "$tmp_dir/logcat" "$EVENT_FINAL")" -eq 1 ] \
    || fatal "fresh final_result evidence was absent or ambiguous"
  fresh_final_line=$(event_line "$tmp_dir/logcat" "$EVENT_FINAL")
  require_line_field "$fresh_final_line" artifact_id "$artifact_id"
  require_line_field "$fresh_final_line" pair_id "$pair_id"
  require_line_field "$fresh_final_line" matrix_block "$matrix_block"
  require_line_field "$fresh_final_line" row_order "$row_order"
  require_line_field "$fresh_final_line" run_side "$run_side"
  require_line_field "$fresh_final_line" session_generation "$matrix_session_generation"
  require_line_field "$fresh_final_line" benchmark_generation "$benchmark_generation"
  [ "$(event_count "$tmp_dir/logcat" matrix_run)" -eq 1 ] \
    || fatal "fresh matrix_run evidence was absent or ambiguous"
  if [ "$input_contract" = none ]; then
    [ "$(event_count "$tmp_dir/logcat" "$EVENT_SCENARIO")" -eq 0 ] \
      || fatal "fresh no-input row emitted unexpected scenario evidence"
  else
    [ "$(event_count "$tmp_dir/logcat" "$EVENT_SCENARIO")" -eq 1 ] \
      || fatal "fresh scenario_complete evidence was absent or ambiguous"
  fi
  # Only event records and the numeric compositor record become durable evidence.
  awk 'index($0, "CoffeeGbBench") > 0 && index($0, "event=") > 0 { print }' \
    "$tmp_dir/logcat" >>"$aggregate"
  printf '%s\n' "$gate_line" >>"$aggregate"
  if [ -n "$output_dir" ]; then
    cp "$aggregate" "$aggregate_copy"
    chmod 600 "$aggregate_copy"
  fi
  bounded_sleep "$COOLDOWN_SECONDS"
  check_host_environment
}

block=
row_order=
row=
first_side=
while IFS=' ' read -r block row_order row first_side; do
  [ -n "$block" ] || continue
  if [ "$first_side" = parent ]; then
    second_side=candidate
    first_apk=$parent_apk
    first_hash=$parent_hash
    second_apk=$candidate_apk
    second_hash=$candidate_hash
  else
    second_side=parent
    first_apk=$candidate_apk
    first_hash=$candidate_hash
    second_apk=$parent_apk
    second_hash=$parent_hash
  fi
  run_one "$block" "$row_order" "$row" "$first_side" "$first_side" "$first_apk" "$first_hash"
  run_one "$block" "$row_order" "$row" "$first_side" "$second_side" "$second_apk" "$second_hash"
done <"$schedule_file"

matrix_report=$tmp_dir/matrix-report.txt
if ! "$MATRIX_SCRIPT" --parent-apk "$parent_hash" --candidate-apk "$candidate_hash" \
    "$aggregate" >"$matrix_report" 2>&1; then
  # The report contains only parser error keys/numeric evidence; retain it for an optional output
  # directory, but do not print command output or any path.
  if [ -n "$output_dir" ]; then
    cp "$matrix_report" "$report_copy"
    chmod 600 "$report_copy"
  fi
  cat "$matrix_report"
  fatal "benchmark matrix report was rejected"
fi
if [ -n "$output_dir" ]; then
  cp "$aggregate" "$aggregate_copy"
  chmod 600 "$aggregate_copy"
  cp "$matrix_report" "$report_copy"
  chmod 600 "$report_copy"
fi
cat "$matrix_report"
printf 'completed_runs=%s\n' "$run_number"
