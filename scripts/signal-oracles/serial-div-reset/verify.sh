#!/bin/sh
# SPDX-License-Identifier: MIT
set -eu

expected_revision=ee559e1d963e1cc522df512e3bae1b4e5ff96fb5
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
oracle_dmg=${ORACLE_DMG:-/tmp/coffee-gb-dmg-sim}
oracle_iverilog=${ORACLE_IVERILOG:-/tmp/coffee-gb-iverilog-master/bin/iverilog}
oracle_vvp=${ORACLE_VVP:-/tmp/coffee-gb-iverilog-master/bin/vvp}

actual_revision=$(git -C "$oracle_dmg" rev-parse HEAD)
if [ "$actual_revision" != "$expected_revision" ]; then
    echo "Expected dmg-sim $expected_revision, found $actual_revision" >&2
    exit 1
fi

if ! git -C "$oracle_dmg" diff --quiet HEAD -- Makefile dmg_cpu_b sm83 keeper.sv timescale.f; then
    echo "The dmg-sim sources used by this oracle must be clean" >&2
    exit 1
fi

test -x "$oracle_iverilog"
test -x "$oracle_vvp"
expected_iverilog='Icarus Verilog version 14.0 (devel) (1d2aa1b)'
actual_iverilog=$($oracle_iverilog -V 2>&1 | sed -n '1p')
if [ "$actual_iverilog" != "$expected_iverilog" ]; then
    echo "Expected '$expected_iverilog', found '$actual_iverilog'" >&2
    exit 1
fi

oracle_tmp=$(mktemp -d "${TMPDIR:-/tmp}/coffee-gb-serial-oracle.XXXXXX")
trap 'rm -rf "$oracle_tmp"' EXIT HUP INT TERM

make --no-print-directory -s -C "$oracle_dmg" -B -f "$script_dir/SerialFullCone.mk" \
    ORACLE_DMG="$oracle_dmg" \
    IVERILOG="$oracle_iverilog" \
    SERIAL_ORACLE_TB="$script_dir/serial_full_cone_tb.sv" \
    SERIAL_ORACLE_OUT="$oracle_tmp/serial_full_cone.vvp" \
    "$oracle_tmp/serial_full_cone.vvp"

raw_output=$($oracle_vvp -N "$oracle_tmp/serial_full_cone.vvp")
printf '%s\n' "$raw_output"
output=$(printf '%s\n' "$raw_output" | sed -n '/^FULL_/p')

expected='FULL_CASE_A stage_high sck_high -> toggle_to_sck_low shift=1
FULL_CASE_B stage_high sck_low -> toggle_to_sck_high shift=0
FULL_CASE_C stage_low -> no_toggle shift=0
FULL_PASS exact dmg_cpu_b hierarchy'
if [ "$output" != "$expected" ]; then
    echo "Unexpected serial DIV-reset oracle output" >&2
    exit 1
fi
