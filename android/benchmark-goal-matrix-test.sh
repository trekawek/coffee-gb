#!/bin/sh
set -eu

# Hermetic contract test for benchmark-goal-matrix.sh.  Every command which would normally reach
# Android is replaced by a local fake; this test never invokes adb, a device, Maven, or audio.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
RUNNER=$SCRIPT_DIR/benchmark-goal-matrix.sh
REPORT=$SCRIPT_DIR/benchmark-goal-matrix-report.sh
GATE=$SCRIPT_DIR/benchmark-goal-matrix-surface-gate.sh
TEST_ROOT=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-goal-test.XXXXXX")
trap '[ "${KEEP_GOAL_TEST_TMP:-0}" = 1 ] || rm -rf "$TEST_ROOT"' EXIT HUP INT TERM
FAKE_BIN=$TEST_ROOT/bin
mkdir -p "$FAKE_BIN"
PARENT_APK=$TEST_ROOT/parent.apk
CANDIDATE_APK=$TEST_ROOT/candidate.apk
printf 'parent-fixture\n' >"$PARENT_APK"
printf 'candidate-fixture\n' >"$CANDIDATE_APK"

cat >"$FAKE_BIN/apksigner" <<'EOF'
#!/bin/sh
set -eu
apk=; print_certs=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    verify) :; shift ;;
    --verbose) shift ;;
    --print-certs) print_certs=true; shift ;;
    --*) shift ;;
    *) apk=$1; shift ;;
  esac
done
[ -n "$apk" ] || exit 1
if [ "$print_certs" = true ]; then
  echo "Signer #1 certificate SHA-256 digest: aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
else
  echo "Verified using v2 scheme"
fi
EOF

cat >"$FAKE_BIN/aapt" <<'EOF'
#!/bin/sh
set -eu
echo "package: name='eu.rekawek.coffeegb.android' versionCode='1'"
EOF

cat >"$FAKE_BIN/adb" <<'EOF'
#!/bin/sh
set -eu

state=${FAKE_ADB_STATE:?}
mode=${FAKE_MODE:-positive}
[ "${FAKE_DEBUG:-0}" = 1 ] && set -x
mkdir -p "$state"
printf '%s\n' "$*" >>"$state/calls"
if [ "${1-}" = -s ]; then shift 2; fi
command=${1-}; [ -n "$command" ] || exit 1; shift || true
printf 'command=%s args=%s\n' "$command" "$*" >>"$state/debug"

