#!/bin/sh
set -eu

# Host-only goal-matrix-v1 scheduler. Recent slots and workload nonces are app-owned; raw device
# dumps remain private and only redacted evidence records are durable.

umask 077
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PACKAGE=eu.rekawek.coffeegb.android
ACTIVITY=$PACKAGE/.MainActivity
DEVICE_SERIAL=4LJJSS7L8PWWMNWW
EXPECTED_MODEL=25078RA3EE
EXPECTED_DEVICE=dew
EXPECTED_API=35
MATRIX_VERSION=goal-matrix-v1
AUDIO_POLICY=silent-pcm-v1
EXECUTION_MODE=performance
RUN_COUNT=16
PAIR_COUNT=1
ORDER_MODE=rotated
BOOTSTRAP=fast-forward

ADB_BIN=${COFFEE_GB_GOAL_ADB:-adb}
APKSIGNER_BIN=${COFFEE_GB_GOAL_APKSIGNER:-apksigner}
AAPT_BIN=${COFFEE_GB_GOAL_AAPT:-aapt}
REPORT_SCRIPT=${COFFEE_GB_GOAL_REPORT_SCRIPT:-$SCRIPT_DIR/benchmark-goal-matrix-report.sh}
GATE_SCRIPT=${COFFEE_GB_GOAL_GATE_SCRIPT:-$SCRIPT_DIR/benchmark-goal-matrix-surface-gate.sh}
POLL_LIMIT=${COFFEE_GB_GOAL_POLL_LIMIT:-60}
POLL_SECONDS=${COFFEE_GB_GOAL_POLL_SECONDS:-1}
REFRESH_WAIT_SECONDS=${COFFEE_GB_GOAL_REFRESH_WAIT_SECONDS:-1}
ANCHOR_POLL_LIMIT=${COFFEE_GB_GOAL_ANCHOR_POLL_LIMIT:-$POLL_LIMIT}

usage() {
  echo "usage: benchmark-goal-matrix.sh --parent-apk <signed.apk> --candidate-apk <signed.apk>" >&2
  echo "       [--bootstrap skip|fast-forward|normal] [--pairs 1]"
  echo "       [--order fixed|rotated] [--output-dir <fresh-dir>]" >&2
}
fatal() { echo "benchmark-goal-matrix: $1" >&2; exit 1; }
require_arg() { [ "$#" -ge 2 ] && [ -n "$2" ] || { usage; exit 2; }; }

parent_apk=; candidate_apk=; output_dir=
bootstrap_seen=false; pairs_seen=false; order_seen=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --parent-apk) require_arg "$@"; [ -z "$parent_apk" ] || { usage; exit 2; }; parent_apk=$2; shift 2 ;;
    --candidate-apk) require_arg "$@"; [ -z "$candidate_apk" ] || { usage; exit 2; }; candidate_apk=$2; shift 2 ;;
    --bootstrap|--bootstrap-mode) require_arg "$@"; [ "$bootstrap_seen" = false ] || { usage; exit 2; }; BOOTSTRAP=$2; bootstrap_seen=true; shift 2 ;;
    --pairs) require_arg "$@"; [ "$pairs_seen" = false ] || { usage; exit 2; }; PAIR_COUNT=$2; pairs_seen=true; shift 2 ;;
    --order) require_arg "$@"; [ "$order_seen" = false ] || { usage; exit 2; }; ORDER_MODE=$2; order_seen=true; shift 2 ;;
    --output-dir|-o) require_arg "$@"; [ -z "$output_dir" ] || { usage; exit 2; }; output_dir=$2; shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done
[ -n "$parent_apk" ] && [ -n "$candidate_apk" ] || { usage; exit 2; }
case "$BOOTSTRAP" in skip|fast-forward|normal) : ;; *) fatal "invalid bootstrap mode" ;; esac
case "$PAIR_COUNT" in 1) : ;; *) fatal "goal-matrix-v1 requires one pair per cell" ;; esac
case "$ORDER_MODE" in fixed|rotated) : ;; *) fatal "invalid side order" ;; esac
case "$POLL_LIMIT" in ''|*[!0-9]*) fatal "poll limit is malformed" ;; esac
[ "$POLL_LIMIT" -gt 0 ] && [ "$POLL_LIMIT" -le 600 ] || fatal "poll limit is out of range"
case "$ANCHOR_POLL_LIMIT" in ''|*[!0-9]*) fatal "anchor poll limit is malformed" ;; esac
[ "$ANCHOR_POLL_LIMIT" -gt 0 ] && [ "$ANCHOR_POLL_LIMIT" -le 600 ] || fatal "anchor poll limit is out of range"
case "$POLL_SECONDS:$REFRESH_WAIT_SECONDS" in *[!0-9:]*|:*) fatal "wait value is malformed" ;; esac
[ "$POLL_SECONDS" -le 10 ] && [ "$REFRESH_WAIT_SECONDS" -le 10 ] || fatal "wait value is too large"
[ -f "$parent_apk" ] && [ -r "$parent_apk" ] || fatal "parent APK is unavailable"
[ -f "$candidate_apk" ] && [ -r "$candidate_apk" ] || fatal "candidate APK is unavailable"

if [ -n "$output_dir" ]; then
  if [ -e "$output_dir" ] && [ ! -d "$output_dir" ]; then fatal "output path is not a directory"; fi
  mkdir -p "$output_dir"
  [ ! -e "$output_dir/matrix.log" ] || fatal "output directory already contains matrix.log"
  [ ! -e "$output_dir/compositor.log" ] || fatal "output directory already contains compositor.log"
  [ ! -e "$output_dir/matrix-report.txt" ] || fatal "output directory already contains matrix-report.txt"
