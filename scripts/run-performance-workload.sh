#!/usr/bin/env bash
set -euo pipefail
repo_root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
cd "$repo_root"
classpath_file=$(mktemp)
trap 'rm -f "$classpath_file"' EXIT
/opt/maven/bin/mvn -q -pl core test-compile dependency:build-classpath \
  -DskipTests -Dmdep.outputFile="$classpath_file"
dependencies=$(cat "$classpath_file")
core_classes=${COFFEE_GB_CORE_CLASSES:-"$repo_root/core/target/classes"}
main=eu.rekawek.coffeegb.core.performance.PerformanceWorkloadMain
if [[ ${1:-} == export ]]; then
  shift
  main=eu.rekawek.coffeegb.core.performance.PerformanceFixtureMain
fi
java -cp "$core_classes:$repo_root/core/target/classes:$repo_root/core/target/test-classes:$dependencies" \
  "$main" "$@"