ctx() {
  file=$state/context.$1
  if [ -f "$file" ]; then sed -n '1p' "$file"; else printf '\n'; fi
}
putctx() { printf '%s\n' "$2" >"$state/context.$1"; }
emit() { printf '08-26 20:00:00.000 100 100 I/CoffeeGbBench: %s\n' "$*" >>"$state/log"; }
slot_count() {
  case "$1" in d) echo 313 ;; u) echo 1297 ;; c1) echo 1582 ;; c2) echo 1084 ;; *) exit 1 ;; esac
}
slot_nonce() {
  case "$1" in d) echo nonce-d-000000000001 ;; u) echo nonce-u-000000000001 ;;
    c1) echo nonce-c1-00000000001 ;; c2) echo nonce-c2-00000000001 ;; *) exit 1 ;; esac
}
cell_profile() {
  case "$1" in
    d-dmg|u-dmg) echo dmg ;; d-cgb-compat) echo cgb-compat ;; d-sgb|u-sgb) echo sgb ;;
    u-cgb-native|c1-cgb-native|c2-cgb-native) echo cgb-native ;; *) exit 1 ;;
  esac
}
cell_hardware() {
  case "$1" in d-dmg|u-dmg) echo dmg ;; d-cgb-compat|u-cgb-native|c1-cgb-native|c2-cgb-native) echo cgb ;; d-sgb|u-sgb) echo sgb ;; *) exit 1 ;; esac
}
core_profile() {
  case "$1" in dmg|cgb-compat|cgb-native) echo "$( [ "$1" = dmg ] && echo dmg || echo cgb )" ;; sgb) echo sgb ;; *) exit 1 ;; esac
}
counter_line() {
  profile=$1
  scalar=42134400
  [ "$mode" = bad_core ] && scalar=42134401
  center=0; [ "$profile" = sgb ] && center=13824000
  allocations=0
  [ "$profile" = sgb ] && [ "$(ctx side)" = parent ] && allocations=600
  speed1=42134400
  speed2=0
  if [ "$profile" = cgb-native ]; then
    speed1=10000000
    speed2=32134400
  fi
  echo "scheduler_master_ticks=42134400 scheduler_scalar_ticks=$scalar scheduler_phase_count=0 scheduler_phase_ticks=0 scheduler_phase_max_ticks=0 scheduler_halt_count=0 scheduler_halt_ticks=0 scheduler_halt_max_ticks=0 scheduler_epoch_count=0 scheduler_epoch_ticks=0 scheduler_epoch_max_ticks=0 scheduler_length_bucket_0=0 scheduler_length_bucket_1=0 scheduler_length_bucket_2=0 scheduler_length_bucket_3=0 scheduler_length_bucket_4=0 scheduler_speed1_ticks=$speed1 scheduler_speed2_ticks=$speed2 scheduler_speed_switch_ticks=0 scheduler_ppu_direct_ticks=0 scheduler_ppu_fallback_ticks=0 scheduler_ppu_fast_ticks=0 scheduler_cpu_safe_accesses=1 scheduler_cpu_direct_rom_reads=0 scheduler_cpu_terminal_reads=0 scheduler_cpu_terminal_writes=0 scheduler_audio_skipped_ticks=42134400 scheduler_audio_zero_sample_slots=$( [ "$profile" = sgb ] && echo 3830400 || echo 766080 ) scheduler_audio_materializations=1 scheduler_sgb_frame_array_allocations=$allocations scheduler_sgb_border_rebuilds=0 scheduler_sgb_center_pixels=$center"
}
identity() {
  cell=$(ctx cell); slot=$(ctx slot); profile=$(cell_profile "$cell"); hardware=$(cell_hardware "$cell")
  echo "matrix_version=goal-matrix-v1 cell_id=$cell workload_slot=$slot workload_nonce=$(slot_nonce "$slot") scenario_id=$slot-v1 scenario_count=$(ctx count) expected_profile=$profile effective_profile=$profile requested_hardware=$hardware execution_mode=performance pair_id=$(ctx pair) matrix_block=$(ctx block) row_order=$(ctx row) recent_slot=$(ctx recent) session_generation=1 run_side=$(ctx side)"
}
emit_final() {
  cell=$(ctx cell); profile=$(cell_profile "$cell"); hardware=$(cell_hardware "$cell"); slot=$(ctx slot)
  case "$profile" in sgb) core=dummy; ready=61.0 ;; *) core=dummy; ready=60.0 ;; esac
  [ "$mode" = slow_parent ] && [ "$(ctx side)" = parent ] && ready=55.0
  [ "$mode" = bad_audio ] && system_volume=1 || system_volume=0
  extra=
  [ "$mode" = privacy ] && extra=' rom_title=private-fixture'
  if [ "$mode" = bad_nonce ]; then nonce=nonce-corrupt-00000001; else nonce=$(slot_nonce "$slot"); fi
  ident="matrix_version=goal-matrix-v1 cell_id=$cell workload_slot=$slot workload_nonce=$nonce scenario_id=$slot-v1 scenario_count=$(ctx count) expected_profile=$profile effective_profile=$profile requested_hardware=$hardware execution_mode=performance pair_id=$(ctx pair) matrix_block=$(ctx block) row_order=$(ctx row) recent_slot=$(ctx recent) session_generation=1 run_side=$(ctx side)"
  core_id=core-$(ctx cell)-$(ctx side)
  events=603; slots=766080; [ "$profile" = sgb ] && events=600 && slots=3830400
  effective_mode=$profile; [ "$profile" = cgb-compat ] && effective_mode=cgb-dmg-compat
  gbc=false; compat=false; [ "$profile" = cgb-native ] || [ "$profile" = cgb-compat ] && gbc=true
  [ "$profile" = cgb-compat ] && compat=true
  clock='clock_ticks_num=4194304 clock_ticks_den=1 clock_frames_num=60 clock_frames_den=1 clock_ticks_frame=69905'
  if [ "$profile" = sgb ]; then
    clock='clock_ticks_num=47250000 clock_ticks_den=11 clock_frames_num=140625 clock_frames_den=2299 clock_ticks_frame=70224'
  fi
  emit "event=final_result $ident core_result_id=$core_id frame=600 fps=$ready ready_interval_fps=$ready submission_interval_fps=$ready build_profile=benchmark artifact_id=$(ctx artifact) device_id=eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee benchmark_generation=1 requested_profile=$(cell_hardware "$cell") profile=$(core_profile "$profile") effective_gbc=$gbc effective_dmg_compat=$compat effective_mode=$effective_mode speed_mode_initial=1 speed_mode_final=1 $clock scenario_session_generation=1 scenario_completed=true scenario_completed_frames=$(ctx count) scenario_expected_frames=$(ctx count) scenario_source_closed=true scenario_audio_drained=true ready_count=600 submitted_count=600 dropped_count=0 duplicate_count=0 late_count=0 corrupt_count=0 ready_first_id=1 ready_last_id=600 ready_first_ns=1 ready_last_ns=600 submission_first_id=1 submission_last_id=600 submission_first_ns=1 submission_last_ns=600 audio_active=true audio_sample_rate=48000 audio_overruns=0 audio_underruns=0 audio_track_underruns=0 audio_restarts=0 audio_paused=false audio_min_buffer_bytes=1024 audio_configured_buffer_bytes=2048 audio_actual_buffer_bytes=2048 audio_pcm_input_events=$((10 + events)) audio_pcm_input_frames=$((100 + slots)) audio_pcm_enqueued_bytes=40000 audio_pcm_enqueued_frames=10000 audio_pcm_written_bytes=40000 audio_pcm_written_frames=10000 audio_write_failures=0 audio_pcm_discarded_bytes=0 audio_pcm_pending_bytes=0 audio_pcm_queued_bytes=0 audio_queue_frames=0 audio_output_open=true audio_output_playing=true audio_muted=false audio_volume=100 audio_route_failures=0 audio_playback_position_frames=2000 audio_system_volume=$system_volume audio_system_volume_max=25 audio_system_music_muted=true audio_queue_capacity_frames=6 audio_max_frame_bytes=4096 audio_output_identity=1 audio_queue_identity=2 audio_start_ledger=10,100,400,100,400,100,0,0,1,2 benchmark_audio_policy=silent-pcm-v1 benchmark_audio_flags=111 benchmark_audio_calendar=42134400,$slots,$events,1,0,0,1,0 system_audio_sample_count=12 system_audio_bad_count=0 audio_focus_granted=true audio_focus_start_loss_count=0 audio_focus_loss_count=0 drain_success=true live_input_mutations=0 speed_mode_sample=frame_600$extra"
}
emit_run() {
  ident=$(identity); cell=$(ctx cell); profile=$(cell_profile "$cell"); core_id=core-$cell-$(ctx side)
  boot_profile=$(core_profile "$profile"); gbc=false; compat=false
  case "$profile" in cgb-native) gbc=true ;; cgb-compat) gbc=true; compat=true ;; esac
  if [ "$mode" = bad_order ]; then
    emit "event=core_result $ident core_result_id=$core_id frame=600 $(counter_line "$profile")"
  fi
  emit "event=matrix_run $ident"
  if [ "$mode" != bad_order ]; then
    emit "event=core_result $ident core_result_id=$core_id frame=600 $(counter_line "$profile")"
  fi
  [ "$mode" = missing_event ] && return 0
  emit_final
  if [ "$mode" = duplicate_event ]; then emit_final; fi
}
parse_start() {
  cell=; slot=; recent=; hardware=; artifact=; pair=; block=; row=; side=; first=; bootstrap=; scenario=; rate=; arm=
  while [ "$#" -gt 0 ]; do
    case "$1" in
      --es|--ei|--ez)
        key=$2; value=$3; shift 3
        case "$key" in
          coffee_gb_cell_id) cell=$value ;; coffee_gb_workload_slot) slot=$value ;;
          coffee_gb_recent_slot) recent=$value ;; coffee_gb_hardware) hardware=$value ;;
          coffee_gb_build_id) artifact=$value ;; coffee_gb_pair_id) pair=$value ;;
          coffee_gb_matrix_block) block=$value ;; coffee_gb_row_order) row=$value ;;
          coffee_gb_run_side) side=$value ;; coffee_gb_first_side) first=$value ;;
          coffee_gb_bootstrap) bootstrap=$value ;; coffee_gb_benchmark_scenario) scenario=$value ;;
          coffee_gb_surface_rate_hz) rate=$value ;; coffee_gb_benchmark_arm_token) arm=$value ;;
        esac
        ;;
      *) shift ;;
    esac
  done
  if [ -n "$arm" ]; then emit_run; else
    [ -n "$cell" ] || exit 1
    count=$(slot_count "$slot"); [ "$mode" = wrong_u ] && [ "$slot" = u ] && count=1012
    putctx cell "$cell"; putctx slot "$slot"; putctx recent "$recent"; putctx artifact "$artifact"; putctx pair "$pair"; putctx block "$block"; putctx row "$row"; putctx side "$side"; putctx first "$first"; putctx bootstrap "$bootstrap"; putctx count "$count"; putctx rate "$rate"
    emit "event=boot_result $(identity) requested_bootstrap=$bootstrap bootstrap_outcome=$( [ "$bootstrap" = skip ] && echo skipped || echo authentic_handoff ) profile=$(core_profile "$(cell_profile "$cell")") effective_gbc=$( [ "$(cell_profile "$cell")" = cgb-native ] || [ "$(cell_profile "$cell")" = cgb-compat ] && echo true || echo false ) effective_dmg_compat=$( [ "$(cell_profile "$cell")" = cgb-compat ] && echo true || echo false ) effective_speed_mode=1 accepted=true"
    # The first boot_result is intentionally visible before anchor; the real app may emit the
    # same identity from its bootstrap observer.  The arm emits the authoritative matrix prefix.
    if [ "$mode" != normal_timeout ]; then emit 'event=benchmark_anchor success=true phase=anchor_ready'; fi
  fi
}

