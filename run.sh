#!/bin/bash

set -euo pipefail

# Always package the working tree first. Leaving several snapshot JARs in target/ is normal, and
# the old wildcard selected the first (often stale) one rather than the code the developer meant
# to run.
/opt/maven/bin/mvn -pl swing -am package -DskipTests

coffee_gb_jar="$(find swing/target -maxdepth 1 -type f -name 'coffee-gb-*-SNAPSHOT.jar' \
  -printf '%T@ %p\n' | sort -nr | head -n 1 | cut -d ' ' -f 2-)"
if [[ -z "$coffee_gb_jar" ]]; then
  echo "Coffee GB package was not produced." >&2
  exit 1
fi

exec java -Dcoffee-gb.desktop.proposal3-menu=true -jar "$coffee_gb_jar" "$@"
