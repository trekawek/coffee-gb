#!/bin/sh
set -eu

# Compositor companion for goal-matrix-v1. Row order is 0..7 (the legacy gate only knows 0..6).
# The layer name and dumps are held in a private temporary directory and never printed.

usage() {
  echo "usage: benchmark-goal-matrix-surface-gate.sh --uid <uid> --layer <name>" >&2
  echo "       --display-refresh-hz <60|120> --before <dump.txt> --after <dump.txt>" >&2
  echo "       --artifact-id <sha256> --device-id <sha256> --pair-id <token>" >&2
  echo "       --matrix-block <token> --row-order <0..7> --run-side parent|candidate" >&2
  echo "       --benchmark-generation <positive> --ready-fps <positive>" >&2
  echo "       [--require-target true|false]" >&2
}
fatal() {
  echo "benchmark-goal-matrix-surface-gate: $1" >&2
  exit 1
}

uid=
layer=
display_refresh_hz=
before=
after=
artifact_id=
device_id=
pair_id=
matrix_block=
row_order=
run_side=
benchmark_generation=
ready_fps=
require_target=true
while [ "$#" -gt 0 ]; do
  case "$1" in
    --uid|--layer|--display-refresh-hz|--before|--after|--artifact-id|--device-id|--pair-id|--matrix-block|--row-order|--run-side|--benchmark-generation|--ready-fps|--require-target)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      key=$1
      value=$2
      case "$key" in
        --uid) uid=$value ;;
        --layer) layer=$value ;;
        --display-refresh-hz) display_refresh_hz=$value ;;
        --before) before=$value ;;
        --after) after=$value ;;
        --artifact-id) artifact_id=$value ;;
        --device-id) device_id=$value ;;
        --pair-id) pair_id=$value ;;
        --matrix-block) matrix_block=$value ;;
        --row-order) row_order=$value ;;
        --run-side) run_side=$value ;;
        --benchmark-generation) benchmark_generation=$value ;;
        --ready-fps) ready_fps=$value ;;
        --require-target) require_target=$value ;;
      esac
      shift 2
      ;;
    --help|-h) usage; exit 0 ;;
    *) usage; exit 2 ;;
  esac
done

case "$uid" in ''|*[!0-9]*) fatal "uid is malformed" ;; esac
[ "$uid" -gt 0 ] || fatal "uid is invalid"
case "$display_refresh_hz" in 60|120) : ;; *) fatal "display rate must be 60 or 120" ;; esac
case "$row_order" in 0|1|2|3|4|5|6|7) : ;; *) fatal "row order is malformed" ;; esac
case "$run_side" in parent|candidate) : ;; *) fatal "run side is malformed" ;; esac
case "$require_target" in true|false) : ;; *) fatal "target requirement is malformed" ;; esac
case "$benchmark_generation" in ''|*[!0-9]*) fatal "benchmark generation is malformed" ;; esac
[ "$benchmark_generation" -gt 0 ] || fatal "benchmark generation is invalid"
case "$artifact_id" in ''|*[!0-9a-fA-F]*) fatal "artifact identity is malformed" ;; esac
case "$device_id" in ''|*[!0-9a-fA-F]*) fatal "device identity is malformed" ;; esac
[ "${#artifact_id}" -eq 64 ] || fatal "artifact identity is malformed"
[ "${#device_id}" -eq 64 ] || fatal "device identity is malformed"
case "$pair_id" in ''|*[!a-zA-Z0-9._-]*) fatal "pair identity is malformed" ;; esac
case "$matrix_block" in ''|*[!a-zA-Z0-9._-]*) fatal "block identity is malformed" ;; esac
[ -n "$layer" ] || fatal "layer is required"
[ "${#layer}" -le 512 ] || fatal "layer is too long"
case "$layer" in *"="*|*"|"*|*";"*|*'$'*|*'\\'*) fatal "layer is ambiguous" ;; esac
for snapshot in "$before" "$after"; do
  case "$snapshot" in *.txt|*.log) : ;; *) fatal "snapshot extension is unsupported" ;; esac
  [ -f "$snapshot" ] || fatal "snapshot is missing"
done
awk -v fps="$ready_fps" 'BEGIN { exit(fps > 0 ? 0 : 1) }' || fatal "ready FPS is invalid"

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-goal-gate.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