case "$command" in
  devices) echo 'List of devices attached'; echo '4LJJSS7L8PWWMNWW device product:dew model:25078RA3EE transport_id:1' ;;
  install) echo 'Success' ;;
  logcat)
    if [ "${1-}" = -c ]; then : >"$state/log"; echo cleared
    else [ -f "$state/log" ] && cat "$state/log" || :; fi
    ;;
  shell)
    sub=${1-}; shift || true
    case "$sub" in
      getprop) case "$1" in ro.product.model) echo 25078RA3EE ;; ro.product.device) echo dew ;; ro.build.version.sdk) echo 35 ;; *) echo '' ;; esac ;;
      settings)
        op=$1; namespace=$2; name=$3
        case "$op" in
          get) case "$namespace.$name" in system.peak_refresh_rate|system.min_refresh_rate|system.user_refresh_rate) echo 60 ;; secure.user_refresh_rate) echo 55 ;; system.is_smart_fps) echo 1 ;; esac ;;
          put) value=$4; if [ "$mode" = display_restore_fail ] && [ "$namespace" = system ] && [ "$name" = is_smart_fps ] && [ "$value" = 1 ]; then exit 1; fi; ;;
          delete) : ;;
        esac ;;
      cmd)
        if [ "$1" = display ] && [ "$2" = get-user-preferred-display-mode ]; then echo 'User preferred display mode: 720 1600 60.0';
        elif [ "$1" = display ] && [ "$2" = get-displays ]; then rate=60; [ -f "$state/rate" ] && rate=$(sed -n '1p' "$state/rate"); echo "Active refresh rate: ${rate}Hz"; echo 'Supported modes: 60Hz 90Hz 120Hz'; echo "RenderFrameRate ${rate}.0";
        elif [ "$1" = display ] && [ "$2" = set-user-preferred-display-mode ]; then printf '%s\n' "$5" >"$state/rate";
        elif [ "$1" = display ] && [ "$2" = clear-user-preferred-display-mode ]; then printf '60\n' >"$state/rate";
        elif [ "$1" = package ] && [ "$2" = list ]; then echo 'package:eu.rekawek.coffeegb.android uid:10042'; fi ;;
      logcat)
        case " $* " in *' -c '*) : >"$state/log"; echo cleared ;; *) [ -f "$state/log" ] && cat "$state/log" || : ;; esac ;;
      am)
        if [ "$1" = force-stop ]; then
          :
        elif [ "$1" = start ]; then
          shift
          parse_start "$@"
          echo 'Status: ok'
        fi
        ;;
      dumpsys)
        if [ "$1" = audio ]; then
          if [ "$mode" = unmuted_audio ]; then
            cat <<'BAD_AUDIO'
