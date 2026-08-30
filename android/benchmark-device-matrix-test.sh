#!/bin/sh
set -eu

# Hermetic contract test.  The fake adb below never connects to Android; it exposes only bounded
# device state and redacted CoffeeGbBench records.  The fixture proves the positive schedule and
# the fail-closed device/signature/layer/timeout/privacy paths without Gradle, Maven, or real ADB.

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-m2-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

parent=$tmp/parent.apk
candidate=$tmp/candidate.apk
printf 'parent-signed-by-test\n' >"$parent"
printf 'candidate-signed-by-test\n' >"$candidate"

parent_hash=1111111111111111111111111111111111111111111111111111111111111111
candidate_hash=2222222222222222222222222222222222222222222222222222222222222222

cat >"$tmp/apksigner" <<'STUB'
#!/bin/sh
if [ "${FAKE_APKSIGNER_MODE:-ok}" = unsigned ]; then
  echo 'DOES NOT VERIFY' >&2
  exit 1
fi
case "$*" in
  *--print-certs*) echo 'Signer #1 certificate SHA-256 digest: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa' ;;
  *) echo 'Verifies' ;;
esac
STUB
chmod 700 "$tmp/apksigner"

cat >"$tmp/sha256sum" <<'STUB'
#!/bin/sh
case "$1" in
  *parent.apk) printf '1111111111111111111111111111111111111111111111111111111111111111  %s\n' "$1" ;;
  *candidate.apk) printf '2222222222222222222222222222222222222222222222222222222222222222  %s\n' "$1" ;;
  *) exit 1 ;;
esac
STUB
chmod 700 "$tmp/sha256sum"

calls=$tmp/adb.calls
records=$tmp/records
fake_log=$tmp/fake.log
rate_file=$tmp/rate
gen_file=$tmp/generation
: >"$calls"
: >"$records"
: >"$fake_log"
printf '60\n' >"$rate_file"
printf '0\n' >"$gen_file"

cat >"$tmp/adb" <<'STUB'
#!/bin/sh
set -eu

calls=${FAKE_CALLS:?}
records=${FAKE_RECORDS:?}
log=${FAKE_LOG:?}
rate_file=${FAKE_RATE:?}
gen_file=${FAKE_GEN:?}
mode=${FAKE_MODE:-ok}
context=$records.context
printf '%s\n' "adb $*" >>"$calls"

if [ "${1:-}" = devices ]; then
  if [ "$mode" = wrong_device ]; then
    printf 'List of devices attached\nwrong-serial device model:other device:other\n'
  else
    printf 'List of devices attached\n4LJJSS7L8PWWMNWW device product:aurora model:25078RA3EE device:dew transport_id:1\n'
  fi
  exit 0
fi

[ "${1:-}" = -s ] || exit 1
shift 2
command=${1:-}
if [ "$command" = get-state ]; then echo device; exit 0; fi
if [ "$command" = install ]; then echo Success; exit 0; fi
if [ "$command" = logcat ]; then
  if [ "${2:-}" = -c ]; then
    : >"$log"
    rm -f "$records.postfinal-seen" "$records.postfinal-emitted"
  else
    if [ "$mode" = post_final_invalidation ] \
        && grep -q 'event=final_result ' "$log"; then
      if [ -f "$records.postfinal-seen" ] && [ ! -f "$records.postfinal-emitted" ]; then
        artifact=$(awk -F= '$1 == "artifact" { print $2 }' "$context")
        pair=$(awk -F= '$1 == "pair" { print $2 }' "$context")
        block=$(awk -F= '$1 == "block" { print $2 }' "$context")
        order=$(awk -F= '$1 == "order" { print $2 }' "$context")
        side=$(awk -F= '$1 == "side" { print $2 }' "$context")
        session_generation=$(awk -F= '$1 == "session_generation" { print $2 }' "$context")
        printf 'I/CoffeeGbBench: event=benchmark_invalidated artifact_id=%s pair_id=%s matrix_block=%s row_order=%s run_side=%s session_generation=%s phase=done reason=visibility_lost\n' \
          "$artifact" "$pair" "$block" "$order" "$side" "$session_generation" >>"$log"
        : >"$records.postfinal-emitted"
      else
        : >"$records.postfinal-seen"
      fi
    fi
    cat "$log"
  fi
  exit 0
fi
[ "$command" = shell ] || exit 1
shift
sub=${1:-}

if [ "$sub" = getprop ]; then
  case "${2:-}" in
    ro.product.model) echo 25078RA3EE ;;
    ro.product.device) echo dew ;;
    ro.build.version.sdk) echo 35 ;;
    *) exit 1 ;;
  esac
  exit 0
fi

if [ "$sub" = settings ]; then
  case "${2:-}:${3:-}:${4:-}" in
    get:global:stay_on_while_plugged_in) echo 15 ;;
    get:global:low_power) echo 0 ;;
    get:system:*) echo 60 ;;
    put:system:peak_refresh_rate|put:system:min_refresh_rate|put:system:user_refresh_rate)
      printf '%s\n' "${5:-60}" >"$rate_file" ;;
    *) : ;;
  esac
  exit 0
