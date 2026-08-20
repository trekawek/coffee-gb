#!/bin/sh
set -eu

root=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
tmp=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-timestats-test.XXXXXX")
trap 'rm -rf "$tmp"' EXIT HUP INT TERM

layer='SurfaceView[eu.rekawek.coffeegb.android/eu.rekawek.coffeegb.android.MainActivity](BLAST)#2374'
cat >"$tmp/before.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 100
displayRefreshRate = 120 fps
droppedFrames = 4
lateAcquireFrames = 1
badDesiredPresentFrames = 2
present2present histogram is as below:
0ms=0 16ms=100 8ms=0 200ms=0 1000ms=0
latch2present histogram is as below:
0ms=0 16ms=999
desired2present histogram is as below:
0ms=0 16ms=777
EOF
cat >"$tmp/after.txt" <<EOF
uid = 10327
layerName = SurfaceView[eu.rekawek.coffeegb.android/.MainActivity](BLAST)#old
packageName =
totalFrames = 101
displayRefreshRate = 120 fps
droppedFrames = 4
lateAcquireFrames = 1
badDesiredPresentFrames = 2
present2present histogram is as below:
0ms=0 8ms=23 16ms=676 200ms=1 1000ms=1
latch2present histogram is as below:
0ms=0 16ms=998
desired2present histogram is as below:
0ms=0 16ms=776

uid = 10327
layerName = $layer
packageName =
totalFrames = 701
displayRefreshRate = 120 fps
droppedFrames = 4
lateAcquireFrames = 1
badDesiredPresentFrames = 2
present2present histogram is as below:
0ms=0 8ms=23 16ms=676 200ms=1 1000ms=1
latch2present histogram is as below:
0ms=0 16ms=9999
desired2present histogram is as below:
0ms=0 16ms=8888
EOF

artifact=$(printf '%064d' 1)
device=$(printf '%064d' 2)

run_gate() {
  rate=$1
  nominal=$2
  before_file=$3
  after_file=$4
  "$root/surface-timestats-gate.sh" --uid 10327 --layer "$layer" \
    --display-refresh-hz "$rate" --ready-fps "$nominal" \
    --before "$before_file" --after "$after_file" \
    --artifact-id "$artifact" --device-id "$device" --pair-id p00-dmg \
    --matrix-block b00 --row-order 0 --run-side parent --benchmark-generation 1
}
output=$(
  "$root/surface-timestats-gate.sh" --uid 10327 \
    --layer "$layer" \
    --display-refresh-hz 120 \
    --ready-fps 61.1679 \
    --before "$tmp/before.txt" --after "$tmp/after.txt" \
    --artifact-id "$artifact" --device-id "$device" --pair-id p00-dmg \
    --matrix-block b00 --row-order 0 --run-side parent --benchmark-generation 1
)
case "$output" in
  *'raw_total_frames=601'*'raw_histogram_frames=601'*'boundary_frames=1'*'boundary_intervals=2'*'total_frames=600'*'histogram_frames=599'*'present_interval_count=599'*'cadence_good_frames=599'*'cadence_bad_frames=0'*'cadence_boundary_200_frames=1'*'cadence_boundary_1000_frames=1'* ) : ;;
  *) echo "unexpected compositor gate output" >&2; exit 1 ;;
esac

if "$root/surface-timestats-gate.sh" --uid 10327 --layer "$layer" \
    --display-refresh-hz 120 \
    --ready-fps 61.1679 \
    --before "$tmp/before.txt" --after "$tmp/before.txt" \
    --artifact-id "$artifact" --device-id "$device" --pair-id p00-dmg \
    --matrix-block b00 --row-order 0 --run-side parent --benchmark-generation 1 >/dev/null 2>&1; then
  echo "gate accepted a non-600 delta" >&2
  exit 1
fi

# A LEGACY 60 Hz row has a fractional producer/display relationship: 596 one-vsync buckets and
# three two-vsync buckets infer 602 display intervals over 599 presents.
cat >"$tmp/legacy-before.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 10
displayRefreshRate = 60 fps
droppedFrames = 0
lateAcquireFrames = 0
badDesiredPresentFrames = 0
present2present histogram is as below:
16ms=1 33ms=0 200ms=0 1000ms=0
EOF
cat >"$tmp/legacy-after.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 611
displayRefreshRate = 60 fps
droppedFrames = 0
lateAcquireFrames = 0
badDesiredPresentFrames = 0
present2present histogram is as below:
16ms=597 33ms=3 200ms=1 1000ms=1
EOF
legacy_output=$(run_gate 60 59.7275 "$tmp/legacy-before.txt" "$tmp/legacy-after.txt")
case "$legacy_output" in
  *'histogram_frames=599'*'present_interval_count=599'*'cadence_vsync_total=602'*) : ;;
  *) echo "legacy fractional cadence was rejected" >&2; exit 1 ;;