- STREAM_MUSIC:
  Muted: false
  streamVolume: 1
  Devices: speaker(2)
  Current: 1 (earpiece): 5, 2 (speaker): 1
- STREAM_ALARM:
  Muted: false
BAD_AUDIO
          else
          cat <<'AUDIO'
- STREAM_MUSIC:
  Muted: true
  streamVolume: 0
  Current: 1 (earpiece): 5, 2 (speaker): 0, 4 (hdmi): 0
  Devices: speaker(2)
- STREAM_ALARM:
  Muted: false
AUDIO
          fi
        elif [ "$1" = SurfaceFlinger ]; then
          case "$2" in
            --list) echo 'SurfaceView[eu.rekawek.coffeegb.android/.MainActivity](BLAST)#1' ;;
            --timestats)
              [ "${3-}" = -clear ] && printf '0\n' >"$state/dumps"; [ "${3-}" = -dump ] || exit 0
              n=0; [ -f "$state/dumps" ] && n=$(sed -n '1p' "$state/dumps"); n=$((n + 1)); printf '%s\n' "$n" >"$state/dumps"
              rate=60; [ -f "$state/rate" ] && rate=$(sed -n '1p' "$state/rate"); total=0; hist='16ms=0 200ms=0 1000ms=0'
              if [ "$n" -ge 2 ]; then
                total=601
                if [ "$mode" = stall ] || [ "$mode" = bad_compositor ] || { [ "$mode" = slow_parent ] && [ "$(ctx side)" = parent ]; }; then hist='16ms=598 200ms=1 500ms=1 1000ms=1';
                elif [ "$rate" = 120 ]; then hist='8ms=24 16ms=575 200ms=1 1000ms=1';
                else hist='16ms=599 200ms=1 1000ms=1'; fi
                [ "$mode" = stale_invalidated ] && emit 'event=benchmark_invalidated reason=late_state'
              fi
              printf 'layerName = SurfaceView[eu.rekawek.coffeegb.android/.MainActivity](BLAST)#1\nuid = 10042\ndisplayRefreshRate = %s fps\ntotalFrames = %s\ndroppedFrames = 0\nlateAcquireFrames = 0\nbadDesiredPresentFrames = 0\npresent2present histogram is as below:\n%s\n\n' "$rate" "$total" "$hist"
              ;;
          esac
        elif [ "$1" = package ]; then echo 'package:eu.rekawek.coffeegb.android uid:10042'; fi
        ;;
    esac
    ;;
  *) exit 1 ;;