select_record() {
  input=$1
  output=$2
  awk -v wanted_uid="$uid" -v wanted_layer="$layer" -v wanted_rate="$display_refresh_hz" '
    function value_for(record, key, lines, count, i, line) {
      count=split(record, lines, "\n")
      for (i=1; i<=count; i++) {
        line=lines[i]
        if (line ~ "^[[:space:]]*" key "[[:space:]]*=") {
          sub("^[[:space:]]*" key "[[:space:]]*=[[:space:]]*", "", line)
          return line
        }
      }
      return ""
    }
    function numeric_rate(value) {
      sub(/[[:space:]]+fps.*/, "", value)
      return (value ~ /^[0-9]+([.][0-9]+)?$/) ? value + 0 : -1
    }
    BEGIN { RS=""; found=0 }
    {
      record=$0
      if (value_for(record, "uid") == wanted_uid && value_for(record, "layerName") == wanted_layer && numeric_rate(value_for(record, "displayRefreshRate")) == wanted_rate + 0) {
        found++
        print record
      }
    }
    END { exit(found == 1 ? 0 : 1) }
  ' "$input" >"$output" || fatal "expected one matching SurfaceFlinger layer"
}

extract_stats() {
  input=$1
  output=$2
  awk -v want_rate="$display_refresh_hz" -v target_fps="$ready_fps" '
    function counter(record, key, lines, n, i, line) {
      n=split(record, lines, "\n")
      for (i=1; i<=n; i++) {
        line=lines[i]
        if (line ~ "^[[:space:]]*" key "[[:space:]]*=") {
          sub("^[[:space:]]*" key "[[:space:]]*=[[:space:]]*", "", line)
          if (line ~ /^[0-9]+$/) return line + 0
        }
      }
      return -1
    }
    function histogram(record, lines, n, i, line, lower, count, sum, in_hist) {
      n=split(record, lines, "\n"); sum=0; in_hist=0
      for (i=1; i<=n; i++) {
        line=lines[i]; lower=tolower(line)
        if (lower ~ /present2present|presenttopresent/) {
          in_hist=1
          while (match(line, /=[[:space:]]*[0-9]+([[:space:]]|$)/)) {
            count=substr(line, RSTART, RLENGTH)
            sub(/^=[[:space:]]*/, "", count)
            sub(/[[:space:]].*$/, "", count)
            sum += count + 0
            line=substr(line, RSTART + RLENGTH)
          }
          continue
        }
        if (!in_hist) continue
        if (line !~ /^[[:space:]]*[0-9]+ms[[:space:]]*[=:]/) {
          in_hist=0
          continue
        }
        while (match(line, /=[[:space:]]*[0-9]+([[:space:]]|$)/)) {
          count=substr(line, RSTART, RLENGTH)
          sub(/^=[[:space:]]*/, "", count)
          sub(/[[:space:]].*$/, "", count)
          sum += count + 0
          line=substr(line, RSTART + RLENGTH)
        }
      }
      return sum
    }
    function cadence(record, lines, n, i, line, lower, in_hist,
            token, millis, count, good, bad, max_gap, min_gap, vsync_total,
            boundary200, boundary1000, interval_count, lane) {
      n=split(record, lines, "\n"); in_hist=0; good=0; bad=0; max_gap=0; min_gap=-1
      vsync_total=0; boundary200=0; boundary1000=0; interval_count=0
      for (i=1; i<=n; i++) {
        line=lines[i]; lower=tolower(line)
        if (lower ~ /present2present|presenttopresent/) { in_hist=1; continue }
        if (!in_hist) continue
        if (line !~ /^[[:space:]]*[0-9]+ms[[:space:]]*[=:]/) { in_hist=0; continue }
        while (match(line, /[0-9]+ms[=:][[:space:]]*[0-9]+/)) {
          token=substr(line, RSTART, RLENGTH)
          millis=token; sub(/ms.*/, "", millis)
          count=token; sub(/^.*[=:][[:space:]]*/, "", count)
          millis=millis+0; count=count+0
          if (count > 0) {
            if (millis == 200) boundary200 += count
            else if (millis == 1000) boundary1000 += count
            else {
              lane=0
              low_lane=int(want_rate / target_fps)
              if (low_lane < 1) low_lane=1
              high_lane=low_lane + 1
              low_target=1000 * low_lane / want_rate
              high_target=1000 * high_lane / want_rate
              if (millis >= low_target - 2 && millis <= low_target + 2) lane=low_lane
              if (millis >= high_target - 2 && millis <= high_target + 2) lane=high_lane
              if (lane > 0) { good += count; vsync_total += count * lane; interval_count += count }
              else { bad += count; interval_count += count }
            }
            if (millis > max_gap) max_gap=millis
            if (min_gap < 0 || millis < min_gap) min_gap=millis
          }
          line=substr(line, RSTART + RLENGTH)
        }
      }
      if (min_gap < 0) min_gap=0
      print "cadence_good_frames=" good
      print "cadence_bad_frames=" bad
      print "cadence_max_gap_ms=" max_gap
      print "cadence_min_gap_ms=" min_gap
      print "cadence_vsync_total=" vsync_total
      print "cadence_interval_count=" interval_count
      print "cadence_boundary_200_frames=" boundary200
      print "cadence_boundary_1000_frames=" boundary1000
    }
    BEGIN { RS="" }
    {
      print "total_frames=" counter($0, "totalFrames")
      print "histogram_frames=" histogram($0)
      print "dropped_frames=" counter($0, "droppedFrames")
      late=counter($0, "lateAcquireFrames")
      if (late < 0) late=counter($0, "lateAcquire")
      print "late_acquire_frames=" late
      bad=counter($0, "badDesiredPresentFrames")
      if (bad < 0) bad=counter($0, "badDesiredPresent")
      print "bad_desired_present_frames=" bad
      cadence($0)
    }
  ' "$input" >"$output"
}