fi

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-goal-matrix.XXXXXX")
display_saved=false
cleanup() {
  restore_status=0
  if [ "$display_saved" = true ]; then
    restore_setting() {
      namespace=$1; name=$2; source=$3
      value=$(awk 'NF { print $1; exit }' "$source" 2>/dev/null || true)
      if [ -z "$value" ] || [ "$value" = null ]; then
        "$ADB_BIN" -s "$DEVICE_SERIAL" shell settings delete "$namespace" "$name" >/dev/null 2>&1 || restore_status=1
      else
        "$ADB_BIN" -s "$DEVICE_SERIAL" shell settings put "$namespace" "$name" "$value" >/dev/null 2>&1 || restore_status=1
      fi
    }
    restore_setting system peak_refresh_rate "$tmp_dir/original.system.peak_refresh_rate"
    restore_setting system min_refresh_rate "$tmp_dir/original.system.min_refresh_rate"
    restore_setting system user_refresh_rate "$tmp_dir/original.system.user_refresh_rate"
    restore_setting secure user_refresh_rate "$tmp_dir/original.secure.user_refresh_rate"
    restore_setting system is_smart_fps "$tmp_dir/original.system.is_smart_fps"
    preferred=$(awk 'tolower($0) ~ /user preferred display mode:/ { line=$0; sub(/^.*:[[:space:]]*/, "", line); if (split(line, f, /[[:space:]]+/) == 3) print f[1], f[2], f[3]; exit }' "$tmp_dir/original.preferred" 2>/dev/null || true)
    set -- $preferred
    if [ "$#" -eq 3 ] && awk -v w="$1" -v h="$2" -v r="$3" 'BEGIN { exit(w > 0 && h > 0 && r > 0 ? 0 : 1) }'; then
      "$ADB_BIN" -s "$DEVICE_SERIAL" shell cmd display set-user-preferred-display-mode "$1" "$2" "$3" 0 >/dev/null 2>&1 || restore_status=1
    else
      "$ADB_BIN" -s "$DEVICE_SERIAL" shell cmd display clear-user-preferred-display-mode 0 >/dev/null 2>&1 || restore_status=1
    fi
  fi
  rm -rf "$tmp_dir"
  [ "$restore_status" -eq 0 ] || { echo "benchmark-goal-matrix: display restoration failed" >&2; exit 1; }
}
trap cleanup EXIT
trap 'exit 129' HUP
trap 'exit 130' INT
trap 'exit 143' TERM

capture_global() { destination=$1; shift; "$ADB_BIN" "$@" >"$destination" 2>&1; }
capture_device() { destination=$1; shift; "$ADB_BIN" -s "$DEVICE_SERIAL" "$@" >"$destination" 2>&1; }
shell_capture() { destination=$1; shift; capture_device "$destination" shell "$@"; }
shell_checked() {
  command_count=${command_count-0}
  shell_capture "$tmp_dir/cmd.$$.${command_count}" "$@" || fatal "device command failed"
  command_count=$((command_count + 1))
}
bounded_sleep() { [ "${COFFEE_GB_GOAL_FAST:-0}" = 1 ] || sleep "$1"; }

verify_apk() {
  apk=$1; label=$2
  "$APKSIGNER_BIN" verify --verbose "$apk" >"$tmp_dir/$label.verify" 2>&1 || fatal "signed APK verification failed"
  awk 'BEGIN { bad=0 } tolower($0) ~ /does not verify|verification failed|error:/ { bad=1 } END { exit bad }' "$tmp_dir/$label.verify" || fatal "APK verification output was not clean"
  "$APKSIGNER_BIN" verify --print-certs "$apk" >"$tmp_dir/$label.certs" 2>&1 || fatal "APK certificate inspection failed"
  cert=$(awk '/certificate SHA-256 digest:/ { v=$0; sub(/^.*certificate SHA-256 digest:[[:space:]]*/, "", v); gsub(/[^0-9A-Fa-f]/, "", v); count++; last=tolower(v); if (length(last) != 64) bad=1 } END { if (count != 1 || bad) exit 1; print last }' "$tmp_dir/$label.certs" 2>/dev/null) || fatal "APK signer identity is malformed"
  case "$cert" in ''|*[!0-9a-f]*) fatal "APK signer identity is malformed" ;; esac
  [ "$(printf '%s' "$cert" | awk '{ print length }')" -eq 64 ] || fatal "APK signer identity is malformed"
  printf '%s\n' "$cert"
}
hash_apk() {
  value=$(sha256sum "$1" | awk '{ print $1 }') || fatal "APK identity could not be derived"
  case "$value" in ''|*[!0-9a-fA-F]*) fatal "APK identity is malformed" ;; esac
  [ "$(printf '%s' "$value" | awk '{ print length }')" -eq 64 ] || fatal "APK identity is malformed"
  printf '%s\n' "$value" | tr 'A-F' 'a-f'
}
apk_package() {
  apk=$1
  if command -v "$AAPT_BIN" >/dev/null 2>&1; then
    "$AAPT_BIN" dump badging "$apk" 2>/dev/null | awk -F"'" '/^package: name=/ { print $2; count++ } END { if (count != 1) exit 1 }'
    return $?
  fi
  if command -v apkanalyzer >/dev/null 2>&1; then apkanalyzer manifest application-id "$apk" 2>/dev/null; return $?; fi
  return 1
}
parent_cert=$(verify_apk "$parent_apk" parent)
candidate_cert=$(verify_apk "$candidate_apk" candidate)
[ "$parent_cert" = "$candidate_cert" ] || fatal "parent and candidate signer certificates differ"
parent_id=$(hash_apk "$parent_apk"); candidate_id=$(hash_apk "$candidate_apk")
[ "$parent_id" != "$candidate_id" ] || fatal "parent and candidate APKs are identical"
parent_package=$(apk_package "$parent_apk" 2>/dev/null || true)
candidate_package=$(apk_package "$candidate_apk" 2>/dev/null || true)
[ "$parent_package" = "$PACKAGE" ] && [ "$candidate_package" = "$PACKAGE" ] || fatal "APK package identity is invalid"

