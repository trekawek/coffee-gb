#!/bin/sh
set -eu

# Invoke the real strict goal-matrix JVM parser.  This wrapper deliberately has no permissive
# fallback: if the parser source cannot be compiled, the report is rejected.

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
PARSER_SOURCE=$SCRIPT_DIR/app/src/test/java/eu/rekawek/coffeegb/android/BenchmarkGoalMatrix.java
WORKLOAD_SOURCE=$SCRIPT_DIR/app/src/main/java/eu/rekawek/coffeegb/android/BenchmarkWorkload.java

usage() {
  echo "usage: benchmark-goal-matrix-report.sh --input <redacted.log>" >&2
  echo "       [--parent-id <sha256> --candidate-id <sha256>]" >&2
}
fatal() {
  echo "benchmark-goal-matrix-report: $1" >&2
  exit 1
}

input=
parent_id=
candidate_id=
while [ "$#" -gt 0 ]; do
  case "$1" in
    --input)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      input=$2
      shift 2
      ;;
    --parent-id)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      parent_id=$2
      shift 2
      ;;
    --candidate-id)
      [ "$#" -ge 2 ] || { usage; exit 2; }
      candidate_id=$2
      shift 2
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *) usage; exit 2 ;;
  esac
done
[ -n "$input" ] || { usage; exit 2; }
case "$input" in *.log|*.txt) : ;; *) fatal "input must be a redacted log" ;; esac
[ -f "$input" ] && [ -r "$input" ] || fatal "input log is unavailable"

case "$parent_id" in
  '') : ;;
  *[!0-9a-fA-F]*) fatal "parent identity is malformed" ;;
  *) [ "${#parent_id}" -eq 64 ] || fatal "parent identity is malformed" ;;
esac
case "$candidate_id" in
  '') : ;;
  *[!0-9a-fA-F]*) fatal "candidate identity is malformed" ;;
  *) [ "${#candidate_id}" -eq 64 ] || fatal "candidate identity is malformed" ;;
esac
if [ -n "$parent_id" ] && [ -n "$candidate_id" ] && [ "$parent_id" = "$candidate_id" ]; then
  fatal "parent and candidate identities must differ"
fi
[ -z "$parent_id" ] || parent_norm=$(printf '%s\n' "$parent_id" | tr 'A-F' 'a-f')
[ -z "$candidate_id" ] || candidate_norm=$(printf '%s\n' "$candidate_id" | tr 'A-F' 'a-f')
[ -z "${parent_norm-}" ] || [ -z "${candidate_norm-}" ] || [ "${parent_norm-}" != "${candidate_norm-}" ] \
  || fatal "parent and candidate identities must differ"
[ -f "$PARSER_SOURCE" ] || fatal "goal parser source is unavailable"
[ -f "$WORKLOAD_SOURCE" ] || fatal "goal workload source is unavailable"

# The Java parser checks SHA-256 syntax and campaign structure, while this host wrapper binds
# each side's final artifact to the exact APK selected by the scheduler.  Keeping this check
# outside the parser also prevents a future parser schema extension from accidentally accepting
# a swapped parent/candidate artifact.  The check emits no IDs or private evidence.
if [ -n "$parent_id" ] || [ -n "$candidate_id" ]; then
  awk -v parent="${parent_norm-}" -v candidate="${candidate_norm-}" '
    function field(name, i, value) {
      for (i=1; i<=NF; i++) if (index($i,name "=")==1) {
        value=$i; sub("^" name "=", "", value); return value
      }
      return ""
    }
    $0 ~ /(^|[[:space:]])event=final_result([[:space:]]|$)/ {
      side=field("run_side"); artifact=tolower(field("artifact_id"))
      if (side == "parent" && parent != "") { parent_count++; if (artifact != parent) bad=1 }
      else if (side == "candidate" && candidate != "") { candidate_count++; if (artifact != candidate) bad=1 }
      else if (side != "parent" && side != "candidate") bad=1
      if (artifact !~ /^[0-9a-f]{64}$/) bad=1
    }
    END {
      if (parent != "" && parent_count == 0) bad=1
      if (candidate != "" && candidate_count == 0) bad=1
      exit bad ? 1 : 0
    }
  ' "$input" || fatal "final_result artifact identity does not match its run side"
fi

tmp_dir=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-goal-report.XXXXXX")
trap 'rm -rf "$tmp_dir"' EXIT HUP INT TERM

