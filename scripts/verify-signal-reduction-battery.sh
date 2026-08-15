#!/bin/sh
# SPDX-License-Identifier: MIT
set -eu

maven=${MAVEN:-/opt/maven/bin/mvn}
threads=${INTEGRATION_TEST_THREADS:-2}

run() {
    echo "+ $*"
    "$@"
}

# The controller reactor leg also runs the complete core unit suite.
run "$maven" -pl controller -am test

run "$maven" -pl core test \
    -Ptest-mooneye,test-dmgacid2,test-cgbacid2 \
    -Dintegration.test.threadCount="$threads"
run "$maven" -pl core test \
    -Ptest-blargg-individual,test-blargg \
    -Dintegration.test.threadCount="$threads"
run "$maven" -pl core test \
    -Ptest-samesuite,test-mealybug \
    -Dintegration.test.threadCount="$threads"
run "$maven" -pl core test \
    -Ptest-gambatte-hw,test-gbmicrotest \
    -Dintegration.test.threadCount="$threads"
run "$maven" -pl core test \
    -Ptest-gbc-hw,test-misc-gb,test-daid \
    -Dintegration.test.threadCount="$threads"
run "$maven" -pl core test \
    -Ptest-rtc3,test-mbc30,test-cgbacidhell,test-strikethrough,test-casualpokeplayer,test-bullygb \
    -Dintegration.test.threadCount="$threads"