capture_global "$tmp_dir/devices" devices -l || fatal "device discovery failed"
device_count=$(awk '$1 !~ /^List$/ && ($2 == "device" || $2 == "offline" || $2 == "unauthorized") { count++ } END { print count+0 }' "$tmp_dir/devices")
[ "$device_count" -eq 1 ] || fatal "expected exactly one connected device"
device_name=$(awk '$1 !~ /^List$/ && $2 == "device" { print $1; exit }' "$tmp_dir/devices")
[ "$device_name" = "$DEVICE_SERIAL" ] || fatal "required Redmi device is not connected"

getprop_one() {
  property=$1; destination=$2
  shell_capture "$destination" getprop "$property" || fatal "device property query failed"
  [ "$(awk 'NF { count++ } END { print count+0 }' "$destination")" -eq 1 ] || fatal "device property response was ambiguous"
  awk 'NF { print; exit }' "$destination"
}
model=$(getprop_one ro.product.model "$tmp_dir/model")
device=$(getprop_one ro.product.device "$tmp_dir/device")
api=$(getprop_one ro.build.version.sdk "$tmp_dir/api")
[ "$model" = "$EXPECTED_MODEL" ] && [ "$device" = "$EXPECTED_DEVICE" ] && [ "$api" = "$EXPECTED_API" ] || fatal "device is not the configured Redmi target"

save_setting() { namespace=$1; name=$2; destination=$3; shell_capture "$destination" settings get "$namespace" "$name" || fatal "display setting query failed"; }
save_setting system peak_refresh_rate "$tmp_dir/original.system.peak_refresh_rate"
save_setting system min_refresh_rate "$tmp_dir/original.system.min_refresh_rate"
save_setting system user_refresh_rate "$tmp_dir/original.system.user_refresh_rate"
save_setting secure user_refresh_rate "$tmp_dir/original.secure.user_refresh_rate"
save_setting system is_smart_fps "$tmp_dir/original.system.is_smart_fps"
shell_capture "$tmp_dir/original.preferred" cmd display get-user-preferred-display-mode 0 || fatal "preferred display query failed"
display_saved=true

