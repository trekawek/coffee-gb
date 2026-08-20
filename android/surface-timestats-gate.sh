#!/bin/sh
set -eu

# Validate the compositor-side companion to one CoffeeGbBench submission window.
#
# API 35's text timestats dump is paragraph based and uses fields such as:
#
#   layerName = SurfaceView[package/activity](BLAST)#123
#   packageName =
#   uid = 10234
#   totalFrames = 600
#   droppedFrames = 0
#   present2present histogram is as below:
#   0ms=0 16ms=600 ...
#
# OEM builds may add lateAcquireFrames and badDesiredPresentFrames. They are required here;
# an absent counter is an ineligible measurement rather than an assumed zero. The script never
# receives ROM/save input. Layer names are retained only in this short-lived host process and
# the emitted matrix record contains a SHA-256 layer ID, not the name.

usage() {
  echo "usage: surface-timestats-gate.sh --uid <uid> --layer <name> --display-refresh-hz <hz>" >&2
  echo "       --before <dump.txt> --after <dump.txt> --artifact-id <sha256>" >&2
  echo "       --device-id <sha256> --pair-id <token> --matrix-block <token>" >&2
  echo "       --row-order <0..6> --run-side parent|candidate" >&2
  echo "       --benchmark-generation <positive-integer> --ready-fps <positive-number>" >&2
}

uid=
layer=
layer_prefix=
before=
after=
artifact_id=
device_id=
pair_id=
matrix_block=
row_order=
run_side=
display_refresh_hz=
benchmark_generation=
ready_fps=

while [ "$#" -gt 0 ]; do
  case "$1" in
    --uid|--layer|--layer-prefix|--display-refresh-hz|--before|--after|--artifact-id|--device-id|--pair-id|--matrix-block|--row-order|--run-side|--benchmark-generation|--ready-fps)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      case "$1" in
        --uid) uid=$2 ;;
        --layer) layer=$2 ;;
        --layer-prefix) layer_prefix=$2 ;;
        --display-refresh-hz) display_refresh_hz=$2 ;;
        --before) before=$2 ;;
        --after) after=$2 ;;
        --artifact-id) artifact_id=$2 ;;
        --device-id) device_id=$2 ;;
        --pair-id) pair_id=$2 ;;
        --matrix-block) matrix_block=$2 ;;
        --row-order) row_order=$2 ;;
        --run-side) run_side=$2 ;;
        --benchmark-generation) benchmark_generation=$2 ;;
        --ready-fps) ready_fps=$2 ;;
      esac
      shift 2
      ;;
    --package)
      # Compatibility spelling for callers that use the package to build a prefix.
      [ "$#" -ge 2 ] || { usage; exit 2; }
      [ -n "$layer_prefix" ] || layer_prefix="SurfaceView[$2"
      shift 2
      ;;
    *) usage; exit 2 ;;
  esac
done

case "$uid" in ''|*[!0-9]*) echo "invalid uid" >&2; exit 2 ;; esac
case "$artifact_id" in ''|*[!0-9a-fA-F]*) echo "invalid artifact id" >&2; exit 2 ;; esac
case "$device_id" in ''|*[!0-9a-fA-F]*) echo "invalid device id" >&2; exit 2 ;; esac
[ "${#artifact_id}" -eq 64 ] || { echo "artifact id must be SHA-256" >&2; exit 2; }
[ "${#device_id}" -eq 64 ] || { echo "device id must be SHA-256" >&2; exit 2; }
case "$pair_id" in ''|*[!a-zA-Z0-9._-]*) echo "invalid pair id" >&2; exit 2 ;; esac
case "$matrix_block" in ''|*[!a-zA-Z0-9._-]*) echo "invalid matrix block" >&2; exit 2 ;; esac
case "$row_order" in 0|1|2|3|4|5|6) : ;; *) echo "invalid row order" >&2; exit 2 ;; esac
case "$run_side" in parent|candidate) : ;; *) echo "invalid run side" >&2; exit 2 ;; esac
case "$display_refresh_hz" in ''|*[!0-9]*) echo "display refresh rate is required" >&2; exit 2 ;; esac
case "$benchmark_generation" in ''|*[!0-9]*) echo "benchmark generation is required" >&2; exit 2 ;; esac
[ "$benchmark_generation" -gt 0 ] || { echo "benchmark generation must be positive" >&2; exit 2; }
case "$ready_fps" in ''|*[!0-9.]*|.*|*.*.*) echo "ready FPS is required" >&2; exit 2 ;; esac
awk -v value="$ready_fps" 'BEGIN { exit(value > 0 ? 0 : 1) }' \
  || { echo "ready FPS must be positive" >&2; exit 2; }

[ -n "$layer" ] || [ -n "$layer_prefix" ] || {
  echo "one of --layer or --layer-prefix is required" >&2
  exit 2
}
[ -z "$layer" ] || [ -z "$layer_prefix" ] || {
  echo "--layer and --layer-prefix are mutually exclusive" >&2
  exit 2
}