fi

if [ "$sub" = cmd ]; then
  case "${2:-}:${3:-}" in
    package:list) echo 'package:eu.rekawek.coffeegb.android uid:10327' ;;
    display:get-user-preferred-display-mode) echo 'User preferred display mode: -1 -1 0.0' ;;
    display:set-user-preferred-display-mode) printf '%s\n' "${6:-60}" >"$rate_file" ;;
    display:clear-user-preferred-display-mode) : ;;
    display:get-displays) echo "Display id 0: DisplayInfo{mode 3, renderFrameRate $(cat "$rate_file").0, supportedModes [{id=3, fps=$(cat "$rate_file").0}], state ON, committedState ON}" ;;
    *) : ;;
  esac
  exit 0
fi

if [ "$sub" = dumpsys ]; then
  case "${2:-}" in
    power) echo 'mWakefulness=Awake mHalInteractiveModeEnabled=true' ;;
    battery) echo 'AC powered: true'; echo 'USB powered: false'; echo 'Wireless powered: false'; echo 'Dock powered: false' ;;
    thermalservice) echo 'ThermalStatusListeners:'; echo 'Thermal Status: 0' ;;
    activity) echo 'mResumedActivity: ActivityRecord{eu.rekawek.coffeegb.android/.MainActivity}' ;;
    package) echo 'ApplicationInfo{eu.rekawek.coffeegb.android flags=[ HAS_CODE ALLOW_CLEAR_USER_DATA ] privateFlags=[ DIRECT_BOOT_AWARE ]}' ;;
    SurfaceFlinger)
      if [ "${3:-}" = --list ]; then
        echo 'RequestedLayerState{SurfaceView[eu.rekawek.coffeegb.android/eu.rekawek.coffeegb.android.MainActivity](BLAST)#2374 visible=true}'
        if [ "$mode" = layer ]; then
          echo 'RequestedLayerState{SurfaceView[eu.rekawek.coffeegb.android/eu.rekawek.coffeegb.android.MainActivity](BLAST)#2375 visible=true}'
        fi
      else
        display_rate=$(cat "$rate_file")
        echo 'uid = 10327'
        echo 'layerName = SurfaceView[eu.rekawek.coffeegb.android/eu.rekawek.coffeegb.android.MainActivity](BLAST)#2374'
        echo 'totalFrames = 100'
        echo "displayRefreshRate = $display_rate fps"
        echo 'droppedFrames = 0'
        echo 'lateAcquireFrames = 0'
        echo 'badDesiredPresentFrames = 0'
        echo 'present2present histogram is as below:'
        echo '0ms=0 16ms=100'
      fi
      ;;
    *) : ;;
  esac
  exit 0
fi