esac
EOF
chmod +x "$FAKE_BIN/adb" "$FAKE_BIN/apksigner" "$FAKE_BIN/aapt"

run_case() {
  case_name=$1; expected=$2; shift 2
  case_state=$TEST_ROOT/state.$case_name; case_output=$TEST_ROOT/output.$case_name
  mkdir -p "$case_state"
  : >"$case_state/log"
  set +e
  FAKE_ADB_STATE=$case_state FAKE_MODE=$case_name COFFEE_GB_GOAL_ADB=$FAKE_BIN/adb \
    COFFEE_GB_GOAL_APKSIGNER=$FAKE_BIN/apksigner COFFEE_GB_GOAL_AAPT=$FAKE_BIN/aapt \
    COFFEE_GB_GOAL_REPORT_SCRIPT=$REPORT COFFEE_GB_GOAL_GATE_SCRIPT=$GATE \
    COFFEE_GB_GOAL_FAST=1 COFFEE_GB_GOAL_POLL_LIMIT=2 COFFEE_GB_GOAL_ANCHOR_POLL_LIMIT=2 \
    COFFEE_GB_GOAL_POLL_SECONDS=0 COFFEE_GB_GOAL_REFRESH_WAIT_SECONDS=0 \
    "$RUNNER" --parent-apk "$PARENT_APK" --candidate-apk "$CANDIDATE_APK" \
      --bootstrap "$1" --output-dir "$case_output" >"$TEST_ROOT/$case_name.stdout" 2>"$TEST_ROOT/$case_name.stderr"
  result=$?
  set -e
  if [ "$expected" = success ]; then
    [ "$result" -eq 0 ] || { sed -n '1,20p' "$TEST_ROOT/$case_name.stderr" >&2; exit 1; }
    printf '%s\n' "$case_output"
  else
    [ "$result" -ne 0 ] || { echo "expected $case_name to fail" >&2; exit 1; }
  fi
}