check_display() {
  wanted=$1
  shell_capture "$tmp_dir/current.display" cmd display get-displays || fatal "display query failed"
  # get-displays also lists every supported mode.  Only an explicitly active/current/
  # committed/render-frame-rate record is authoritative; supported 60/90/120 modes are
  # intentionally ignored.
  awk -v wanted="$wanted" '
    function line_rate(line, lower, text) {
      lower=tolower(line)
      if (lower ~ /renderframerate[[:space:]]*[=:]?[[:space:]]*[0-9]/) {
        text=lower; sub(/^.*renderframerate[[:space:]]*[=:]?[[:space:]]*/, "", text)
        if (match(text, /^[0-9]+([.][0-9]+)?/)) return int(substr(text,RSTART,RLENGTH)+0.5)
      }
      if (lower ~ /refresh[[:space:]_]*rate([^a-z]|$)/) {
        text=lower; sub(/^.*refresh[[:space:]_]*rate[^0-9]*/, "", text)
        if (match(text, /^[0-9]+([.][0-9]+)?/)) return int(substr(text,RSTART,RLENGTH)+0.5)
      }
      if (lower ~ /fps/) {
        text=lower
        if (match(text, /[0-9]+([.][0-9]+)?[[:space:]]*fps/)) {
          text=substr(text,RSTART,RLENGTH); sub(/[[:space:]]*fps.*$/, "", text)
          return int(text+0.5)
        }
      }
      return -1
    }
    {
      lower=tolower($0); value=line_rate($0)
      if (value >= 0 && lower ~ /(active|current|committed|displayinfo|displaymode|renderframerate|refresh[[:space:]_]*rate([^a-z]|$)|fps)/) {
        if (lower ~ /(active|current|committed)/) { active_count++; active_bad += value != wanted }
        else { all_count++; all_bad += value != wanted }
      }
    }
    END {
      if (active_count > 0) exit(active_bad == 0 ? 0 : 1)
      exit(all_count > 0 && all_bad == 0 ? 0 : 1)
    }
  ' "$tmp_dir/current.display" || fatal "active display did not reach requested rate"
}
pin_display() {
  wanted=$1
  shell_checked settings put system peak_refresh_rate "$wanted"
  shell_checked settings put system min_refresh_rate "$wanted"
  shell_checked settings put system user_refresh_rate "$wanted"
  shell_checked settings put secure user_refresh_rate "$wanted"
  shell_checked settings put system is_smart_fps 0
  shell_checked cmd display set-user-preferred-display-mode 720 1600 "$wanted" 0
  bounded_sleep "$REFRESH_WAIT_SECONDS"
  check_display "$wanted"
}
audio_proof() {
  phase=$1
  shell_capture "$tmp_dir/audio.$phase" dumpsys audio || fatal "audio proof query failed"
  awk '
    function is_stream_header(lower) {
      return lower ~ /^[[:space:]]*[-*]?[[:space:]]*stream_[a-z_]+[[:space:]]*:/
    }
    {
      lower=tolower($0)
      if (lower ~ /^[[:space:]]*[-*]?[[:space:]]*stream_music[[:space:]]*:/) {
        inside=1; seen++; stream_seen=1; next
      }
      if (inside && is_stream_header(lower)) inside=0
      if (!inside) next
      if (lower ~ /(^|[^a-z])muted[[:space:]]*:[[:space:]]*true([^a-z]|$)/) muted++
      if (lower ~ /(^|[^a-z])muted[[:space:]]*:[[:space:]]*false([^a-z]|$)/) bad=1
      if (lower ~ /streamvolume[[:space:]]*:[[:space:]]*[0-9]+/) {
        value=lower; sub(/^.*streamvolume[[:space:]]*:[[:space:]]*/, "", value)
        sub(/[^0-9].*$/, "", value); stream_volume_seen++
        if (value + 0 != 0) bad=1
      }
      if (lower ~ /(^|[^a-z])devices[[:space:]]*:/) {
        value=lower; sub(/^.*devices[[:space:]]*:[[:space:]]*/, "", value)
        while (match(value, /speaker[[:space:]]*\([0-9]+\)/)) {
          token=substr(value,RSTART,RLENGTH); id=token
          sub(/^.*\(/, "", id); sub(/\).*/, "", id)
          active_speaker=id+0; active_device_seen=1
          value=substr(value,RSTART+RLENGTH)
        }
      }
      if (lower ~ /(^|[^a-z])current[[:space:]]*:/) {
        value=lower; sub(/^.*current[[:space:]]*:[[:space:]]*/, "", value)
        while (match(value, /[0-9]+[[:space:]]*\([^)]*\)[[:space:]]*:[[:space:]]*[0-9]+/)) {
          token=substr(value,RSTART,RLENGTH); id=token; level=token
          sub(/[[:space:]]*\(.*/, "", id)
          sub(/^.*:[[:space:]]*/, "", level)
          current_seen[id+0]++
          current_level[id+0]=level+0
          value=substr(value,RSTART+RLENGTH)
        }
      }
    }
    END {
      current_speaker_seen=active_device_seen ? current_seen[active_speaker] : 0
      if (current_speaker_seen == 1 && current_level[active_speaker] != 0) bad=1
      exit((seen == 1 && stream_seen && muted > 0 && stream_volume_seen == 1 && active_device_seen && current_speaker_seen == 1 && !bad) ? 0 : 1)
    }
  ' "$tmp_dir/audio.$phase" || fatal "STREAM_MUSIC is not muted at volume zero"
}
clear_log() { capture_device "$tmp_dir/log.clear" logcat -c -s CoffeeGbBench:I '*:S' || fatal "benchmark log clear failed"; }
capture_log() { destination=$1; capture_device "$destination" logcat -d -v threadtime -s CoffeeGbBench:I '*:S' || fatal "benchmark log capture failed"; }
check_log_safety() {
  input=$1
  awk '
    {
      raw=tolower($0); scan=$0
      tag=index(scan, "CoffeeGbBench:")
      if (tag > 0) scan=substr(scan, tag + length("CoffeeGbBench:"))
      lower=tolower(scan)
      if (index(raw, "coffeegbbench") > 0 && index(raw, "event=") == 0) bad=1
      if (raw ~ /(^|[[:space:]])(rom|rom_path|rom_title|rom_name|rom_header|rom_checksum|save|save_path|uri|title|header|checksum|frame_hash|pixel_hash|payload|content|path|file|filename)=/) bad=1
      if (lower ~ /[\\\/]|:\/\//) bad=1
      if (raw ~ /\.(gb|gbc|sgb|sav|rom|cgbstate)([^a-z]|$)/) bad=1
    }
    END { exit bad ? 1 : 0 }
  ' "$input" || fatal "CoffeeGbBench emitted workload-bearing evidence"
}
wait_for_anchor() {
  poll=0
  while [ "$poll" -lt "$ANCHOR_POLL_LIMIT" ]; do
    poll=$((poll + 1))
    capture_log "$tmp_dir/anchor.raw"
    check_log_safety "$tmp_dir/anchor.raw"
    anchors=$(event_count "$tmp_dir/anchor.raw" benchmark_anchor)
    [ "$anchors" -le 1 ] || fatal "multiple benchmark_anchor events were observed"
    if [ "$anchors" -eq 1 ]; then
      anchor=$(event_line "$tmp_dir/anchor.raw" benchmark_anchor)
      [ "$(line_field "$anchor" success)" = true ] || fatal "benchmark anchor was unsuccessful"
      [ "$(line_field "$anchor" phase)" = anchor_ready ] || fatal "benchmark anchor phase is invalid"
      return 0
    fi
    bounded_sleep "$POLL_SECONDS"
  done
  fatal "timed out waiting for benchmark anchor"
}
resolve_layer() {
  shell_capture "$tmp_dir/layers" dumpsys SurfaceFlinger --list || fatal "SurfaceFlinger layer listing failed"
  sed -n 's/.*\(SurfaceView\[[^]]*\](BLAST)#[0-9][0-9]*\).*/\1/p' "$tmp_dir/layers" \
    | awk -v package_name="$PACKAGE" 'index($0, package_name) > 0 { print }' >"$tmp_dir/layer-candidates"
  layer_count=$(awk 'NF { count++ } END { print count+0 }' "$tmp_dir/layer-candidates")
  [ "$layer_count" -eq 1 ] || fatal "SurfaceFlinger did not expose exactly one active BLAST layer"
  layer=$(awk 'NF { print; exit }' "$tmp_dir/layer-candidates")
  case "$layer" in *"="*|*"|"*|*";"*|*'\`'*|*'$'*|*'\\'*) fatal "SurfaceFlinger layer name is ambiguous" ;; esac
}
redact_log() {
  input=$1; output=$2
  awk '
    {
      raw=tolower($0)
      if (index(raw, "coffeegbbench") == 0) next
      scan=$0
      tag=index(scan, "CoffeeGbBench:")
      if (tag > 0) scan=substr(scan, tag + length("CoffeeGbBench:"))
      lower=tolower(scan)
      if (raw ~ /(^|[[:space:]])(rom|rom_path|rom_title|rom_name|rom_header|rom_checksum|save|save_path|uri|title|header|checksum|frame_hash|pixel_hash|payload|content|path|file|filename)=/) bad=1
      if (lower ~ /[\\\/]|:\/\//) bad=1
      start=index($0, "event=")
      if (start == 0) { bad=1; next }
      record=substr($0, start)
      if (record !~ /^event=(boot_result|matrix_run|core_result|final_result)([[:space:]]|$)/) next
      if (record ~ /[\\\/]|:\/\//) bad=1
      print record
    }
    END { exit bad ? 1 : 0 }
  ' "$input" >"$output" || fatal "benchmark log failed redaction checks"
}
event_count() { file=$1; event=$2; awk -v wanted="event=$event" '{ for (i=1;i<=NF;i++) if ($i == wanted) { count++; break } } END { print count+0 }' "$file"; }
event_line() { file=$1; event=$2; awk -v wanted="event=$event" '{ for (i=1;i<=NF;i++) if ($i == wanted) { print; exit } }' "$file"; }
line_field() { record=$1; field=$2; printf '%s\n' "$record" | awk -v wanted="$field" '{ for (i=1;i<=NF;i++) if (index($i,wanted "=")==1) { v=$i; sub("^" wanted "=", "", v); print v; exit } }'; }
require_field() { [ "$(line_field "$1" "$2")" = "$3" ] || fatal "benchmark evidence identity is inconsistent"; }
require_positive() {
  v=$(line_field "$1" "$2")
  case "$v" in ''|*[!0-9.]*|.*|*.*.*) fatal "benchmark evidence counter is malformed" ;; esac
  awk -v value="$v" 'BEGIN { exit(value > 0 && value == value ? 0 : 1) }' || fatal "benchmark evidence counter is not positive"
}

validate_audio() {
  record=$1
  require_field "$record" benchmark_audio_policy "$AUDIO_POLICY"
  flags=$(line_field "$record" benchmark_audio_flags)
  case "$flags" in
    111) : ;;
    '') require_field "$record" benchmark_audio_requested true; require_field "$record" benchmark_audio_active_at_boundary true; require_field "$record" benchmark_audio_disabled_after true ;;
    *) fatal "silent PCM flags are invalid" ;;
  esac
  calendar=$(line_field "$record" benchmark_audio_calendar)
  [ -n "$calendar" ] || fatal "silent PCM calendar is missing"
  old_ifs=$IFS; IFS=,; set -- $calendar; IFS=$old_ifs
  [ "$#" -eq 8 ] || fatal "silent PCM calendar is malformed"
  n=1
  while [ "$n" -le 8 ]; do
    eval "v=\${$n}"
    case "$v" in ''|*[!0-9]*) fatal "silent PCM calendar is malformed" ;; esac
    n=$((n + 1))
  done
  [ "$(line_field "$record" audio_active)" = true ] || fatal "audio output was not active"
  [ "$(line_field "$record" audio_output_playing)" = true ] || fatal "audio output was not playing"
  [ "$(line_field "$record" audio_muted)" = false ] || fatal "application audio was muted"
  [ "$(line_field "$record" audio_system_volume)" = 0 ] || fatal "system music volume evidence is non-zero"
  [ "$(line_field "$record" audio_system_music_muted)" = true ] || fatal "system mute evidence is absent"
  [ "$(line_field "$record" audio_route_failures)" = 0 ] || fatal "audio route failure was reported"
  [ "$(line_field "$record" audio_track_underruns)" = 0 ] || fatal "audio underrun was reported"
  [ "$(line_field "$record" system_audio_bad_count)" = 0 ] || fatal "system audio sample failed"
  require_positive "$record" system_audio_sample_count
  require_positive "$record" audio_output_identity
  require_positive "$record" audio_queue_identity
}