if [ "$sub" = am ]; then
  action=${2:-}
  if [ "$action" = force-stop ]; then
    printf 'force-stop\n' >>"$records"
    exit 0
  fi
  [ "$action" = start ] || exit 1
  profile=; pair=; block=; order=; side=; first=; slot=; launch_rate=; artifact=; execution=accuracy; audio_policy=canonical; scenario=none; session_generation=; arm=
  shift 2
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --es|--ez|--ei)
        key=$2; value=$3
        case "$key" in
          coffee_gb_hardware) profile=$value ;;
          coffee_gb_build_id) artifact=$value ;;
          coffee_gb_pair_id) pair=$value ;;
          coffee_gb_matrix_block) block=$value ;;
          coffee_gb_row_order) order=$value ;;
          coffee_gb_run_side) side=$value ;;
          coffee_gb_first_side) first=$value ;;
          coffee_gb_recent_slot) slot=$value ;;
          coffee_gb_surface_rate_hz) launch_rate=$value ;;
          coffee_gb_execution_mode) execution=$value ;;
          coffee_gb_audio_policy) audio_policy=$value ;;
          coffee_gb_benchmark_scenario) scenario=$value ;;
          coffee_gb_benchmark_arm_token) arm=$value ;;
        esac
        shift 3
        ;;
      *) shift ;;
    esac
  done
  if [ -n "$arm" ]; then
    printf 'arm token=%s\n' "$arm" >>"$records"
    if [ -f "$context" ]; then
      profile=$(awk -F= '$1 == "profile" { print $2 }' "$context")
      pair=$(awk -F= '$1 == "pair" { print $2 }' "$context")
      block=$(awk -F= '$1 == "block" { print $2 }' "$context")
      order=$(awk -F= '$1 == "order" { print $2 }' "$context")
      side=$(awk -F= '$1 == "side" { print $2 }' "$context")
      first=$(awk -F= '$1 == "first" { print $2 }' "$context")
      slot=$(awk -F= '$1 == "slot" { print $2 }' "$context")
      launch_rate=$(awk -F= '$1 == "rate" { print $2 }' "$context")
      artifact=$(awk -F= '$1 == "artifact" { print $2 }' "$context")
      execution=$(awk -F= '$1 == "execution" { print $2 }' "$context")
      audio_policy=$(awk -F= '$1 == "audio_policy" { print $2 }' "$context")
      scenario=$(awk -F= '$1 == "scenario" { print $2 }' "$context")
      session_generation=$(awk -F= '$1 == "session_generation" { print $2 }' "$context")
    fi
    generation=$(($(cat "$gen_file") + 1))
    printf '%s\n' "$generation" >"$gen_file"
    if [ "$mode" = privacy ]; then
      printf 'I/CoffeeGbBench: event=final_result uri=/private/workload.gb\n' >>"$log"
    elif [ "$mode" != timeout ]; then
      case "$profile:$slot" in
        cgb:2) effective=cgb-native; gbc=true; compat=false ;;
        cgb:3) effective=cgb-dmg-compat; gbc=true; compat=true ;;
        cgb0:2) effective=cgb0-native; gbc=true; compat=false ;;
        dmg:3) effective=dmg; gbc=false; compat=false ;;
        mgb:3) effective=mgb; gbc=false; compat=false ;;
        sgb:3) effective=sgb; gbc=false; compat=false ;;
        sgb2:3) effective=sgb2; gbc=false; compat=false ;;
        *) effective=unknown; gbc=false; compat=false ;;
      esac
      content_rate=59728
      ready_fps=59.7275
      [ "$effective" = sgb ] && { content_rate=61168; ready_fps=61.1679; }
      case "$scenario" in
        dmg-action-v1) scenario_frames=313; scenario_generation=$session_generation ;;
        cgb-action-v1) scenario_frames=923; scenario_generation=$session_generation ;;
        *) scenario_frames=0; scenario_generation=0 ;;
      esac
      if [ "$mode" = scenario_generation ] && [ "$scenario_generation" -gt 0 ]; then
        scenario_generation=$((scenario_generation + 1))
      fi
      [ "$mode" = scenario_frames ] && scenario_frames=$((scenario_frames + 1))
      scenario_completed=true
      scenario_source_closed=true
      scenario_audio_drained=true
      [ "$mode" = scenario_incomplete ] && scenario_completed=false
      [ "$mode" = scenario_cleanup ] && { scenario_source_closed=false; scenario_audio_drained=false; }
      audio_start_pending=0
      audio_start_queued=0
      audio_start_playing=true
      [ "$mode" = baseline_pending ] && audio_start_pending=4
      [ "$mode" = baseline_queued ] && audio_start_queued=4
      [ "$mode" = baseline_stopped ] && audio_start_playing=false
      audio_start_system_volume=10
      audio_start_system_music_muted=false
      { [ "$audio_policy" = silent-pcm-v1 ] || [ "$audio_policy" = silent-pcm-relaxed-apu-v1 ]; } && {
        audio_start_system_volume=0
        audio_start_system_music_muted=true
      }
      matrix_record="I/CoffeeGbBench: event=matrix_run build_profile=benchmark artifact_id=$artifact pair_id=$pair matrix_block=$block row_order=$order run_side=$side first_side=$first session_generation=$session_generation benchmark_generation=$generation benchmark_token=$arm workload_nonce=app-owned-test-nonce-0001 warmup=true input_contract=$scenario scenario_session_generation=$scenario_generation scenario_completed=$scenario_completed scenario_completed_frames=$scenario_frames scenario_expected_frames=$scenario_frames scenario_source_closed=$scenario_source_closed scenario_audio_drained=$scenario_audio_drained execution_mode=$execution thermal_window=m2 audio=on render=presentation availability=available requested_hardware=$profile benchmark_audio_policy=$audio_policy surface_vote_hz=$launch_rate display_target_hz=$launch_rate surface_content_rate_millihz=$content_rate profile=$profile effective_gbc=$gbc effective_dmg_compat=$compat effective_mode=$effective device_id=3333333333333333333333333333333333333333333333333333333333333333 audio_start_pending_bytes=$audio_start_pending audio_start_queued_bytes=$audio_start_queued audio_start_output_playing=$audio_start_playing audio_start_queue_empty_polls=0 audio_start_queue_probe=0,0,0,0,2048,960 audio_start_active=true audio_start_paused=false audio_start_muted=false audio_start_volume=100 audio_start_system_volume=$audio_start_system_volume audio_start_system_volume_max=15 audio_start_system_music_muted=$audio_start_system_music_muted audio_start_queued_frames=0 audio_start_reopen_pending=false audio_start_output_identity=1 audio_start_queue_identity=1"
      printf '%s\n' "$matrix_record" >>"$log"
      probe_frame=60
      while [ "$probe_frame" -le 600 ]; do
        probe_intervals=$((probe_frame - 1))
        printf 'I/CoffeeGbBench: event=audio_timing_probe artifact_id=%s pair_id=%s matrix_block=%s row_order=%s run_side=%s benchmark_generation=%s frame=%s probe_generation=1 audio_timing=%s,17000,0,0,100,500,0,0,0,100,100,100,0,1000 audio_underrun_attribution=0,0,0,0,0,0,0\n' \
          "$artifact" "$pair" "$block" "$order" "$side" "$generation" \
          "$probe_frame" "$probe_intervals" >>"$log"
        probe_frame=$((probe_frame + 60))
      done
      audio_system_volume=10
      audio_system_music_muted=false
      benchmark_audio_requested=false
      benchmark_audio_active_at_boundary=false
      benchmark_audio_disabled_after=true
      benchmark_audio_skipped_ticks=0
      benchmark_audio_zero_sample_slots=0
      benchmark_audio_zero_sample_events=0
      benchmark_audio_max_debt=0
      benchmark_audio_apu_reads=0
      benchmark_audio_apu_writes=0
      benchmark_audio_frame_sequencer_commits=0
      benchmark_audio_dropped_channel_ticks=0
      benchmark_audio_flags=001
      benchmark_audio_calendar=0,0,0,0,0,0,0,0
      { [ "$audio_policy" = silent-pcm-v1 ] || [ "$audio_policy" = silent-pcm-relaxed-apu-v1 ]; } && {
        audio_system_volume=0
        audio_system_music_muted=true
        benchmark_audio_requested=true
        benchmark_audio_active_at_boundary=true
        benchmark_audio_disabled_after=true
        benchmark_audio_skipped_ticks=1
        benchmark_audio_zero_sample_slots=1
        benchmark_audio_zero_sample_events=1
        benchmark_audio_max_debt=1
        benchmark_audio_apu_reads=1
        benchmark_audio_apu_writes=1
        benchmark_audio_frame_sequencer_commits=1
        [ "$audio_policy" = silent-pcm-relaxed-apu-v1 ] && \
          benchmark_audio_dropped_channel_ticks=1
        benchmark_audio_flags=111
        benchmark_audio_calendar="$benchmark_audio_skipped_ticks,$benchmark_audio_zero_sample_slots,$benchmark_audio_zero_sample_events,$benchmark_audio_max_debt,$benchmark_audio_apu_reads,$benchmark_audio_apu_writes,$benchmark_audio_frame_sequencer_commits,$benchmark_audio_dropped_channel_ticks"
      }
      compact_audio_extra=
      [ "$mode" = compact_flags_tail ] && benchmark_audio_flags="${benchmark_audio_flags}0"
      [ "$mode" = compact_calendar_extra ] && \
        benchmark_audio_calendar="${benchmark_audio_calendar},0"
      [ "$mode" = compact_apu_overflow ] && \
        benchmark_audio_calendar='1,1,1,1,9223372036854775808,0,1,0'
      [ "$mode" = compact_mixed ] && \
        compact_audio_extra=' benchmark_audio_requested=false'
      system_audio_sample_count=0
      system_audio_bad_count=0
      { [ "$audio_policy" = silent-pcm-v1 ] || [ "$audio_policy" = silent-pcm-relaxed-apu-v1 ]; } && {
        system_audio_sample_count=12
      }
      final_record="I/CoffeeGbBench: event=final_result build_profile=benchmark artifact_id=$artifact pair_id=$pair matrix_block=$block row_order=$order run_side=$side session_generation=$session_generation benchmark_generation=$generation benchmark_token=$arm frame=600 ready_count=600 ready_interval_fps=$ready_fps submission_interval_fps=$ready_fps submitted_count=600 dropped_count=0 duplicate_count=0 late_count=0 corrupt_count=0 requested_profile=$profile profile=$profile effective_gbc=$gbc effective_dmg_compat=$compat effective_mode=$effective execution_mode=$execution surface_vote_hz=$launch_rate display_target_hz=$launch_rate surface_content_rate_millihz=$content_rate warmup=true input_contract=$scenario scenario_session_generation=$scenario_generation scenario_completed=$scenario_completed scenario_completed_frames=$scenario_frames scenario_expected_frames=$scenario_frames scenario_source_closed=$scenario_source_closed scenario_audio_drained=$scenario_audio_drained drain_success=true benchmark_audio_policy=$audio_policy benchmark_audio_flags=$benchmark_audio_flags benchmark_audio_calendar=$benchmark_audio_calendar$compact_audio_extra audio_active=true audio_output_playing=true audio_muted=false audio_volume=100 audio_system_volume=$audio_system_volume audio_system_music_muted=$audio_system_music_muted audio_overruns=0 audio_queue_empty_polls=1 audio_queue_probe=1,0,0,0,2048,960 audio_output_identity=1 audio_queue_identity=1 audio_track_underruns=0 audio_restarts=0 audio_write_failures=0 audio_route_failures=0 audio_focus_granted=true audio_focus_start_loss_count=0 audio_focus_loss_count=0 live_input_mutations=0 thermal_worst=0 display_bad_count=0 interactive_bad_count=0 plugged_bad_count=0 power_save_bad_count=0 stay_awake_bad_count=0 workload_nonce=app-owned-test-nonce-0001 device_id=3333333333333333333333333333333333333333333333333333333333333333 system_audio_sample_count=$system_audio_sample_count system_audio_bad_count=$system_audio_bad_count"
      printf '%s\n' "$final_record" >>"$log"
    fi
  elif [ "$mode" != timeout ]; then
    session_generation=$(($(cat "$gen_file") + 1))
    case "$scenario" in
      dmg-action-v1) scenario_frames=313 ;;
      cgb-action-v1) scenario_frames=923 ;;
      *) scenario_frames=0 ;;
    esac
    if [ "$scenario" != none ] && [ "$mode" != scenario_missing ]; then
      emitted_frames=$scenario_frames
      [ "$mode" = scenario_frames ] && emitted_frames=$((emitted_frames + 1))
      scenario_pair=$pair
      [ "$mode" = scenario_identity ] && scenario_pair=stale-pair
      completed=true
      source_closed=true
      audio_drained=true
      [ "$mode" = scenario_incomplete ] && completed=false
      [ "$mode" = scenario_cleanup ] && { source_closed=false; audio_drained=false; }
      printf 'I/CoffeeGbBench: event=scenario_complete artifact_id=%s pair_id=%s matrix_block=%s row_order=%s run_side=%s session_generation=%s input_contract=%s completed=%s completed_frames=%s expected_frames=%s source_closed=%s audio_drained=%s\n' \
        "$artifact" "$scenario_pair" "$block" "$order" "$side" "$session_generation" \
        "$scenario" "$completed" "$emitted_frames" "$scenario_frames" "$source_closed" \
        "$audio_drained" >>"$log"
    elif [ "$scenario" = none ] && [ "$mode" = scenario_missing ]; then
      printf 'I/CoffeeGbBench: event=scenario_complete artifact_id=%s pair_id=%s matrix_block=%s row_order=%s run_side=%s session_generation=%s input_contract=dmg-action-v1 completed=true completed_frames=313 expected_frames=313 source_closed=true audio_drained=true\n' \
        "$artifact" "$pair" "$block" "$order" "$side" "$session_generation" >>"$log"
    fi
    printf 'I/CoffeeGbBench: event=warmup_complete completed=true phase=warming\n' >>"$log"
    printf 'I/CoffeeGbBench: event=benchmark_anchor success=true phase=anchor_ready\n' >>"$log"
  fi
  if [ -z "$arm" ]; then
    printf 'profile=%s\npair=%s\nblock=%s\norder=%s\nside=%s\nfirst=%s\nslot=%s\nrate=%s\nartifact=%s\nexecution=%s\naudio_policy=%s\nscenario=%s\nsession_generation=%s\n' \
      "$profile" "$pair" "$block" "$order" "$side" "$first" "$slot" "$launch_rate" "$artifact" "$execution" "$audio_policy" "$scenario" "$session_generation" >"$context"
    printf 'launch profile=%s slot=%s rate=%s mode=%s scenario=%s pair=%s order=%s side=%s first=%s\n' \
      "$profile" "$slot" "$launch_rate" "$execution" "$scenario" "$pair" "$order" "$side" "$first" >>"$records"
  fi
  printf 'Status: ok\n'
  exit 0
