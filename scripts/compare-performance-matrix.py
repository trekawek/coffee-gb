#!/usr/bin/env python3
"""Compare pinned builds on authored workloads, with separate diagnostic JVMs and raw receipts.

This script never builds the project or reads an external ROM. Give it already compiled,
immutable classpaths and the corresponding source/build receipts. Run only on a quiet host.
"""

import argparse
import csv
import datetime
import hashlib
import json
import math
import os
from pathlib import Path
import re
import statistics
import subprocess
import time


SCHEMA = "coffee-gb-authored-performance-matrix-v1"
VARIANTS = ("baseline", "candidate", "diagnostics")
COLUMNS = ("variant", "round", "scenario", "profile", "ticks", "host_ns", "frames",
           "epoch_ticks", "bulk_ticks")
FIXED_POLICY = {"bootstrap": "SKIP", "execution": "PERFORMANCE", "rtc_millis": 0,
                "battery_persistence": False, "held_input": ["A", "RIGHT"]}
MAIN = "eu.rekawek.coffeegb.core.performance.PerformanceMatrixMain"


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def now():
    return datetime.datetime.now(datetime.timezone.utc).isoformat()


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2) + "\n")


def load_manifest(path):
    data = json.loads(Path(path).read_text())
    if data.get("schema") != SCHEMA:
        raise ValueError("Unsupported matrix schema")
    if not re.fullmatch(r"[A-Za-z0-9_-]+", data.get("id", "")):
        raise ValueError("Invalid authored matrix ID")
    policy = data["protocol"]
    for name, expected in FIXED_POLICY.items():
        if policy.get(name) != expected:
            raise ValueError("Unsupported matrix policy: " + name)
    for name in ("trials", "measured_ticks", "diagnostic_window_ticks"):
        if type(policy.get(name)) is not int or policy[name] <= 0:
            raise ValueError("Invalid matrix protocol: " + name)
    if type(policy.get("warmup_ticks")) is not int or policy["warmup_ticks"] < 0:
        raise ValueError("Invalid warmup budget")
    if type(policy.get("shuffle_seed")) is not int:
        raise ValueError("Invalid shuffle seed")
    if policy.get("jvm_flags") != ["-Xms256m", "-Xmx1g", "-XX:+UseSerialGC"]:
        raise ValueError("Unexpected JVM policy; define a separately reviewed protocol")
    cases = data["cases"]
    if not isinstance(cases, list) or not cases:
        raise ValueError("Empty authored case inventory")
    keys = set()
    for case in cases:
        if set(case) != {"scenario", "profile"} or any(
                not re.fullmatch(r"[A-Z][A-Z_0-9]*", str(value)) for value in case.values()):
            raise ValueError("Invalid authored scenario/profile")
        key = (case["scenario"], case["profile"])
        if key in keys:
            raise ValueError("Duplicate authored scenario/profile")
        keys.add(key)
    return data


def inventory_classpath(classpath_file):
    """Hash code entries only. ROM resources, saves and arbitrary directory files are excluded."""
    source = Path(classpath_file).resolve(strict=True)
    text = source.read_text().strip()
    entries = text.split(os.pathsep)
    if not entries or any(not item or not Path(item).is_absolute() for item in entries):
        raise ValueError("Classpath entries must be nonempty absolute paths")
    inventory = []
    for item in entries:
        path = Path(item).resolve(strict=True)
        if path.is_dir():
            classes = sorted(path.rglob("*.class"))
            if not classes:
                raise ValueError("Classpath directory contains no compiled classes")
            files = {entry.relative_to(path).as_posix(): sha256(entry) for entry in classes}
            inventory.append({"path": str(path), "classes": files})
        elif path.is_file() and path.suffix.lower() == ".jar":
            inventory.append({"path": str(path), "jar_sha256": sha256(path)})
        else:
            raise ValueError("Classpath must contain compiled class directories or JARs")
    return text, {"classpath_file": str(source), "classpath_sha256": sha256(source),
                  "entries": inventory}