# Real BLAST names contain brackets, parentheses, '#', '/', ':', '+', spaces and OEM suffixes.
# Reject control/separator characters that could make a dump record ambiguous, while accepting
# the complete printable layer charset used by SurfaceFlinger.
validate_layer() {
  value=$1
  [ -n "$value" ] || return 1
  [ "${#value}" -le 512 ] || return 1
  case "$value" in *"="*|*"|"*|*";"*|*"\`"*|*"$"*|*"\\"*) return 1 ;; esac
  printf '%s\n' "$value" | LC_ALL=C awk \
    'length($0) > 0 && $0 !~ /[^[:print:]]/ { ok=1 } END { exit(ok ? 0 : 1) }'
}
[ -z "$layer" ] || validate_layer "$layer" || { echo "invalid layer name" >&2; exit 2; }
[ -z "$layer_prefix" ] || validate_layer "$layer_prefix" || { echo "invalid layer prefix" >&2; exit 2; }

for snapshot in "$before" "$after"; do
  case "$snapshot" in *.txt|*.log) : ;; *) echo "snapshot must be a .txt or .log file" >&2; exit 2 ;; esac
  [ -f "$snapshot" ] || { echo "snapshot is missing" >&2; exit 2; }
done

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-timestats.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

# A prefix is only a convenience for fixtures with one matching paragraph.  Production scheduling
# resolves the exact active BLAST name from `dumpsys SurfaceFlinger --list` and passes --layer;
# selecting the largest historical TimeStats counter is deliberately not accepted.
resolve_layer() {
  snapshot=$1
  awk -v uid_value="$uid" -v prefix="$layer_prefix" '
    function value_for(record, key, lines, n, i, line) {
      n = split(record, lines, "\n")
      for (i = 1; i <= n; i++) {
        line = lines[i]
        if (line ~ "^[[:space:]]*" key "[[:space:]]*=") {
          sub("^[[:space:]]*" key "[[:space:]]*=[[:space:]]*", "", line)
          return line
        }
      }
      return ""
    }
    BEGIN { RS=""; match_count=0; match_name="" }
    {
      record=$0
      record_uid=value_for(record, "uid")
      name=value_for(record, "layerName")
      if (record_uid == uid_value && name != "" && index(name, prefix) == 1) {
        match_count++; match_name=name
      }
    }
    END { if (match_count != 1) exit 3; print match_name }
  ' "$snapshot"
}

if [ -n "$layer_prefix" ]; then
  layer=$(resolve_layer "$after") || {
    echo "could not resolve one unique warmed BLAST layer" >&2
    exit 1
  }
fi

# Select one exact API35 layer paragraph. packageName is deliberately not required: on API35 the
# app-owned SurfaceView record commonly has an empty packageName even though layerName embeds the
# package/activity. UID plus exact layerName is the authoritative linkage.
select_record() {
  snapshot=$1
  output=$2
  awk -v uid_value="$uid" -v wanted="$layer" -v wanted_refresh="$display_refresh_hz" '
    function value_for(record, key, lines, n, i, line) {
      n = split(record, lines, "\n")
      for (i = 1; i <= n; i++) {
        line = lines[i]
        if (line ~ "^[[:space:]]*" key "[[:space:]]*=") {
          sub("^[[:space:]]*" key "[[:space:]]*=[[:space:]]*", "", line)
          return line
        }
      }
      return ""
    }
    function normalize_rate(value) {
      sub(/[[:space:]]+fps.*/, "", value)
      if (value ~ /^[0-9]+(\.[0-9]+)?$/) return value + 0
      return -1
    }
    BEGIN { RS=""; found=0 }
    {
      record=$0
      refresh=value_for(record, "displayRefreshRate")
      if (value_for(record, "uid") == uid_value && value_for(record, "layerName") == wanted && (wanted_refresh == "" || normalize_rate(refresh) == wanted_refresh + 0)) {
        found++
        print record
      }
    }
    END { if (found != 1) exit 3 }
  ' "$snapshot" > "$output" || {
    echo "expected exactly one uid/layer timestats record" >&2
    exit 1
  }
}