fi

if [ "$sub" = logcat ]; then
  if [ "${2:-}" = -c ]; then
    : >"$log"
  else
    cat "$log"
  fi
  exit 0
fi

exit 1
STUB
chmod 700 "$tmp/adb"

cat >"$tmp/gate" <<'STUB'
#!/bin/sh
set -eu
getarg() {
  wanted=$1
  shift
  while [ "$#" -gt 1 ]; do
    if [ "$1" = "$wanted" ]; then echo "$2"; return 0; fi
    shift 2
  done
  return 1
}
artifact=$(getarg --artifact-id "$@") || exit 1
device=$(getarg --device-id "$@") || exit 1
pair=$(getarg --pair-id "$@") || exit 1
block=$(getarg --matrix-block "$@") || exit 1
order=$(getarg --row-order "$@") || exit 1
side=$(getarg --run-side "$@") || exit 1
generation=$(getarg --benchmark-generation "$@") || exit 1
rate=$(getarg --display-refresh-hz "$@") || exit 1
ready=$(getarg --ready-fps "$@") || exit 1
before=$(getarg --before "$@") || exit 1
after=$(getarg --after "$@") || exit 1
case "$before:$after" in *.txt:*.txt) : ;; *) exit 1 ;; esac
[ -f "$before" ] && [ -f "$after" ] || exit 1
printf 'gate artifact=%s device=%s pair=%s block=%s order=%s side=%s generation=%s rate=%s ready=%s\n' \
  "$artifact" "$device" "$pair" "$block" "$order" "$side" "$generation" "$rate" "$ready" >>"${FAKE_RECORDS:?}"