validate_run() {
  expected_cell=$1; expected_slot=$2; expected_hardware=$3; expected_profile=$4
  expected_scenario=$5; expected_count=$6; expected_order=$7; expected_pair=$8
  expected_side=$9; input_file=${10}; expected_artifact=${11}
  [ "$(event_count "$input_file" boot_result)" -eq 1 ] && [ "$(event_count "$input_file" matrix_run)" -eq 1 ] && [ "$(event_count "$input_file" core_result)" -eq 1 ] && [ "$(event_count "$input_file" final_result)" -eq 1 ] || fatal "launch emitted incomplete or duplicate evidence"
  boot=$(event_line "$input_file" boot_result); matrix=$(event_line "$input_file" matrix_run); core=$(event_line "$input_file" core_result); final=$(event_line "$input_file" final_result)
  boot_no=$(grep -n 'event=boot_result' "$input_file" | head -1 | cut -d: -f1)
  matrix_no=$(grep -n 'event=matrix_run' "$input_file" | head -1 | cut -d: -f1)
  core_no=$(grep -n 'event=core_result' "$input_file" | head -1 | cut -d: -f1)
  final_no=$(grep -n 'event=final_result' "$input_file" | head -1 | cut -d: -f1)
  [ "$boot_no" -lt "$matrix_no" ] && [ "$matrix_no" -lt "$core_no" ] && [ "$core_no" -lt "$final_no" ] || fatal "goal evidence order is invalid"
  for record in "$boot" "$matrix" "$core" "$final"; do
    require_field "$record" matrix_version "$MATRIX_VERSION"; require_field "$record" cell_id "$expected_cell"
    require_field "$record" workload_slot "$expected_slot"; require_field "$record" scenario_id "$expected_scenario"
    require_field "$record" scenario_count "$expected_count"; require_field "$record" expected_profile "$expected_profile"
    require_field "$record" effective_profile "$expected_profile"; require_field "$record" requested_hardware "$expected_hardware"
    require_field "$record" execution_mode "$EXECUTION_MODE"; require_field "$record" pair_id "$expected_pair"
    require_field "$record" matrix_block "$MATRIX_BLOCK"; require_field "$record" row_order "$expected_order"
    require_field "$record" run_side "$expected_side"
  done
  nonce=$(line_field "$matrix" workload_nonce)
  case "$nonce" in ''|unknown|invalid|*[!a-z0-9._-]*) fatal "workload nonce is malformed" ;; esac
  [ "$(printf '%s' "$nonce" | awk '{ print length }')" -ge 16 ] && [ "$(printf '%s' "$nonce" | awk '{ print length }')" -le 64 ] || fatal "workload nonce is malformed"
  [ "$(line_field "$boot" workload_nonce)" = "$nonce" ] && [ "$(line_field "$core" workload_nonce)" = "$nonce" ] && [ "$(line_field "$final" workload_nonce)" = "$nonce" ] || fatal "workload nonce changed"
  boot_session=$(line_field "$boot" session_generation)
  case "$boot_session" in ''|*[!0-9]*) fatal "boot session generation is malformed" ;; esac
  [ "$boot_session" -gt 0 ] || fatal "boot session generation is invalid"
  require_field "$final" session_generation "$boot_session"
  case "$BOOTSTRAP" in
    skip) require_field "$boot" requested_bootstrap skip; require_field "$boot" bootstrap_outcome skipped ;;
    fast-forward|normal) require_field "$boot" requested_bootstrap "$BOOTSTRAP"; require_field "$boot" bootstrap_outcome authentic_handoff ;;
  esac
  require_field "$core" frame 600; require_field "$final" frame 600
  core_id=$(line_field "$core" core_result_id); [ -n "$core_id" ] || fatal "core result identity is missing"
  require_field "$final" core_result_id "$core_id"
  require_field "$final" artifact_id "$expected_artifact"
  final_device=$(line_field "$final" device_id)
  case "$final_device" in ''|*[!0-9a-f]*) fatal "device evidence identity is malformed" ;; esac
  [ "${#final_device}" -eq 64 ] || fatal "device evidence identity is malformed"
  if [ -z "${campaign_device_id:-}" ]; then
    campaign_device_id=$final_device
  elif [ "$campaign_device_id" != "$final_device" ]; then
    fatal "device identity changed within campaign"
  fi
  require_positive "$final" benchmark_generation; require_positive "$final" fps
  require_positive "$final" ready_interval_fps; require_positive "$final" submission_interval_fps
  if [ "$expected_side" = candidate ]; then
    target_fps=59.130225; [ "$expected_profile" = sgb ] && target_fps=60.556221
    # fps is a compact three-decimal display field; the exact interval fields carry the
    # performance claim and are compared directly to the cell floor.
    awk -v ready="$(line_field "$final" ready_interval_fps)" \
        -v submit="$(line_field "$final" submission_interval_fps)" -v target="$target_fps" \
      'BEGIN { exit(ready >= target && submit >= target ? 0 : 1) }' \
      || fatal "candidate performance is below the absolute ready/submission floor"
  fi
  validate_audio "$final"
  cat "$input_file" >>"$aggregate"
}

