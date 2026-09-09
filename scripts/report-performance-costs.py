#!/usr/bin/env python3
"""Summarize diagnostic workload JSON/JSONL without copying private report metadata.

Usage: report-performance-costs.py report.json [reports.jsonl ...] --format markdown

Inputs must use opaque workload IDs. Only IDs, known profiles, numeric counters and known
diagnostic categories are exported. ROM paths, payloads, source labels and unknown fields
are ignored. Signatures describe emulated work, not measured CPU time or FPS acceptance.
"""

import argparse
from collections import Counter, defaultdict
import json
import math
from pathlib import Path
import re
import statistics
import sys


PROFILES = {
    "DMG", "MGB", "CGB", "CGB_X2", "CGB0", "CGB0_X2", "CGB_COMPAT",
    "CGB0_COMPAT", "SGB", "SGB2",
}
EXECUTIONS = {"SCALAR", "EPOCH", "HALT", "TRANSFER", "PHASE"}
BLOCKERS = {
    "BOOT", "OBSERVATION", "RESET", "CPU_STATE", "CPU_INTERRUPT", "CPU_PHASE",
    "DMA", "HDMA", "SERIAL", "INFRARED", "INPUT", "TIMER", "AUDIO", "CARTRIDGE",
    "LCD_OFF", "PROFILE", "PPU_OBSERVATION", "PPU_ALIAS", "PPU_DMA", "PPU_LATCH",
    "PPU_FIRST_LINE", "PPU_PROFILE", "PPU_OUTPUT", "PPU_CHECKPOINT", "PPU_LINE_END",
    "STAT", "EVENT_BOUNDARY", "SERIAL_UNBOUNDED_ENDPOINT",
}
FENCES = {
    "UNKNOWN", "ROM_CONTROL", "VRAM", "CARTRIDGE", "OAM", "JOYPAD", "SERIAL",
    "TIMER", "INTERRUPT", "SOUND", "STAT", "LY", "PPU", "OTHER_IO", "RAM",
}


def number(value, default=0):
    if isinstance(value, (int, float)) and not isinstance(value, bool):
        if 0 <= value <= 2**63 - 1 and math.isfinite(value):
            return value
    return default


def known_counts(value, allowed):
    if not isinstance(value, dict):
        return {}
    return {key: number(count) for key, count in value.items()
            if key in allowed and number(count) > 0}


def ratio(numerator, denominator):
    return numerator / denominator if denominator else 0.0