if [ "$rate" -ge 90 ]; then
  vsync_total=1175
  histogram_fps=61.1679
else
  vsync_total=602
  histogram_fps=59.7275
fi
echo "event=compositor_result artifact_id=$artifact device_id=$device pair_id=$pair matrix_block=$block row_order=$order run_side=$side benchmark_generation=$generation layer_id=4444444444444444444444444444444444444444444444444444444444444444 layer_uid=10327 display_refresh_hz=$rate raw_total_frames=601 raw_histogram_frames=601 boundary_frames=1 boundary_intervals=2 total_frames=600 histogram_frames=599 present_interval_count=599 cadence_good_frames=599 cadence_bad_frames=0 cadence_vsync_total=$vsync_total cadence_boundary_200_frames=1 cadence_boundary_1000_frames=1 cadence_max_gap_ms=1000 cadence_min_gap_ms=16 compositor_histogram_fps=$histogram_fps dropped_frames=0 late_acquire_frames=0 bad_desired_present_frames=0 measurement=surfaceflinger_timestats"
STUB
chmod 700 "$tmp/gate"

cat >"$tmp/matrix" <<'STUB'
#!/bin/sh
set -eu
input=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --parent-apk|--candidate-apk) shift 2 ;;
    *) input=$1; shift ;;
  esac