def summarize(rows, manifest):
    """Reject incomplete or mismatched work before computing paired timing ratios."""
    protocol = manifest["protocol"]
    expected = {(case["scenario"], case["profile"], trial)
                for case in manifest["cases"] for trial in range(protocol["trials"])}
    groups = {}
    for row in rows:
        if set(row) != set(COLUMNS):
            raise ValueError("Unexpected measurement columns")
        key = (row["scenario"], row["profile"], int(row["round"]))
        variant = row["variant"]
        if key not in expected or variant not in VARIANTS:
            raise ValueError("Measurement is outside the declared matrix")
        pair = groups.setdefault(key, {})
        if variant in pair:
            raise ValueError("Duplicate measurement row")
        values = {name: int(row[name]) for name in COLUMNS[4:]}
        if values["ticks"] != protocol["measured_ticks"] or values["host_ns"] <= 0:
            raise ValueError("Wrong tick budget or nonpositive duration")
        if any(values[name] < 0 for name in ("frames", "epoch_ticks", "bulk_ticks")):
            raise ValueError("Negative frame/execution accounting")
        if any(values[name] > values["ticks"] for name in ("epoch_ticks", "bulk_ticks")):
            raise ValueError("Execution accounting exceeds measured work")
        if values["epoch_ticks"] + values["bulk_ticks"] > values["ticks"]:
            raise ValueError("Disjoint execution accounting exceeds measured work")
        pair[variant] = values
    if set(groups) != expected or any(set(pair) != set(VARIANTS) for pair in groups.values()):
        raise ValueError("Incomplete matrix or variant triplet")
    cases = {}
    for (scenario, profile, trial), pair in sorted(groups.items()):
        # Different trials can legitimately start their measured window at different frame
        # phases. Compare the same scene and trial across variants, never all rows together.
        if len({row["frames"] for row in pair.values()}) != 1:
            raise ValueError("Frame mismatch for " + str((scenario, profile, trial)))
        for metric in ("epoch_ticks", "bulk_ticks"):
            if pair["candidate"][metric] != pair["diagnostics"][metric]:
                raise ValueError("Diagnostics changed execution accounting: " + metric)
        case = cases.setdefault((scenario, profile), [])
        case.append({"trial": trial,
                     "speedup": pair["baseline"]["host_ns"] / pair["candidate"]["host_ns"],
                     "enabled_overhead": pair["diagnostics"]["host_ns"] /
                                         pair["candidate"]["host_ns"] - 1})
    result = {"schema": SCHEMA, "matrix_id": manifest["id"], "cases": len(cases),
              "trials": protocol["trials"], "all_frames_and_diagnostic_accounting_match": True,
              "timing_accepted": False,
              "limitations": ["Host discovery; does not establish sustained Android cadence.",
                              "Enabled diagnostics compared with disabled diagnostics; no erased-code reference.",
                              "Counters describe execution paths, not equivalent host costs."],
              "per_case": []}
    for (scenario, profile), trials in sorted(cases.items()):
        median = statistics.median(row["speedup"] for row in trials)
        result["per_case"].append({"scenario": scenario, "profile": profile,
                                   "median_speedup": median, "trials": trials,
                                   "requires_longer_controls": median < 1 / 1.03,
                                   "median_enabled_overhead": statistics.median(
                                       row["enabled_overhead"] for row in trials)})
    result["geomean_median_speedup"] = math.exp(statistics.mean(
        math.log(case["median_speedup"]) for case in result["per_case"]))
    return result