check_positive() {
  case_output=$1; case_state=$2
  grep -q '^accepted=true$' "$TEST_ROOT/$(basename "$case_output").stdout" 2>/dev/null || :
  [ "$(grep -c '^event=matrix_run ' "$case_output/matrix.log")" -eq 16 ]
  [ "$(grep -c '^event=boot_result ' "$case_output/matrix.log")" -eq 16 ]
  [ "$(grep -c '^event=core_result ' "$case_output/matrix.log")" -eq 16 ]
  [ "$(grep -c '^event=final_result ' "$case_output/matrix.log")" -eq 16 ]
  [ "$(wc -l <"$case_output/compositor.log")" -eq 16 ]
  [ "$(grep -c ' install -r -d --no-streaming ' "$case_state/calls")" -eq 16 ]
  [ "$(grep -c 'am start' "$case_state/calls")" -eq 32 ]
  [ "$(grep -c 'coffee_gb_benchmark_arm_token' "$case_state/calls")" -eq 16 ]
  [ "$(grep -c 'dumpsys audio' "$case_state/calls")" -ge 49 ]
  ! grep -Eiq 'pm[[:space:]]+clear|uninstall|set(stream|)-volume|set-stream-mute|adjustVolume|routing|focus' "$case_state/calls"
  ! grep -Eiq 'rom_|save_|title=|header=|checksum=|frame_hash=|pixel_hash=|(^|[[:space:]])(path|uri|file)=' "$case_output/matrix.log"
  ! grep -q 'screen_mode_type' "$case_state/calls"
  [ "$(awk -F= '$1=="event" {count++} END {print count+0}' "$case_output/matrix.log")" -eq 64 ]
}

# Use distinct labels for the full positive modes so no state or fresh-output check is shared.
for bootstrap in skip fast-forward normal; do
  label=positive-$bootstrap
  out=$(run_case "$label" success "$bootstrap")
  check_positive "$TEST_ROOT/output.$label" "$TEST_ROOT/state.$label"
  grep -q "requested_bootstrap=$bootstrap" "$TEST_ROOT/output.$label/matrix.log"
done

# Actual report-side artifact binding fixtures: swapped, missing, and mismatched final IDs.
base=$TEST_ROOT/output.positive-normal/matrix.log
pid=$(sha256sum "$PARENT_APK" | awk '{print $1}')
cid=$(sha256sum "$CANDIDATE_APK" | awk '{print $1}')
awk -v p="$pid" -v c="$cid" 'BEGIN{done=0} /event=final_result/ && !done && $0 ~ "artifact_id="p {sub("artifact_id="p,"artifact_id="c); done=1} {print}' "$base" >"$TEST_ROOT/swapped.log"
awk -v p="$pid" 'BEGIN{done=0} /event=final_result/ && !done && $0 ~ "artifact_id="p {sub(" artifact_id="p," "); done=1} {print}' "$base" >"$TEST_ROOT/missing.log"
awk -v p="$pid" 'BEGIN{done=0} /event=final_result/ && !done && $0 ~ "artifact_id="p {sub("artifact_id="p,"artifact_id=" substr("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",1,64)); done=1} {print}' "$base" >"$TEST_ROOT/mismatch.log"
for fixture in swapped missing mismatch; do
  set +e
  "$REPORT" --input "$TEST_ROOT/$fixture.log" --parent-id "$pid" --candidate-id "$cid" >"$TEST_ROOT/$fixture.out" 2>&1
  result=$?
  set -e
  [ "$result" -ne 0 ] || { echo "$fixture artifact fixture unexpectedly accepted" >&2; exit 1; }