done
[ -n "$input" ] || exit 1
if grep -E '(^|[[:space:]])(uri|title|content|payload|rom_path|save_path)=' "$input" >/dev/null 2>&1; then
  exit 1
fi
echo accepted=true
STUB
chmod 700 "$tmp/matrix"

run_case() {
  mode=$1
  policy=${2:-canonical}
  FAKE_APKSIGNER_MODE=${FAKE_APKSIGNER_MODE:-ok} FAKE_MODE=$mode \
    FAKE_CALLS="$calls" FAKE_RECORDS="$records" FAKE_LOG="$fake_log" \
    FAKE_RATE="$rate_file" FAKE_GEN="$gen_file" \
    COFFEE_GB_M2_ADB="$tmp/adb" COFFEE_GB_M2_APKSIGNER="$tmp/apksigner" \
    COFFEE_GB_M2_SHA256="$tmp/sha256sum" COFFEE_GB_M2_MATRIX_SCRIPT="$tmp/matrix" \
    COFFEE_GB_M2_GATE_SCRIPT="$tmp/gate" COFFEE_GB_M2_FAST=1 COFFEE_GB_M2_SEED=7 \
    COFFEE_GB_M2_ANCHOR_POLLS=2 COFFEE_GB_M2_FINAL_POLLS=2 \
    "$root/benchmark-device-matrix.sh" --parent-apk "$parent" --candidate-apk "$candidate" \
      --color-slot 2 --non-color-slot 3 --execution-mode performance \
      --audio-policy "$policy" \
      --output-dir "$tmp/out-$mode" \
      >"$tmp/$mode.stdout" 2>"$tmp/$mode.stderr"
}

expect_failure() {
  mode=$1
  policy=${2:-canonical}
  : >"$records"
  : >"$fake_log"
  printf '0\n' >"$gen_file"
  set +e
  if [ "$mode" = unsigned ]; then
    FAKE_APKSIGNER_MODE=unsigned run_case ok
    rc=$?
  else
    run_case "$mode" "$policy"
    rc=$?
  fi
  set -e
  [ "$rc" -ne 0 ] || { echo "accepted fail-closed case $mode" >&2; exit 1; }
}

sh -n "$root/benchmark-device-matrix.sh"
sh -n "$root/benchmark-device-matrix-test.sh"

: >"$records"
: >"$fake_log"
printf '0\n' >"$gen_file"
if ! run_case ok; then
  cat "$tmp/ok.stderr" >&2
  exit 1
fi