MATRIX_BLOCK=goal-block-$(od -An -N12 -tx1 /dev/urandom 2>/dev/null | tr -d '[:space:]')
case "$MATRIX_BLOCK" in goal-block-[0-9a-f][0-9a-f]*) : ;; *) fatal "could not create a fresh campaign block" ;; esac
[ "${#MATRIX_BLOCK}" -ge 16 ] && [ "${#MATRIX_BLOCK}" -le 64 ] || fatal "campaign block identity is malformed"
aggregate=$tmp_dir/matrix.log
: >"$aggregate"
campaign_device_id=
compositor_aggregate=$tmp_dir/compositor.log
: >"$compositor_aggregate"
run_index=0
cells='d-dmg|d|dmg|dmg|d-v1|313
d-cgb-compat|d|cgb|cgb-compat|d-v1|313
d-sgb|d|sgb|sgb|d-v1|313
u-dmg|u|dmg|dmg|u-v1|1297
u-cgb-native|u|cgb|cgb-native|u-v1|1297
u-sgb|u|sgb|sgb|u-v1|1297
c1-cgb-native|c1|cgb|cgb-native|c1-v1|1582
c2-cgb-native|c2|cgb|cgb-native|c2-v1|1084'
printf '%s\n' "$cells" >"$tmp_dir/cells"