esac

# Accuracy/reference runs are allowed to be slower than real time.  At 23 FPS on a 60 Hz
# display, measured intervals occupy the adjacent two/three-vsync lanes; the inferred histogram
# cadence must follow the ready cadence instead of being rejected as an invalid compositor.
cat >"$tmp/accuracy-before.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 10
displayRefreshRate = 60 fps
droppedFrames = 0
lateAcquireFrames = 0
badDesiredPresentFrames = 0
present2present histogram is as below:
16ms=1 33ms=0 50ms=0 200ms=0 1000ms=0
EOF
cat >"$tmp/accuracy-after.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 611
displayRefreshRate = 60 fps
droppedFrames = 0
lateAcquireFrames = 0
badDesiredPresentFrames = 0
present2present histogram is as below:
16ms=1 33ms=234 50ms=365 200ms=1 1000ms=1
EOF
accuracy_output=$(run_gate 60 23.0 "$tmp/accuracy-before.txt" "$tmp/accuracy-after.txt")
case "$accuracy_output" in
  *'histogram_frames=599'*'present_interval_count=599'*'cadence_vsync_total=1563'*) : ;;
  *) echo "slow Accuracy compositor cadence was rejected" >&2; exit 1 ;;
esac

# SGB pinned to 120 Hz must reject a slow all-two-vsync stream (60 FPS vs 61.1679 nominal).
cat >"$tmp/slow-before.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 10
displayRefreshRate = 120 fps
droppedFrames = 0
lateAcquireFrames = 0
badDesiredPresentFrames = 0
present2present histogram is as below:
16ms=1 200ms=0 1000ms=0
EOF
cat >"$tmp/slow-after.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 611
displayRefreshRate = 120 fps
droppedFrames = 0
lateAcquireFrames = 0
badDesiredPresentFrames = 0
present2present histogram is as below:
16ms=600 200ms=1 1000ms=1
EOF
if run_gate 120 61.1679 "$tmp/slow-before.txt" "$tmp/slow-after.txt" >/dev/null 2>&1; then
  echo "slow compositor cadence was accepted" >&2
  exit 1
fi

# Alternating one-vsync/two-vsync burst-and-stall bins must fail the off-lane continuity check.
cat >"$tmp/burst-after.txt" <<EOF
uid = 10327
layerName = $layer
packageName =
totalFrames = 611
displayRefreshRate = 120 fps
droppedFrames = 0
lateAcquireFrames = 0
badDesiredPresentFrames = 0
present2present histogram is as below:
8ms=300 16ms=1 32ms=299 200ms=1 1000ms=1
EOF
if run_gate 120 61.1679 "$tmp/slow-before.txt" "$tmp/burst-after.txt" >/dev/null 2>&1; then
  echo "burst/stall cadence was accepted" >&2
  exit 1
fi

# Missing or extra raw/boundary records are ineligible even when endpoint counters look close.
sed 's/totalFrames = 611/totalFrames = 610/' "$tmp/slow-after.txt" >"$tmp/raw600-after.txt"
if run_gate 120 61.1679 "$tmp/slow-before.txt" "$tmp/raw600-after.txt" >/dev/null 2>&1; then
  echo "raw 600 compositor records were accepted" >&2
  exit 1
fi
sed 's/totalFrames = 611/totalFrames = 612/' "$tmp/slow-after.txt" >"$tmp/raw602-after.txt"
if run_gate 120 61.1679 "$tmp/slow-before.txt" "$tmp/raw602-after.txt" >/dev/null 2>&1; then
  echo "raw 602 compositor records were accepted" >&2
  exit 1
fi
sed 's/200ms=1 1000ms=1/200ms=2 1000ms=0/' "$tmp/slow-after.txt" >"$tmp/missing-boundary-after.txt"
if run_gate 120 61.1679 "$tmp/slow-before.txt" "$tmp/missing-boundary-after.txt" >/dev/null 2>&1; then
  echo "missing boundary was accepted" >&2
  exit 1
fi

# Exact uid/layer/refresh selection remains mandatory: a duplicate active paragraph is ambiguous.
cat "$tmp/slow-after.txt" "$tmp/slow-after.txt" >"$tmp/duplicate-after.txt"
if run_gate 120 61.1679 "$tmp/slow-before.txt" "$tmp/duplicate-after.txt" >/dev/null 2>&1; then
  echo "duplicate timestats paragraph was accepted" >&2
  exit 1
fi
echo "surface-timestats gate fixture: PASS"