launch_count=$(awk '/^launch / { count++ } END { print count + 0 }' "$records")
[ "$launch_count" -eq 168 ] || { echo "expected 168 launches, got $launch_count" >&2; exit 1; }
force_stop_count=$(awk '/^force-stop$/ { count++ } END { print count + 0 }' "$records")
[ "$force_stop_count" -eq 168 ] || { echo "expected 168 force-stops, got $force_stop_count" >&2; exit 1; }
arm_count=$(awk '/^arm / { count++ } END { print count + 0 }' "$records")
[ "$arm_count" -eq 168 ] || { echo "expected 168 singleTop arms, got $arm_count" >&2; exit 1; }
install_count=$(grep -c -- ' install -r -d --no-streaming ' "$calls" || true)
[ "$install_count" -eq 168 ] || { echo "expected 168 data-preserving installs, got $install_count" >&2; exit 1; }
awk '
  /^launch / {
    launch_no++
    pair=$0; sub(/^.*pair=/, "", pair); sub(/[[:space:]].*$/, "", pair)
    side=$0; sub(/^.*side=/, "", side); sub(/[[:space:]].*$/, "", side)
    first=$0; sub(/^.*first=/, "", first); sub(/[[:space:]].*$/, "", first)
    rate=$0; sub(/^.*rate=/, "", rate); sub(/[[:space:]].*$/, "", rate)
    mode=$0; sub(/^.*mode=/, "", mode); sub(/[[:space:]].*$/, "", mode)
    profile=$0; sub(/^.*profile=/, "", profile); sub(/[[:space:]].*$/, "", profile)
    scenario=$0; sub(/^.*scenario=/, "", scenario); sub(/[[:space:]].*$/, "", scenario)
    slot=$0; sub(/^.*slot=/, "", slot); sub(/[[:space:]].*$/, "", slot)
    if ((launch_no - 1) % 14 == 0) { block_no++; expected_first=(block_no % 2 == 1 ? "parent" : "candidate") }
    if (first != expected_first) exit 2
    if ((launch_no % 2) == 1) { prior_pair=pair; prior_side=side } else if (pair != prior_pair || side == prior_side) exit 3
    row=pair; sub(/^p[0-9]+-/, "", row)
    if ((row == "sgb" && rate != 120) || (row != "sgb" && rate != 60)) exit 4
    if (mode != "performance") exit 12
    if (((row == "cgb-native" || row == "cgb0-native") && slot != 2) || \
        ((row != "cgb-native" && row != "cgb0-native") && slot != 3)) exit 5
    if ((row == "cgb-native" || row == "cgb-dmg-compat") && profile != "cgb") exit 6
    if (row == "cgb0-native" && profile != "cgb0") exit 7
    if ((row == "dmg" || row == "mgb" || row == "sgb" || row == "sgb2") && profile != row) exit 8
    if (((row == "dmg" || row == "mgb" || row == "cgb-dmg-compat") && scenario != "dmg-action-v1") || \
        ((row == "cgb-native" || row == "cgb0-native") && scenario != "cgb-action-v1") || \
        ((row == "sgb" || row == "sgb2") && scenario != "none")) exit 13
    if ((launch_no % 2) == 1) {
      if (seen[block_no SUBSEP row]++) exit 9
      rows_in_block[block_no]++
    }
  }
  END {
    if (launch_no != 168 || block_no != 12) exit 10
    for (b = 1; b <= 12; b++) if (rows_in_block[b] != 7) exit 11
  }
' "$records" || { echo 'schedule/adjacency/alternation/rate assertion failed' >&2; exit 1; }

grep -q -- '--es coffee_gb_hardware cgb' "$calls" || { echo 'profile was not passed' >&2; exit 1; }
grep -q -- '--ei coffee_gb_recent_slot 2' "$calls" || { echo 'color slot was not passed' >&2; exit 1; }
grep -q -- '--ei coffee_gb_recent_slot 3' "$calls" || { echo 'non-color slot was not passed' >&2; exit 1; }
grep -q -- '--es coffee_gb_benchmark_scenario dmg-action-v1' "$calls" || { echo 'DMG input contract was not passed' >&2; exit 1; }
grep -q -- '--es coffee_gb_benchmark_scenario cgb-action-v1' "$calls" || { echo 'CGB input contract was not passed' >&2; exit 1; }
grep -q -- '--es coffee_gb_benchmark_scenario none' "$calls" || { echo 'SGB input contract was not passed' >&2; exit 1; }
if grep -q -- 'coffee_gb_workload_nonce' "$calls"; then
  echo 'host workload nonce was passed' >&2
  exit 1
fi
if grep -E 'pm clear|clear-data|\.gb(c|cstate)?|\.sav|uri=|title=' "$calls" >/dev/null 2>&1; then
  echo 'privacy/data-clear command was observed' >&2
  exit 1
fi
if grep -Ei 'adjust[-_[:alnum:]]*volume|set[-_[:alnum:]]*volume|set[-_[:alnum:]]*mute|set[-_[:alnum:]]*ringer|cmd[[:space:]]+audio|cmd[[:space:]]+media_session[[:space:]]+.*volume|media_session[[:space:]]+volume|keyevent[[:space:]]+(24|25|164|volume_up|volume_down|volume_mute)|settings[[:space:]]+put[[:space:]]+.*(volume|mute|ringer)' "$calls" >/dev/null 2>&1; then
  echo 'audio volume mutation command was observed' >&2
  exit 1
fi
if rg -n -i 'audioManager\.(adjustVolume|adjustStreamVolume|adjustSuggestedStreamVolume|setStreamVolume|setStreamMute|setRingerMode)\(|KEYCODE_VOLUME_(UP|DOWN|MUTE)' \
    "$root/app/src/main" >/dev/null 2>&1; then
  echo 'Android production source contains a system-audio mutation API' >&2
  exit 1
fi
[ -f "$tmp/out-ok/matrix.log" ] && [ -f "$tmp/out-ok/matrix-report.txt" ] || {
  echo 'redacted output artifacts are missing' >&2
  exit 1
}
if awk '
  {
    start=index($0, "event=")
    if (start == 0) next
    record=substr($0, start)
    if (record ~ /[\/\\]|:\/\/|(^|[[:space:]])(uri|title|content|payload)=/) bad=1
  }
  END { exit bad ? 0 : 1 }
