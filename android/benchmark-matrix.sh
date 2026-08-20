#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
source_file="$script_dir/app/src/test/java/eu/rekawek/coffeegb/android/BenchmarkMatrix.java"
output_dir=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-matrix.XXXXXX")
trap 'rm -rf "$output_dir"' EXIT HUP INT TERM

javac -d "$output_dir" "$source_file"

usage() {
  echo "usage: benchmark-matrix.sh --parent-apk <apk-or-sha256>"
  echo "       --candidate-apk <apk-or-sha256> [redacted.log|redacted.txt|-|--adb]" >&2
}

parent_apk=
candidate_apk=
input=
input_set=false
while [ "$#" -gt 0 ]; do
  case "$1" in
    --parent-apk)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      parent_apk=$2
      shift 2
      ;;
    --candidate-apk)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      candidate_apk=$2
      shift 2
      ;;
    --adb)
      [ "$input_set" = false ] || { usage; exit 2; }
      input=--adb
      input_set=true
      shift
      ;;
    -|*.log|*.txt)
      [ "$input_set" = false ] || { usage; exit 2; }
      input=$1
      input_set=true
      shift
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

[ -n "$parent_apk" ] && [ -n "$candidate_apk" ] || { usage; exit 2; }
[ "$input_set" = true ] || input=-

if [ "$input" = --adb ]; then
  adb logcat -d -v threadtime -s CoffeeGbBench:I '*:S' \
    | java -cp "$output_dir" eu.rekawek.coffeegb.android.BenchmarkMatrix \
        --parent-apk "$parent_apk" --candidate-apk "$candidate_apk" -
else
  java -cp "$output_dir" eu.rekawek.coffeegb.android.BenchmarkMatrix \
    --parent-apk "$parent_apk" --candidate-apk "$candidate_apk" "$input"
fi