run_one() {
  cell=$1; slot=$2; hardware=$3; profile=$4; scenario=$5; scenario_count=$6
  row_order=$7; side=$8; apk=$9; artifact=${10}; first_side=${11}; rate=${12}
  pair_id=goal-pair-$MATRIX_BLOCK-$cell; run_index=$((run_index + 1)); run_dir=$tmp_dir/run.$run_index; mkdir -p "$run_dir"
  shell_checked am force-stop "$PACKAGE"
  capture_device "$run_dir/install" install -r -d --no-streaming "$apk" || fatal "APK installation failed"
  awk 'tolower($0) ~ /(^|[^a-z])success([^a-z]|$)/ { ok=1 } END { exit(ok ? 0 : 1) }' "$run_dir/install" || fatal "APK installation was not confirmed"
  resolve_uid; pin_display "$rate"; audio_proof before; clear_log
  shell_checked dumpsys SurfaceFlinger --timestats -enable
  shell_checked dumpsys SurfaceFlinger --timestats -clear
  capture_device "$run_dir/launch" shell am start -W -n "$ACTIVITY" \
    --ez coffee_gb_benchmark true --es coffee_gb_matrix_version "$MATRIX_VERSION" \
    --es coffee_gb_cell_id "$cell" --es coffee_gb_workload_slot "$slot" --ei coffee_gb_recent_slot "$slot_index" \
    --es coffee_gb_hardware "$hardware" --ez coffee_gb_audio true --es coffee_gb_audio_policy "$AUDIO_POLICY" \
    --es coffee_gb_render presentation --ez coffee_gb_warmup true --ez coffee_gb_recent true \
    --es coffee_gb_build_id "$artifact" --es coffee_gb_pair_id "$pair_id" --es coffee_gb_matrix_block "$MATRIX_BLOCK" \
    --ei coffee_gb_row_order "$row_order" --es coffee_gb_run_side "$side" --es coffee_gb_first_side "$first_side" \
    --es coffee_gb_bootstrap "$BOOTSTRAP" --es coffee_gb_execution_mode "$EXECUTION_MODE" \
    --es coffee_gb_benchmark_scenario "$scenario" --ei coffee_gb_surface_rate_hz "$rate" \
    || fatal "benchmark Activity launch failed"
  awk 'tolower($0) ~ /status:[[:space:]]*ok/ { ok=1 } END { exit(ok ? 0 : 1) }' "$run_dir/launch" || fatal "benchmark launch was not confirmed"
  wait_for_anchor; audio_proof around; resolve_layer
  shell_capture "$run_dir/before.txt" dumpsys SurfaceFlinger --timestats -dump || fatal "baseline dump failed"
  arm_token=goal-arm-$run_index-$(date +%s)
  capture_device "$run_dir/arm" shell am start -W -n "$ACTIVITY" -f 0x20000000 --es coffee_gb_benchmark_arm_token "$arm_token" || fatal "benchmark arm failed"
  awk 'tolower($0) ~ /status:[[:space:]]*ok/ { ok=1 } END { exit(ok ? 0 : 1) }' "$run_dir/arm" || fatal "benchmark arm was not confirmed"
  poll=0
  rm -f "$tmp_dir/run.records"
  while [ "$poll" -lt "$POLL_LIMIT" ]; do
    poll=$((poll + 1)); capture_log "$tmp_dir/final.raw"
    if grep -q 'event=final_result' "$tmp_dir/final.raw"; then redact_log "$tmp_dir/final.raw" "$tmp_dir/run.records"; break; fi
    bounded_sleep "$POLL_SECONDS"
  done
  [ -f "$tmp_dir/run.records" ] && [ "$(event_count "$tmp_dir/run.records" final_result)" -gt 0 ] || fatal "timed out waiting for final result"
  audio_proof after
  final=$(event_line "$tmp_dir/run.records" final_result)
  shell_capture "$run_dir/after.txt" dumpsys SurfaceFlinger --timestats -dump || fatal "after dump failed"
  benchmark_generation=$(line_field "$final" benchmark_generation); device_id=$(line_field "$final" device_id)
  case "$device_id" in ''|*[!0-9a-f]*) fatal "device evidence identity is malformed" ;; esac
  [ "$(printf '%s' "$device_id" | awk '{ print length }')" -eq 64 ] || fatal "device evidence identity is malformed"
  ready_fps=59.7275; [ "$profile" = sgb ] && ready_fps=61.1679
  gate_output=$tmp_dir/gate.$run_index
  require_target=false; [ "$side" = candidate ] && require_target=true
  "$GATE_SCRIPT" --uid "$uid" --layer "$layer" --display-refresh-hz "$rate" --before "$run_dir/before.txt" --after "$run_dir/after.txt" \
    --artifact-id "$artifact" --device-id "$device_id" --pair-id "$pair_id" --matrix-block "$MATRIX_BLOCK" \
    --row-order "$row_order" --run-side "$side" --benchmark-generation "$benchmark_generation" --ready-fps "$ready_fps" --require-target "$require_target" \
    >"$gate_output" 2>"$tmp_dir/gate.err" || fatal "compositor gate rejected the run"
  gate_line=$(awk '/^event=compositor_result([[:space:]]|$)/ { print; count++ } END { if (count != 1) exit 1 }' "$gate_output") || fatal "compositor gate output is ambiguous"
  require_field "$gate_line" artifact_id "$artifact"; require_field "$gate_line" device_id "$device_id"
  require_field "$gate_line" pair_id "$pair_id"; require_field "$gate_line" matrix_block "$MATRIX_BLOCK"
  require_field "$gate_line" row_order "$row_order"; require_field "$gate_line" run_side "$side"
  require_positive "$gate_line" benchmark_generation; require_field "$gate_line" total_frames 600
  require_field "$gate_line" dropped_frames 0; require_field "$gate_line" late_acquire_frames 0; require_field "$gate_line" bad_desired_present_frames 0
  cat "$gate_output" >>"$compositor_aggregate"
  # SurfaceFlinger and audio are queried after final_result. Re-capture the private tag after the
  # compositor gate so a delayed invalidation/focus/route transition cannot be hidden by the first
  # successful final capture.
  capture_log "$run_dir/post.raw"
  check_log_safety "$run_dir/post.raw"
  if grep -Eiq 'benchmark_invalidated|audio_focus_(lost|changed)|audio_route_changed|audio_route_failure([[:space:]=]|$)|system_audio_bad_count=[1-9]|audio_route_failures=[1-9]' "$run_dir/post.raw"; then
    fatal "post-final benchmark evidence was invalidated or audio state changed"
  fi
  redact_log "$run_dir/post.raw" "$run_dir/post.records"
  [ "$(event_count "$run_dir/post.records" final_result)" -eq 1 ] || fatal "post-final log did not retain exactly one final_result"
  [ "$(event_line "$run_dir/post.records" final_result)" = "$final" ] || fatal "final_result changed after compositor measurement"
  validate_run "$cell" "$slot" "$hardware" "$profile" "$scenario" "$scenario_count" "$row_order" "$pair_id" "$side" "$tmp_dir/run.records" "$artifact"
}