# Check the paired performance claim independently of parser internals.  A slow parent remains a
# valid control (its compositor gate is structural), but each candidate must clear the absolute
# ready/submission floor and must not regress more than 0.5% against its own cell's parent.  The
# compact report is deliberately keyed only by the public cell id; no ROM/path/title/hash data is
# printed.
pair_report=$tmp_dir/pairs.txt
awk '
  function field(name, i, value) {
    for (i=1; i<=NF; i++) if (index($i,name "=")==1) {
      value=$i; sub("^" name "=", "", value); return value
    }
    return ""
  }
  $0 ~ /(^|[[:space:]])event=final_result([[:space:]]|$)/ {
    cell=field("cell_id"); side=field("run_side")
    ready=field("ready_interval_fps"); submit=field("submission_interval_fps")
    if (cell == "" || (side != "parent" && side != "candidate")) bad=1
    if (ready !~ /^[0-9]+([.][0-9]+)?$/ || submit !~ /^[0-9]+([.][0-9]+)?$/) bad=1
    key=cell SUBSEP side
    if (ready_by[key] != "" || submit_by[key] != "") bad=1
    ready_by[key]=ready; submit_by[key]=submit
    if (!(cell in seen)) cell_count++
    seen[cell]=1
  }
  END {
    for (cell in seen) {
      pk=cell SUBSEP "parent"; ck=cell SUBSEP "candidate"
      if (ready_by[pk] == "" || ready_by[ck] == "" || submit_by[pk] == "" || submit_by[ck] == "") { bad=1; continue }
      floor=(cell == "d-sgb" || cell == "u-sgb") ? 60.556221 : 59.130225
      rr=ready_by[ck] / ready_by[pk]; sr=submit_by[ck] / submit_by[pk]
      if (ready_by[ck] < floor || submit_by[ck] < floor || rr < 0.995 || sr < 0.995) bad=1
      printf "cell=%s ready_ratio=%.6f submission_ratio=%.6f candidate_ready_fps=%s candidate_submission_fps=%s\n", cell, rr, sr, ready_by[ck], submit_by[ck]
    }
    if (cell_count != 8) bad=1
    exit bad ? 1 : 0
  }
' "$input" >"$pair_report" || fatal "paired candidate performance evidence is incomplete or below floor"

# These compile-only declarations satisfy the two Android-type references in the real workload.
# They are not a parser implementation and are never retained outside this private build.
stub_dir=$tmp_dir/stubs/eu/rekawek/coffeegb/android
mkdir -p "$stub_dir"
cat >"$stub_dir/DiagnosticsOptions.java" <<'EOF'
package eu.rekawek.coffeegb.android;
final class DiagnosticsOptions {
    enum Hardware {
        DMG("dmg"), CGB("cgb"), SGB("sgb");
        private final String value;
        Hardware(String value) { this.value = value; }
        String externalValue() { return value; }
    }
}
EOF
cat >"$stub_dir/BenchmarkGameplayScenario.java" <<'EOF'
package eu.rekawek.coffeegb.android;
final class BenchmarkGameplayScenario {
    static final int NONE_MASK = 0;
    static final int RIGHT_MASK = 1 << 0;
    static final int A_MASK = 1 << 4;
    static final int B_MASK = 1 << 5;
    static final int START_MASK = 1 << 7;
}
EOF

adapter=$tmp_dir/GoalMatrixReportMain.java
cat >"$adapter" <<'EOF'
package eu.rekawek.coffeegb.android;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
public final class GoalMatrixReportMain {
    private GoalMatrixReportMain() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 1 && args.length != 3) {
            System.err.println("goal parser input is required");
            System.exit(2);
            return;
        }
        List<String> lines = Files.readAllLines(Path.of(args[0]), StandardCharsets.UTF_8);
        BenchmarkGoalMatrix.Report report = args.length == 3
                ? BenchmarkGoalMatrix.parse(lines, args[1], args[2])
                : BenchmarkGoalMatrix.parse(lines);
        System.out.println("accepted=" + report.accepted());
        System.out.println("run_count=" + report.runCount());
        if (!report.accepted()) {
            for (String error : report.errors()) {
                System.out.println("error=" + error.replace(' ', '_'));
            }
            System.exit(1);
        }
    }
}
EOF

classes=$tmp_dir/classes
mkdir -p "$classes"
javac -d "$classes" -sourcepath "$tmp_dir/stubs" \
  "$stub_dir/DiagnosticsOptions.java" "$stub_dir/BenchmarkGameplayScenario.java" \
  "$WORKLOAD_SOURCE" "$PARSER_SOURCE" "$adapter" \
  >/dev/null 2>"$tmp_dir/javac.err" || fatal "goal parser compilation failed"
if [ -n "$parent_id" ] || [ -n "$candidate_id" ]; then
  java -cp "$classes" eu.rekawek.coffeegb.android.GoalMatrixReportMain "$input" \
    "${parent_norm-}" "${candidate_norm-}"
else
  java -cp "$classes" eu.rekawek.coffeegb.android.GoalMatrixReportMain "$input"
fi
cat "$pair_report"