select_record "$before" "$tmp_dir/before"
select_record "$after" "$tmp_dir/after"
extract_stats "$tmp_dir/before" "$tmp_dir/before.stats"
extract_stats "$tmp_dir/after" "$tmp_dir/after.stats"

read_stat() {
  name=$1
  file=$2
  value=$(awk -F= -v wanted="$name" '$1 == wanted { print $2; exit }' "$file")
  case "$value" in ''|*[!0-9]*) fatal "compositor counter is unavailable" ;; esac
  printf '%s\n' "$value"
}
before_total=$(read_stat total_frames "$tmp_dir/before.stats")
after_total=$(read_stat total_frames "$tmp_dir/after.stats")
before_hist=$(read_stat histogram_frames "$tmp_dir/before.stats")
after_hist=$(read_stat histogram_frames "$tmp_dir/after.stats")
before_drop=$(read_stat dropped_frames "$tmp_dir/before.stats")
after_drop=$(read_stat dropped_frames "$tmp_dir/after.stats")
before_late=$(read_stat late_acquire_frames "$tmp_dir/before.stats")
after_late=$(read_stat late_acquire_frames "$tmp_dir/after.stats")
before_bad=$(read_stat bad_desired_present_frames "$tmp_dir/before.stats")
after_bad=$(read_stat bad_desired_present_frames "$tmp_dir/after.stats")
before_good=$(read_stat cadence_good_frames "$tmp_dir/before.stats")
after_good=$(read_stat cadence_good_frames "$tmp_dir/after.stats")
before_cadence_bad=$(read_stat cadence_bad_frames "$tmp_dir/before.stats")
after_cadence_bad=$(read_stat cadence_bad_frames "$tmp_dir/after.stats")
after_max_gap=$(read_stat cadence_max_gap_ms "$tmp_dir/after.stats")
before_vsync_total=$(read_stat cadence_vsync_total "$tmp_dir/before.stats")
after_vsync_total=$(read_stat cadence_vsync_total "$tmp_dir/after.stats")
before_interval_count=$(read_stat cadence_interval_count "$tmp_dir/before.stats")
after_interval_count=$(read_stat cadence_interval_count "$tmp_dir/after.stats")
before_boundary_200=$(read_stat cadence_boundary_200_frames "$tmp_dir/before.stats")
after_boundary_200=$(read_stat cadence_boundary_200_frames "$tmp_dir/after.stats")
before_boundary_1000=$(read_stat cadence_boundary_1000_frames "$tmp_dir/before.stats")
after_boundary_1000=$(read_stat cadence_boundary_1000_frames "$tmp_dir/after.stats")