resolve_uid() {
  shell_capture "$tmp_dir/package.uid" cmd package list packages -U "$PACKAGE" || fatal "package UID query failed"
  uid=$(awk -v wanted="package:$PACKAGE" '$1 == wanted { for (i=2;i<=NF;i++) if ($i ~ /^uid:[0-9]+$/) { count++; value=$i; sub(/^uid:/,"",value) } } END { if (count != 1) exit 1; print value }' "$tmp_dir/package.uid" 2>/dev/null) || fatal "package UID response was ambiguous"
  case "$uid" in ''|*[!0-9]*) fatal "package UID is malformed" ;; esac
  [ "$uid" -gt 0 ] || fatal "package UID is invalid"
}

# Initial proof is read-only.  No audio service or volume command appears in this workflow.
audio_proof initial
row_order=0
while IFS='|' read -r cell slot hardware profile scenario scenario_count; do
  case "$slot" in d) slot_index=0 ;; u) slot_index=1 ;; c1) slot_index=2 ;; c2) slot_index=3 ;; *) fatal "invalid workload slot" ;; esac
  rate=60; [ "$profile" = sgb ] && rate=120
  if [ "$ORDER_MODE" = fixed ] || [ $((row_order % 2)) -eq 0 ]; then
    run_one "$cell" "$slot" "$hardware" "$profile" "$scenario" "$scenario_count" "$row_order" parent "$parent_apk" "$parent_id" parent "$rate"
    run_one "$cell" "$slot" "$hardware" "$profile" "$scenario" "$scenario_count" "$row_order" candidate "$candidate_apk" "$candidate_id" parent "$rate"
  else
    run_one "$cell" "$slot" "$hardware" "$profile" "$scenario" "$scenario_count" "$row_order" candidate "$candidate_apk" "$candidate_id" candidate "$rate"
    run_one "$cell" "$slot" "$hardware" "$profile" "$scenario" "$scenario_count" "$row_order" parent "$parent_apk" "$parent_id" candidate "$rate"
  fi
  row_order=$((row_order + 1))
done <"$tmp_dir/cells"
[ "$run_index" -eq "$RUN_COUNT" ] || fatal "goal matrix did not execute exactly 16 launches"

cp "$aggregate" "$tmp_dir/goal-only.log"
report=$tmp_dir/report.txt
if ! "$REPORT_SCRIPT" --input "$tmp_dir/goal-only.log" --parent-id "$parent_id" --candidate-id "$candidate_id" >"$report" 2>"$tmp_dir/report.err"; then
  cat "$report" >&2; cat "$tmp_dir/report.err" >&2; fatal "goal matrix parser rejected campaign"
fi
grep -q '^accepted=true$' "$report" || fatal "goal matrix parser did not accept campaign"
if [ -n "$output_dir" ]; then
  cp "$aggregate" "$output_dir/matrix.log"; cp "$compositor_aggregate" "$output_dir/compositor.log"; cp "$report" "$output_dir/matrix-report.txt"
  chmod 600 "$output_dir/matrix.log" "$output_dir/compositor.log" "$output_dir/matrix-report.txt"
fi
cat "$report"