def summarize_record(record):
    """Return a sanitized observation, or None for a non-workload/invalid record."""
    if not isinstance(record, dict) or record.get("schema") != "coffee-gb-performance-v1":
        return None
    identifier = record.get("opaque_id", record.get("id"))
    if not isinstance(identifier, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", identifier):
        return None
    profile = record.get("profile")
    if not isinstance(profile, str) or profile not in PROFILES:
        return None
    ticks = number(record.get("ticks"))
    nominal = number(record.get("nominal_ticks_per_second"))
    if ticks <= 0 or nominal <= 0:
        return None
    result = {"id": identifier, "profile": profile, "speed": record.get("speed", 1),
              "ticks": ticks, "metrics": {}, "signatures": [], "blockers": {},
              "fences": {}, "combinations": {}, "warnings": []}
    if result["speed"] not in (1, 2):
        return None
    if record.get("diagnostics_enabled") is not True:
        result["signatures"] = ["diagnostics_missing"]
        return result

    execution = known_counts(record.get("execution_ticks"), EXECUTIONS)
    if sum(execution.values()) != ticks:
        result["warnings"].append("execution_tick_accounting_mismatch")
    scalar = execution.get("SCALAR", 0)
    subsystem = record.get("subsystem_ticks")
    if not isinstance(subsystem, dict):
        subsystem = {}
    direct = number(record.get("direct_lines"))
    rejected = number(record.get("rejected_lines"))
    histogram = record.get("span_histogram", [])
    if not isinstance(histogram, list):
        histogram = []
    histogram = [number(count) for count in histogram[:65]]
    calls = sum(histogram[1:])
    small_calls = sum(histogram[1:4])
    small_ticks = sum(index * histogram[index] for index in range(1, min(4, len(histogram))))
    windows = record.get("windows", [])
    if not isinstance(windows, list):
        windows = []
    scalar_window_streak = 0
    longest_window_streak = 0
    full_windows = 0
    scalar_heavy_windows = 0
    for window in windows:
        if not isinstance(window, dict):
            continue
        size = number(window.get("ticks"))
        # The workload runner configures one emulated second per diagnostics window.
        # A short final window must not make a transient tail look sustained.
        if size < math.ceil(nominal):
            continue
        full_windows += 1
        heavy = ratio(number(window.get("scalar")), size) >= 0.5
        scalar_heavy_windows += int(heavy)
        scalar_window_streak = scalar_window_streak + 1 if heavy else 0
        longest_window_streak = max(longest_window_streak, scalar_window_streak)

    metrics = result["metrics"]
    metrics.update({
        "scalar_share": scalar / ticks,
        "epoch_share": execution.get("EPOCH", 0) / ticks,
        "halt_transfer_share": (execution.get("HALT", 0) + execution.get("TRANSFER", 0)) / ticks,
        "phase_share": execution.get("PHASE", 0) / ticks,
        "rejected_line_share": ratio(rejected, direct + rejected),
        "observed_lines": direct + rejected,
        "longest_rejected_line_run": number(record.get("longest_rejected_line_run")),
        "mean_span_ticks": ratio(ticks, calls),
        "small_span_call_share": ratio(small_calls, calls),
        "small_span_tick_share": small_ticks / ticks,
        "longest_scalar_seconds": number(record.get("longest_scalar_run")) / nominal,
        "full_windows_retained": full_windows,
        "scalar_heavy_window_share": ratio(scalar_heavy_windows, full_windows),
        "consecutive_scalar_heavy_windows": longest_window_streak,
        "overflow_combinations": number(record.get("overflow_combinations")),
    })
    for counter, metric in (("PPU_REPLAY", "ppu_replay_per_tick"),
                            ("STAT_REPLAY", "stat_replay_per_tick"),
                            ("AUDIO_MATERIALIZED", "audio_materialized_per_tick"),
                            ("DMA_REPLAY", "dma_replay_per_tick"),
                            ("DMA_BATCHED_COPY", "dma_batched_copy_per_tick"),
                            ("PPU_QUIET_AFTER_REPLAY", "ppu_quiet_after_replay_per_tick"),
                            ("PPU_QUIET_STEADY", "ppu_quiet_steady_per_tick"),
                            ("PPU_QUIET_DURING_DMA", "ppu_quiet_during_dma_per_tick")):
        count = number(subsystem.get(counter), None)
        if count is None:
            # Earlier snapshots may predate a counter; unavailable is not measured zero.
            result["warnings"].append("missing_subsystem_counter_" + counter)
        else:
            metrics[metric] = count / ticks
    signatures = result["signatures"]
    for key, label in (("ppu_replay_per_tick", "ppu_replay"),
                       ("stat_replay_per_tick", "stat_replay"),
                       ("audio_materialized_per_tick", "audio_materialization"),
                       ("dma_replay_per_tick", "dma_replay"),
                       ("dma_batched_copy_per_tick", "dma_batched_copy"),
                       ("rejected_line_share", "line_rejection")):
        if metrics.get(key, 0) >= 0.25:
            signatures.append(label)
    if metrics["longest_rejected_line_run"] >= 288:
        signatures.append("long_rejected_line_run")
    if metrics["small_span_call_share"] >= 0.75 and 0 < metrics["mean_span_ticks"] <= 4:
        signatures.append("small_spans")
    if metrics["scalar_share"] >= 0.5:
        signatures.append("scalar_dominated")
    if metrics["longest_scalar_seconds"] >= 0.1:
        signatures.append("long_scalar_run")
    if longest_window_streak >= 2:
        signatures.append("sustained_scalar_windows")
    if metrics["halt_transfer_share"] >= 0.5:
        signatures.append("halt_transfer_dominated")
    if not signatures:
        signatures.append("no_large_signature")
    signatures.sort()
    result["blockers"] = known_counts(record.get("blockers"), BLOCKERS)
    result["fences"] = known_counts(record.get("fences"), FENCES)
    combinations = record.get("rejection_combinations", [])
    if isinstance(combinations, list):
        for combination in combinations:
            if not isinstance(combination, dict) or not isinstance(combination.get("reasons"), list):
                continue
            reasons = combination["reasons"]
            # Dropping an unknown reason would incorrectly relabel a simultaneous rejection.
            if not reasons or any(not isinstance(reason, str) or reason not in BLOCKERS for reason in reasons):
                continue
            key = tuple(sorted(set(reasons)))
            result["combinations"][key] = result["combinations"].get(key, 0) + number(combination.get("count"))
    return result


def aggregate(records):
    groups = defaultdict(list)
    ignored = 0
    for record in records:
        observation = summarize_record(record)
        if observation is None:
            ignored += 1
            continue
        groups[(observation["id"], observation["profile"], observation["speed"])].append(observation)
    workloads = []
    clusters = defaultdict(list)
    for (identifier, profile, speed), observations in sorted(groups.items()):
        diagnostic = [item for item in observations if item["metrics"]]
        signatures = sorted(set(label for item in observations for label in item["signatures"]))
        metrics = {}
        metric_runs = {}
        for key in sorted(set(key for item in diagnostic for key in item["metrics"])):
            values = [item["metrics"][key] for item in diagnostic if key in item["metrics"]]
            metrics[key] = {"median": statistics.median(values), "min": min(values), "max": max(values)}
            metric_runs[key] = len(values)
        workload = {"id": identifier, "profile": profile, "speed": speed,
                    "runs": len(observations), "diagnostic_runs": len(diagnostic),
                    "signatures": signatures, "metrics": metrics, "metric_runs": metric_runs,
                    "warnings": sorted(set(w for item in observations for w in item["warnings"]))}
        ticks = sum(item["ticks"] for item in diagnostic)
        for category in ("blockers", "fences", "combinations"):
            counts = Counter()
            for item in diagnostic:
                counts.update(item[category])
            workload[category] = [
                {"reasons" if category == "combinations" else "reason": list(key) if isinstance(key, tuple) else key,
                 "attempts": count, "attempts_per_million_ticks": ratio(count * 1_000_000, ticks)}
                for key, count in sorted(counts.items(), key=lambda item: (-item[1], item[0]))[:8]
            ]
        workloads.append(workload)
        clusters[tuple(signatures)].append({"id": identifier, "profile": profile, "speed": speed})
    return {
        "schema": "coffee-gb-performance-signatures-v1", "ignored_records": ignored,
        "notes": [
            "Signatures are heuristic work categories, not measured CPU time or acceptance gates.",
            "Replay counters can overlap and exceed one replay tick per master tick; do not add them as time shares.",
            "PPU quiet-during-DMA counts retained batching independently of the remaining exact DMA replay work.",
            "DMA_BATCHED_COPY records the copy stride separately from per-dot DMA_REPLAY; older snapshots predate the stride and its counter.",
            "PPU_QUIET_AFTER_REPLAY records recovered batching after an exact dot; it is coverage, not a cost or failure signature.",
            "PPU_QUIET_STEADY records an already-proven exact mode-3 continuation; it is coverage, not a cost or failure signature.",
            "Blocker/fence counts are attempts, not elapsed ticks. Signatures are the union observed across repeated runs.",
            "Small-span calls include scalar calls. Windows retain only the runner's bounded recent history.",
            "Absent subsystem counters are unavailable, not zero; metric_runs records each metric's observation count.",
            "Only explicitly supplied opaque IDs and known diagnostic fields are exported; throughput timings are omitted.",
        ],
        "clusters": [{"signatures": list(key), "workloads": values}
                     for key, values in sorted(clusters.items(), key=lambda item: (-len(item[1]), item[0]))],
        "workloads": workloads,
    }


def parse_reports(contents):
    try:
        value = json.loads(contents)
        return value if isinstance(value, list) else [value]
    except (ValueError, RecursionError):
        values = []
        for line in contents.splitlines():
            if not line.strip():
                continue
            try:
                values.append(json.loads(line))
            except (ValueError, RecursionError):
                values.append(None)
        return values


def markdown(report):
    lines = ["Diagnostic work signatures; these counters do not measure CPU time.", "",
             "| ID / profile | Signatures | Scalar | PPU replay / T | STAT replay / T | Rejected lines | Mean span | Longest scalar |",
             "|---|---|---:|---:|---:|---:|---:|---:|"]
    for workload in report["workloads"]:
        metrics = workload["metrics"]
        def value(key, scale=1, suffix=""):
            return f"{metrics[key]['median'] * scale:.3g}{suffix}" if key in metrics else "—"
        cells = [f"{workload['id']} / {workload['profile']} ×{workload['speed']}",
                 ", ".join(workload["signatures"]), value("scalar_share", 100, "%"),
                 value("ppu_replay_per_tick"), value("stat_replay_per_tick"),
                 value("rejected_line_share", 100, "%"), value("mean_span_ticks"),
                 value("longest_scalar_seconds", 1000, " ms")]
        lines.append("| " + " | ".join(cells) + " |")
    lines.extend(["", f"Ignored records: {report['ignored_records']}.", ""])
    lines.extend("- " + note for note in report["notes"])
    return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reports", nargs="+", type=Path)
    parser.add_argument("--format", choices=("json", "markdown"), default="json")
    args = parser.parse_args()
    records = []
    for index, path in enumerate(args.reports):
        try:
            records.extend(parse_reports(path.read_text()))
        except (OSError, UnicodeError):
            # Input names can be private; neither names nor exception messages are echoed.
            print(f"Unable to read report input {index + 1}", file=sys.stderr)
            return 1
    report = aggregate(records)
    print(markdown(report) if args.format == "markdown" else json.dumps(report, indent=2), end="\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
