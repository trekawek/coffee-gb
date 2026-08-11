#!/usr/bin/env bash
set -euo pipefail

umask 077

if [[ $# -ne 1 ]]; then
  echo 'error=usage' >&2
  exit 2
fi

rom_path=$1
script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
repo_root=$(git -C "$script_dir" rev-parse --show-toplevel)

if [[ ! -f $rom_path || ! -r $rom_path ]]; then
  echo 'error=rom_unreadable' >&2
  exit 2
fi

session_dir=$(mktemp -d /tmp/coffee-gb-play.XXXXXX)
echo "session_dir=$session_dir"
echo 'build_started=true'

cleanup_failed_setup() {
  local status=$?
  if [[ $status -ne 0 ]]; then
    rm -rf -- "$session_dir"
  fi
  return "$status"
}
trap cleanup_failed_setup EXIT

mvn -q -DskipTests -Dkotlin.compiler.daemon=false -pl cli -am package -f "$repo_root/pom.xml"
echo 'build_finished=true'

mkdir -m 700 -- "$session_dir/classes"
javac --release 16 \
  -cp "$repo_root/cli/target/coffee-gb-cli.jar" \
  -d "$session_dir/classes" \
  "$script_dir/PlayGbRom.java"

trap - EXIT
exec java \
  -Djava.awt.headless=true \
  -Dorg.slf4j.simpleLogger.defaultLogLevel=error \
  -cp "$session_dir/classes:$repo_root/cli/target/coffee-gb-cli.jar" \
  PlayGbRom "$rom_path" "$session_dir"