delta_total=$((after_total - before_total))
delta_hist=$((after_hist - before_hist))
delta_drop=$((after_drop - before_drop))
delta_late=$((after_late - before_late))
delta_bad=$((after_bad - before_bad))
delta_good=$((after_good - before_good))
delta_cadence_bad=$((after_cadence_bad - before_cadence_bad))
delta_vsync_total=$((after_vsync_total - before_vsync_total))
delta_interval_count=$((after_interval_count - before_interval_count))
delta_boundary_200=$((after_boundary_200 - before_boundary_200))
delta_boundary_1000=$((after_boundary_1000 - before_boundary_1000))
if [ "$delta_total" -lt 0 ] || [ "$delta_hist" -lt 0 ] || [ "$delta_drop" -lt 0 ] \
    || [ "$delta_late" -lt 0 ] || [ "$delta_bad" -lt 0 ] || [ "$delta_good" -lt 0 ] \
    || [ "$delta_cadence_bad" -lt 0 ] || [ "$delta_vsync_total" -lt 0 ] \
    || [ "$delta_interval_count" -lt 0 ] || [ "$delta_boundary_200" -lt 0 ] \
    || [ "$delta_boundary_1000" -lt 0 ]; then
  fatal "compositor counters decreased; baseline/after dumps are not comparable"
fi

layer_id=$(printf '%s' "$layer" | sha256sum | awk '{ print $1 }')
case "$layer_id" in ''|*[!0-9a-f]*) fatal "layer identity could not be derived" ;; esac
[ "${#layer_id}" -eq 64 ] || fatal "layer identity is malformed"

boundary_frames=1
adjusted_total=$((delta_total - boundary_frames))
boundary_intervals=2
adjusted_hist=$((delta_hist - boundary_intervals))
histogram_fps=$(awk -v count="$delta_interval_count" -v refresh="$display_refresh_hz" \
    -v vsyncs="$delta_vsync_total" 'BEGIN { if (vsyncs > 0) printf "%.6f", count * refresh / vsyncs; else print "0" }')
echo "event=compositor_result artifact_id=$artifact_id device_id=$device_id pair_id=$pair_id matrix_block=$matrix_block row_order=$row_order run_side=$run_side benchmark_generation=$benchmark_generation layer_id=$layer_id layer_uid=$uid display_refresh_hz=$display_refresh_hz raw_total_frames=$delta_total raw_histogram_frames=$delta_hist boundary_frames=$boundary_frames boundary_intervals=$boundary_intervals total_frames=$adjusted_total histogram_frames=$adjusted_hist present_interval_count=$delta_interval_count cadence_good_frames=$delta_good cadence_bad_frames=$delta_cadence_bad cadence_vsync_total=$delta_vsync_total cadence_boundary_200_frames=$delta_boundary_200 cadence_boundary_1000_frames=$delta_boundary_1000 cadence_max_gap_ms=$after_max_gap cadence_min_gap_ms=$(read_stat cadence_min_gap_ms "$tmp_dir/after.stats") compositor_histogram_fps=$histogram_fps dropped_frames=$delta_drop late_acquire_frames=$delta_late bad_desired_present_frames=$delta_bad measurement=surfaceflinger_timestats"

[ "$delta_total" -eq 601 ] || fatal "compositor total frame delta is not 601"
[ "$delta_hist" -eq 601 ] || fatal "compositor histogram delta is not 601"
[ "$adjusted_total" -eq 600 ] || fatal "compositor total frame normalization is not 600"
[ "$adjusted_hist" -eq 599 ] || fatal "compositor histogram normalization is not 599"
[ "$delta_interval_count" -eq 599 ] || fatal "compositor interval count is not 599"
[ "$delta_good" -ge 0 ] || fatal "compositor cadence evidence is malformed"
[ "$delta_cadence_bad" -ge 0 ] || fatal "compositor cadence evidence is malformed"
[ "$delta_boundary_200" -eq 1 ] || fatal "compositor 200ms boundary is missing"
[ "$delta_boundary_1000" -eq 1 ] || fatal "compositor 1000ms boundary is missing"
[ "$delta_drop" -eq 0 ] || fatal "compositor dropped frame delta is non-zero"
[ "$delta_late" -eq 0 ] || fatal "compositor late-acquire delta is non-zero"
[ "$delta_bad" -eq 0 ] || fatal "compositor bad-desired-present delta is non-zero"
if [ "$require_target" = true ]; then
  [ "$delta_vsync_total" -gt 0 ] || fatal "compositor cadence has no vsync lane"
  [ "$delta_good" -eq 599 ] || fatal "compositor cadence has non-vsync frames"
  [ "$delta_cadence_bad" -eq 0 ] || fatal "compositor cadence has a stall bucket"
  target_floor=59.130225
  [ "$display_refresh_hz" = 120 ] && target_floor=60.556221
  awk -v actual="$histogram_fps" -v floor="$target_floor" \
    'BEGIN { exit(actual >= floor ? 0 : 1) }' \
    || fatal "compositor histogram FPS is below the absolute target floor"
fi