' "$tmp/out-ok/matrix.log"; then
  echo 'durable log contains a path/content field' >&2
  exit 1
fi

gate_count=$(awk '/^gate / { count++ } END { print count + 0 }' "$records")
[ "$gate_count" -eq 168 ] || { echo "expected 168 compositor gate links, got $gate_count" >&2; exit 1; }
awk '
  function value(name, i, token) {
    for (i = 1; i <= NF; i++) {
      if ($i ~ ("^" name "=")) {
        token=$i
        sub("^" name "=", "", token)
        return token
      }
    }
    return ""
  }
  /^gate / {
    gate_no++
    generation=value("generation")
    artifact=value("artifact")
    device=value("device")
    pair=value("pair")
    block=value("block")
    order=value("order")
    side=value("side")
    rate=value("rate")
    row=pair
    sub(/^p[0-9]+-/, "", row)
    if (generation != gate_no || length(artifact) != 64 || artifact !~ /^[0-9a-f][0-9a-f]*$/ ||
        length(device) != 64 || device !~ /^[0-9a-f][0-9a-f]*$/ ||
        pair == "" || block == "" || order == "" || (side != "parent" && side != "candidate") ||
        (row == "sgb" ? rate != 120 : rate != 60)) exit 2
  }
  END { if (gate_no != 168) exit 3 }
' "$records" || { echo 'compositor gate identity/generation linkage assertion failed' >&2; exit 1; }

# The explicit silent policy covers the complete seven-row matrix, including SGB/SGB2.
: >"$records"
: >"$fake_log"
printf '0\n' >"$gen_file"
if ! run_case silent_ok silent-pcm-v1; then
  cat "$tmp/silent_ok.stderr" >&2
  exit 1
fi
silent_launch_count=$(awk '/^launch / { count++ } END { print count + 0 }' "$records")
[ "$silent_launch_count" -eq 168 ] || {
  echo "expected 168 silent-policy launches, got $silent_launch_count" >&2
  exit 1
}
if ! awk '/^launch / { pair=$0; sub(/^.*pair=/, "", pair); sub(/[[:space:]].*$/, "", pair); row=pair; sub(/^p[0-9]+-/, "", row); if (row == "sgb") sgb=1; if (row == "sgb2") sgb2=1 } END { exit sgb && sgb2 ? 0 : 1 }' "$records"; then
  echo 'silent policy did not schedule both SGB rows' >&2
  exit 1
fi
if ! awk '/^launch / { pair=$0; sub(/^.*pair=/, "", pair); sub(/[[:space:]].*$/, "", pair); row=pair; sub(/^p[0-9]+-/, "", row); scenario=$0; sub(/^.*scenario=/, "", scenario); sub(/[[:space:]].*$/, "", scenario); if ((row == "sgb" || row == "sgb2") && scenario != "dmg-action-v1") bad=1 } END { exit bad ? 1 : 0 }' "$records"; then
  echo 'exact silent SGB rows did not use the DMG gameplay precondition' >&2
  exit 1
fi
grep -q -- '--es coffee_gb_audio_policy silent-pcm-v1' "$calls" || {
  echo 'silent audio policy was not passed' >&2
  exit 1
}

: >"$records"
: >"$fake_log"
printf '0\n' >"$gen_file"
if ! run_case relaxed_ok silent-pcm-relaxed-apu-v1; then
  cat "$tmp/relaxed_ok.stderr" >&2
  exit 1
fi
relaxed_launch_count=$(awk '/^launch / { count++ } END { print count + 0 }' "$records")
[ "$relaxed_launch_count" -eq 120 ] || {
  echo "expected 120 relaxed silent-policy launches, got $relaxed_launch_count" >&2
  exit 1
}
if awk '/^launch / { pair=$0; sub(/^.*pair=/, "", pair); sub(/[[:space:]].*$/, "", pair); row=pair; sub(/^p[0-9]+-/, "", row); if (row == "sgb" || row == "sgb2") bad=1 } END { exit bad ? 0 : 1 }' "$records"; then
  echo 'relaxed silent policy scheduled an unsupported SGB row' >&2
  exit 1
fi
grep -q -- '--es coffee_gb_audio_policy silent-pcm-relaxed-apu-v1' "$calls" || {
  echo 'relaxed silent audio policy was not passed' >&2
  exit 1
}

expect_failure wrong_device
expect_failure unsigned
expect_failure layer
expect_failure timeout
expect_failure privacy
expect_failure scenario_missing
expect_failure scenario_frames
expect_failure scenario_generation
expect_failure scenario_identity
expect_failure scenario_incomplete
expect_failure scenario_cleanup
expect_failure baseline_pending
expect_failure baseline_queued
expect_failure baseline_stopped
expect_failure post_final_invalidation
expect_failure compact_flags_tail
expect_failure compact_calendar_extra
expect_failure compact_mixed
expect_failure compact_apu_overflow silent-pcm-v1

echo 'benchmark device matrix hermetic fixture: PASS'