done

# The exact ready/submission floors are intentionally tested just below each boundary.  The
# compact fps field may remain unchanged; report validation uses the full-precision interval
# fields, matching the production producer's formatExact() values.
awk 'BEGIN { changed=0 }
  /event=final_result/ && /cell_id=d-dmg/ && /run_side=candidate/ && !changed {
    sub("ready_interval_fps=60.0", "ready_interval_fps=59.1302249")
    sub("submission_interval_fps=60.0", "submission_interval_fps=59.1302249")
    changed=1
  }
  { print }
  END { if (!changed) exit 1 }' "$base" >"$TEST_ROOT/below-legacy.log"
awk 'BEGIN { changed=0 }
  /event=final_result/ && /cell_id=d-sgb/ && /run_side=candidate/ && !changed {
    sub("ready_interval_fps=61.0", "ready_interval_fps=60.5562209")
    sub("submission_interval_fps=61.0", "submission_interval_fps=60.5562209")
    changed=1
  }
  { print }
  END { if (!changed) exit 1 }' "$base" >"$TEST_ROOT/below-sgb.log"
for fixture in below-legacy below-sgb; do
  set +e
  "$REPORT" --input "$TEST_ROOT/$fixture.log" --parent-id "$pid" --candidate-id "$cid" \
    >"$TEST_ROOT/$fixture.out" 2>&1
  result=$?
  set -e
  [ "$result" -ne 0 ] || { echo "$fixture floor fixture unexpectedly accepted" >&2; exit 1; }
done

# Structural/negative runner fixtures, including wrong U endpoint, parser core invariant, audio,
# privacy, event order/cardinality, post-final invalidation, real cadence stall, and restore.
for failure in wrong_u bad_core bad_audio unmuted_audio privacy bad_order missing_event duplicate_event stale_invalidated stall display_restore_fail normal_timeout; do
  bootstrap=fast-forward
  [ "$failure" = normal_timeout ] && bootstrap=normal
  run_case "$failure" failure "$bootstrap" >/dev/null
done

# Direct real SurfaceFlinger fixture: parent may be a slow structural control; a candidate must
# reject the same out-of-lane bucket.  This also checks the 200/1000 lifecycle boundaries.
surface=$TEST_ROOT/surface
mkdir -p "$surface"
layer='SurfaceView[eu.rekawek.coffeegb.android/.MainActivity](BLAST)#1'
make_dump() {
  total=$1; hist=$2; file=$3
  printf 'layerName = %s\nuid = 10042\ndisplayRefreshRate = 60 fps\ntotalFrames = %s\ndroppedFrames = 0\nlateAcquireFrames = 0\nbadDesiredPresentFrames = 0\npresent2present histogram is as below:\n%s\n\n' "$layer" "$total" "$hist" >"$file"
}
make_dump 0 '16ms=0 200ms=0 1000ms=0' "$surface/before.txt"
make_dump 601 '16ms=598 200ms=1 500ms=1 1000ms=1' "$surface/after-stall.txt"
make_dump 601 '16ms=599 200ms=1 1000ms=1' "$surface/after-good.txt"
id=$(printf '%064d' 0 | tr 0 d); did=$(printf '%064d' 0 | tr 0 e)
"$GATE" --uid 10042 --layer "$layer" --display-refresh-hz 60 --before "$surface/before.txt" --after "$surface/after-stall.txt" --artifact-id "$id" --device-id "$did" --pair-id p-surface --matrix-block b-surface --row-order 0 --run-side parent --benchmark-generation 1 --ready-fps 59.7275 --require-target false >/dev/null
set +e
"$GATE" --uid 10042 --layer "$layer" --display-refresh-hz 60 --before "$surface/before.txt" --after "$surface/after-stall.txt" --artifact-id "$id" --device-id "$did" --pair-id p-surface --matrix-block b-surface --row-order 0 --run-side candidate --benchmark-generation 1 --ready-fps 59.7275 --require-target true >/dev/null 2>&1
result=$?
set -e
[ "$result" -ne 0 ]

echo 'goal-matrix hermetic tests: PASS (16-launch x 3 bootstrap modes, parser, surface gate, negatives)'