# Extract counters and the complete present2present/presentToPresent histogram. AOSP emits the
# histogram as `0ms=0 16ms=600 ...`; some OEM builds use `0ms: 0`. Sum every bucket rather than
# trusting averageFPS or a single bucket. The cadence counters remove exactly two controlled
# warm-up boundary buckets (200ms and 1000ms); the remaining 599 intervals must follow the
# selected display-vsync lane and have a weighted rate near the row's exact nominal cadence.
# All other intervals must land in the narrow hardware-vsync lane, so a burst/stall pattern cannot
# hide behind a good endpoint average.
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
        line=lines[i]
        lower=tolower(line)
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
        # A layer paragraph contains several histograms.  Present-to-present buckets are the
        # only lines beginning with an integer millisecond bucket; stop before latch/desired/app
        # deadline histograms and named metrics rather than summing unrelated counters.
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
            if (millis == 200) {
              boundary200 += count
            } else if (millis == 1000) {
              boundary1000 += count
            } else {
              lane=0
              low_lane=int(want_rate / target_fps)
              if (low_lane < 1) low_lane=1
              high_lane=low_lane + 1
              low_target=1000 * low_lane / want_rate
              high_target=1000 * high_lane / want_rate
              if (millis >= low_target - 2 && millis <= low_target + 2) lane=low_lane
              if (millis >= high_target - 2 && millis <= high_target + 2) lane=high_lane
              if (lane > 0) {
                good += count
                vsync_total += count * lane
                interval_count += count
              }
              else {
                bad += count
                interval_count += count
              }
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
  ' "$input" > "$output"
}

select_record "$before" "$tmp_dir/before"
select_record "$after" "$tmp_dir/after"
extract_stats "$tmp_dir/before" "$tmp_dir/before.stats"
extract_stats "$tmp_dir/after" "$tmp_dir/after.stats"

read_stat() {
  key=$1
  file=$2
  value=$(awk -F= -v wanted="$key" '$1 == wanted { print $2; exit }' "$file")
  case "$value" in ''|*[!0-9]*) echo "missing or invalid $key" >&2; exit 1 ;; esac
  echo "$value"
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
    || [ "$delta_late" -lt 0 ] || [ "$delta_bad" -lt 0 ] \
    || [ "$delta_good" -lt 0 ] || [ "$delta_cadence_bad" -lt 0 ] \
    || [ "$delta_vsync_total" -lt 0 ] || [ "$delta_interval_count" -lt 0 ] \
    || [ "$delta_boundary_200" -lt 0 ] || [ "$delta_boundary_1000" -lt 0 ]; then
  echo "timestats counters decreased; baseline/after dumps are not comparable" >&2
  exit 1
fi

layer_id=$(printf '%s' "$layer" | {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum | awk '{print $1}';
  elif command -v shasum >/dev/null 2>&1; then shasum -a 256 | awk '{print $1}';
  else exit 1; fi
}) || { echo "SHA-256 tool is unavailable" >&2; exit 1; }

boundary_frames=1
adjusted_total=$((delta_total - boundary_frames))
boundary_intervals=2
adjusted_hist=$((delta_hist - boundary_intervals))
histogram_fps=$(awk -v count="$delta_interval_count" -v refresh="$display_refresh_hz" \
    -v vsyncs="$delta_vsync_total" 'BEGIN { if (vsyncs > 0) printf "%.6f", count * refresh / vsyncs; else print "0" }')
echo "event=compositor_result artifact_id=$artifact_id device_id=$device_id pair_id=$pair_id matrix_block=$matrix_block row_order=$row_order run_side=$run_side benchmark_generation=$benchmark_generation layer_id=$layer_id layer_uid=$uid display_refresh_hz=$display_refresh_hz raw_total_frames=$delta_total raw_histogram_frames=$delta_hist boundary_frames=$boundary_frames boundary_intervals=$boundary_intervals total_frames=$adjusted_total histogram_frames=$adjusted_hist present_interval_count=$delta_interval_count cadence_good_frames=$delta_good cadence_bad_frames=$delta_cadence_bad cadence_vsync_total=$delta_vsync_total cadence_boundary_200_frames=$delta_boundary_200 cadence_boundary_1000_frames=$delta_boundary_1000 cadence_max_gap_ms=$after_max_gap cadence_min_gap_ms=$(read_stat cadence_min_gap_ms "$tmp_dir/after.stats") compositor_histogram_fps=$histogram_fps dropped_frames=$delta_drop late_acquire_frames=$delta_late bad_desired_present_frames=$delta_bad measurement=surfaceflinger_timestats"

[ "$delta_total" -eq 601 ] \
  && [ "$delta_hist" -eq 601 ] \
  && [ "$adjusted_total" -eq 600 ] \
  && [ "$adjusted_hist" -eq 599 ] \
  && [ "$delta_interval_count" -eq 599 ] \
  && [ "$delta_good" -eq 599 ] \
  && [ "$delta_cadence_bad" -eq 0 ] \
  && [ "$delta_boundary_200" -eq 1 ] \
  && [ "$delta_boundary_1000" -eq 1 ] \
  && [ "$delta_vsync_total" -gt 0 ] \
  && awk -v actual="$histogram_fps" -v target="$ready_fps" \
       'BEGIN { exit(actual >= target * 0.99 && actual <= target * 1.01 ? 0 : 1) }' \
  && [ "$delta_drop" -eq 0 ] \
  && [ "$delta_late" -eq 0 ] \
  && [ "$delta_bad" -eq 0 ]