def run_matrix(args):
    manifest = load_manifest(args.manifest)
    protocol = manifest["protocol"]
    classpaths, inventories, receipts = {}, {}, {}
    for variant in ("baseline", "candidate"):
        cp, inventory = inventory_classpath(getattr(args, variant + "_classpath_file"))
        classpaths[variant], inventories[variant] = cp, inventory
        receipt = getattr(args, variant + "_source_receipt").resolve(strict=True)
        # Receipts can have different build schemas; their exact bytes are retained. Their
        # source-to-class association is supplied by the caller, never inferred here.
        json.loads(receipt.read_text())
        receipts[variant] = {"path": str(receipt), "sha256": sha256(receipt)}
    allowed_cpus = sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None
    if args.cpu is not None and (allowed_cpus is None or args.cpu not in allowed_cpus):
        raise ValueError("Requested CPU affinity is unavailable")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "harness-classes"
    classes.mkdir()
    root = Path(__file__).resolve().parents[1]
    source_root = root / "core/src/test/java/eu/rekawek/coffeegb/core/performance"
    sources = [source_root / "PerformanceMatrixMain.java", source_root / "PerformanceWorkloads.java"]
    source_hashes = {path.name: sha256(path) for path in sources}
    write_json(output / "manifest.json", manifest)
    with (output / "cases.tsv").open("x") as stream:
        for case in manifest["cases"]:
            stream.write(case["scenario"] + "\t" + case["profile"] + "\n")
    metadata = {"schema": SCHEMA, "status": "in_progress", "started_utc": now(),
                "source_receipts": receipts, "code_classpaths": inventories,
                "harness_sources": source_hashes, "java": args.java, "javac": args.javac,
                "affinity_cpu": args.cpu, "allowed_cpus": allowed_cpus,
                "source_association": "caller-supplied immutable build receipts",
                "order": [], "manifest_sha256": sha256(output / "manifest.json")}
    write_json(output / "metadata.json", metadata)
    try:
        with (output / "compile.log").open("x") as compiler_log:
            subprocess.run([args.javac, "--release", "16", "-g", "-cp", classpaths["candidate"],
                            "-d", str(classes), *map(str, sources)], check=True,
                           stdout=compiler_log, stderr=subprocess.STDOUT, timeout=120)
        metadata["compiled_harness"] = {
            path.relative_to(classes).as_posix(): sha256(path) for path in sorted(classes.rglob("*.class"))}
        with (output / "results.tsv").open("x") as stream, (output / "stderr.log").open("x") as errors:
            stream.write("\t".join(COLUMNS) + "\n")
            stream.flush()
            for trial in range(protocol["trials"]):
                order = VARIANTS if trial % 2 == 0 else ("candidate", "baseline", "diagnostics")
                for variant in order:
                    cp = classpaths["baseline" if variant == "baseline" else "candidate"]
                    command = [args.java, *protocol["jvm_flags"], "-cp", str(classes) + os.pathsep + cp,
                               MAIN, variant, str(trial), str(output / "cases.tsv"),
                               str(protocol["warmup_ticks"]), str(protocol["measured_ticks"]),
                               str(protocol["shuffle_seed"]), str(protocol["diagnostic_window_ticks"]),
                               manifest["id"]]
                    if args.cpu is not None:
                        command = ["taskset", "-c", str(args.cpu), *command]
                    receipt = {"trial": trial, "variant": variant, "started_utc": now(),
                               "command": command,
                               "load_average": list(os.getloadavg()) if hasattr(os, "getloadavg") else None}
                    metadata["order"].append(receipt)
                    write_json(output / "metadata.json", metadata)
                    started = time.monotonic()
                    subprocess.run(command, stdout=stream, stderr=errors, check=True, timeout=args.timeout)
                    stream.flush()
                    receipt["wall_seconds"] = time.monotonic() - started
                    print(f"{variant} trial {trial}: {receipt['wall_seconds']:.1f}s", flush=True)
        for variant in ("baseline", "candidate"):
            _, current = inventory_classpath(getattr(args, variant + "_classpath_file"))
            if current != inventories[variant] or sha256(receipts[variant]["path"]) != receipts[variant]["sha256"]:
                raise ValueError("Source/classpath pin changed during measurements")
        if {path.name: sha256(path) for path in sources} != source_hashes:
            raise ValueError("Authored harness source changed during measurements")
        metadata["status"] = "complete"
        metadata["completed_utc"] = now()
        metadata["results_sha256"] = sha256(output / "results.tsv")
        write_json(output / "metadata.json", metadata)
        summarize_output(output)
    except BaseException as failure:
        metadata["status"] = "failed"
        metadata["failure_type"] = type(failure).__name__
        write_json(output / "metadata.json", metadata)
        raise


def summarize_output(output):
    metadata = json.loads((output / "metadata.json").read_text())
    if metadata.get("status") != "complete":
        raise ValueError("Run did not finish with unchanged source/class pins")
    if sha256(output / "manifest.json") != metadata["manifest_sha256"]:
        raise ValueError("Manifest changed after measurement")
    if sha256(output / "results.tsv") != metadata["results_sha256"]:
        raise ValueError("Raw measurements changed after completion")
    manifest = load_manifest(output / "manifest.json")
    with (output / "results.tsv").open() as stream:
        result = summarize(list(csv.DictReader(stream, delimiter="\t")), manifest)
    result["manifest_sha256"] = metadata["manifest_sha256"]
    result["results_sha256"] = metadata["results_sha256"]
    result["source_receipt_sha256"] = {
        variant: receipt["sha256"] for variant, receipt in metadata["source_receipts"].items()}
    write_json(output / "summary.json", result)
    print(f"Validated {result['cases']} cases × {result['trials']} trials; timing acceptance remains pending.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    run = commands.add_parser("run", help="Compile the common test harness and run sequential JVMs")
    run.add_argument("--manifest", type=Path, default=Path(__file__).with_name("performance-matrix.json"))
    run.add_argument("--output", type=Path, required=True, help="New output directory; never overwritten")
    for variant in ("baseline", "candidate"):
        run.add_argument("--" + variant + "-classpath-file", type=Path, required=True)
        run.add_argument("--" + variant + "-source-receipt", type=Path, required=True)
    run.add_argument("--java", default="java")
    run.add_argument("--javac", default="javac")
    run.add_argument("--cpu", type=int, help="Optional common Linux CPU affinity, recorded in metadata")
    run.add_argument("--timeout", type=int, default=600, help="Maximum seconds per variant/trial JVM")
    summary = commands.add_parser("summarize", help="Validate saved raw work and recompute the summary")
    summary.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "run":
        run_matrix(args)
    else:
        summarize_output(args.output)


if __name__ == "__main__":
    main()
