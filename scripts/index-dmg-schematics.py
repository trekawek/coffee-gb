#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build a compact, factual index of the sibling dmg-schematics checkout.

The source checkout is CC BY-SA 4.0.  This independently authored reader emits
only structural metadata (sheet relationships, interface label names, symbol
counts, and netlist category counts); it does not copy schematic geometry or
cell-level wire connectivity.
"""

from __future__ import annotations

import argparse
import collections
import json
import pathlib
import re
import subprocess
import sys
from typing import Iterator


STRING = r'"(?:\\.|[^"\\])*"'

REFERENCE_COUNTS = {
    "schematic_count": 65,
    "hierarchy_interface_count": 59,
    "hierarchy_interface_mismatch_count": 1,
    "netlist_file_count": 82,
    "cell_count": 4_756,
    "wire_count": 5_114,
    "type_count": 221,
    "unique_type_name_count": 214,
    "declared_category_count": 63,
    "alias_count": 72,
    "classified_cell_count": 4_674,
    "unclassified_cell_count": 82,
}

REFERENCE_COMMIT = "02399f96e0893783c130cf6f03fad7a1148ae60a"

REFERENCE_CATEGORIES = (
    "alu", "alu-core", "alu-daa", "alu-dec", "alu-flag", "apu-analog",
    "apu-ch1", "apu-ch2", "apu-ch3", "apu-ch4", "apu-control", "apu-decode",
    "bootrom", "bus-adr", "bus-data", "clocks", "dbus", "dbus-mreq", "dbus-rd",
    "dec1", "dec2", "dec3", "hram", "idu", "int", "irq", "irq-ie", "joypad",
    "ppu-bgfifo", "ppu-bgscroll", "ppu-control", "ppu-cycles", "ppu-decode",
    "ppu-dma", "ppu-lcd", "ppu-mux", "ppu-oam", "ppu-objctl", "ppu-objfifo",
    "ppu-objreg", "ppu-pal", "ppu-stat", "ppu-vram", "ppu-window", "ppu-xcomp",
    "ppu-xprio", "ppu-ycomp", "reg", "reg-a", "reg-bc", "reg-bus", "reg-de",
    "reg-hl", "reg-ir", "reg-pc", "reg-sp", "reg-wz", "seq", "seq-irq",
    "serial", "sys-decode", "test", "timer",
)

REFERENCE_ORPHANS = {
    "dmg_cpu_b": (
        "dmg_cpu_b/ff04_div.kicad_sch",
        "dmg_cpu_b/sprite_store.kicad_sch",
    ),
    "sm83": (),
}

REFERENCE_INTERFACE_MISMATCH = {
    "parent": "dmg_cpu_b/ppu.kicad_sch",
    "child": "dmg_cpu_b/oam.kicad_sch",
    "resolved": True,
    "parent_only_pins": [],
    "child_only_labels": ["DMA_PHI"],
}


def decode_string(value: str) -> str:
    return json.loads(value)


def first_string(form: str, key: str) -> str | None:
    match = re.search(r"\(" + re.escape(key) + r"\s+(" + STRING + r")", form)
    return decode_string(match.group(1)) if match else None


def properties(form: str) -> dict[str, str]:
    result: dict[str, str] = {}
    pattern = re.compile(r"\(property\s+(" + STRING + r")\s+(" + STRING + r")")
    for match in pattern.finditer(form):
        result[decode_string(match.group(1))] = decode_string(match.group(2))
    return result


def top_level_forms(path: pathlib.Path) -> Iterator[tuple[str, str]]:
    """Yield direct children of the kicad_sch root without building a huge AST."""
    data = path.read_text(encoding="utf-8")
    depth = 0
    start: int | None = None
    quoted = False
    escaped = False
    for offset, char in enumerate(data):
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == '"':
            quoted = True
        elif char == "(":
            if depth == 1:
                start = offset
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 1 and start is not None:
                form = data[start : offset + 1]
                match = re.match(r"\(\s*([^\s()]+)", form)
                if match:
                    yield match.group(1), form
                start = None
        if depth < 0:
            raise ValueError(f"unbalanced closing parenthesis in {path}")
    if depth != 0 or quoted:
        raise ValueError(f"unterminated expression or string in {path}")


def schematic_index(path: pathlib.Path, root: pathlib.Path) -> dict[str, object]:
    counts: collections.Counter[str] = collections.Counter()
    labels: dict[str, set[str]] = {
        "hierarchical": set(),
        "global": set(),
        "local": set(),
    }
    symbols: collections.Counter[str] = collections.Counter()
    references: list[dict[str, str]] = []
    children: list[dict[str, object]] = []
    title: dict[str, str] = {}

    for kind, form in top_level_forms(path):
        counts[kind] += 1
        if kind == "title_block":
            for key in ("title", "date", "rev", "company"):
                value = first_string(form, key)
                if value is not None:
                    title[key] = value
        elif kind == "sheet":
            props = properties(form)
            pins = [decode_string(value) for value in re.findall(r"\(pin\s+(" + STRING + r")", form)]
            children.append(
                {
                    "name": props.get("Sheetname", ""),
                    "file": props.get("Sheetfile", ""),
                    "pins": sorted(set(pins)),
                }
            )
        elif kind in ("hierarchical_label", "global_label", "label"):
            value = first_string(form, kind)
            if value is not None:
                bucket = {
                    "hierarchical_label": "hierarchical",
                    "global_label": "global",
                    "label": "local",
                }[kind]
                labels[bucket].add(value)
        elif kind == "symbol":
            lib_id = first_string(form, "lib_id")
            props = properties(form)
            if lib_id:
                symbols[lib_id] += 1
                reference = props.get("Reference")
                value = props.get("Value")
                if reference and not reference.startswith("#"):
                    references.append(
                        {"reference": reference, "value": value or "", "lib_id": lib_id}
                    )

    return {
        "path": path.relative_to(root).as_posix(),
        "title": title,
        "children": sorted(children, key=lambda child: (str(child["file"]), str(child["name"]))),
        "labels": {key: sorted(values) for key, values in labels.items()},
        "symbols": dict(sorted(symbols.items())),
        "references": sorted(references, key=lambda item: item["reference"]),
        "object_counts": dict(sorted(counts.items())),
    }


def netlist_statements(data: str, source: pathlib.Path) -> Iterator[str]:
    """Yield semicolon-terminated nlconv statements.

    Statements may span lines and command names may carry a condition suffix,
    for example ``cell:-codegen``.  Hash comments and semicolons only have
    syntax outside quoted strings.
    """
    buffer: list[str] = []
    quoted = False
    escaped = False
    commented = False

    for char in data:
        if commented:
            if char == "\n":
                commented = False
                buffer.append(char)
            continue
        if quoted:
            buffer.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
            continue
        if char == "#":
            commented = True
        elif char == '"':
            quoted = True
            buffer.append(char)
        elif char == ";":
            statement = "".join(buffer).strip()
            buffer.clear()
            if statement:
                yield statement
        else:
            buffer.append(char)

    if quoted:
        raise ValueError(f"unterminated string in {source}")
    remainder = "".join(buffer).strip()
    if remainder:
        raise ValueError(f"unterminated netlist statement in {source}: {remainder[:80]!r}")


def netlist_command(statement: str) -> tuple[str, str | None]:
    match = re.match(r"([A-Za-z][A-Za-z0-9_-]*)(?::([^\s]+))?(?:\s|$)", statement)
    if not match:
        raise ValueError(f"unrecognized netlist statement: {statement[:80]!r}")
    return match.group(1), match.group(2)


def makefile_file_list(path: pathlib.Path, variable: str) -> list[str]:
    """Read one simple backslash-continued file-list assignment."""
    lines = path.read_text(encoding="utf-8").splitlines()
    prefix = f"{variable} ="
    for index, line in enumerate(lines):
        if not line.startswith(prefix):
            continue
        values: list[str] = []
        current = line[len(prefix) :].strip()
        while True:
            continued = current.endswith("\\")
            if continued:
                current = current[:-1].strip()
            values.extend(current.split())
            if not continued:
                return values
            index += 1
            if index >= len(lines):
                raise ValueError(f"unterminated {variable} assignment in {path}")
            current = lines[index].strip()
    raise ValueError(f"missing {variable} assignment in {path}")


def netlist_index(root: pathlib.Path) -> dict[str, object]:
    netlist_root = root / "netlist"
    declared_categories: set[str] = set()
    cells: collections.Counter[str] = collections.Counter()
    signal_classes: collections.Counter[str] = collections.Counter()
    cells_by_file: dict[str, collections.Counter[str]] = {}
    wires_by_file: dict[str, collections.Counter[str]] = {}
    statement_counts_by_file: dict[str, collections.Counter[str]] = {}
    unclassified_cells_by_file: collections.Counter[str] = collections.Counter()
    unclassified_wires_by_file: collections.Counter[str] = collections.Counter()
    statement_counts: collections.Counter[str] = collections.Counter()
    qualified_statements: collections.Counter[str] = collections.Counter()
    cell_names: set[str] = set()
    wire_names: set[str] = set()
    type_names: set[str] = set()
    cell_aliases = 0
    wire_aliases = 0

    cell_re = re.compile(
        r"^cell(?::(?P<condition>[^\s]+))?\s+"
        r"(?P<name>[^:\s]+):(?P<type>[^\s;]+)"
    )
    wire_re = re.compile(
        r"^wire(?::(?P<condition>[^\s]+))?\s+"
        r"(?P<name>[^:\s]+)(?::(?P<signal>[^\s;]+))?(?:\s|$)"
    )
    category_statement_re = re.compile(r"^category\s+([^\s:]+)\s*:")
    alias_re = re.compile(r"^alias\s+(cell|wire)\s+")
    type_re = re.compile(r"^type(?::[^\s]+)?\s+([^\s;]+)")

    files = sorted(netlist_root.rglob("*.nl"))
    for path in files:
        relative = path.relative_to(root).as_posix()
        file_cells: collections.Counter[str] = collections.Counter()
        file_wires: collections.Counter[str] = collections.Counter()
        file_statement_counts: collections.Counter[str] = collections.Counter()
        data = path.read_text(encoding="utf-8")

        for statement in netlist_statements(data, path):
            command, condition = netlist_command(statement)
            statement_counts[command] += 1
            file_statement_counts[command] += 1
            if condition:
                qualified_statements[f"{command}:{condition}"] += 1

            if command == "category":
                match = category_statement_re.match(statement)
                if not match:
                    raise ValueError(f"malformed category in {path}: {statement[:80]!r}")
                declared_categories.add(match.group(1))
            elif command == "cell":
                match = cell_re.match(statement)
                if not match:
                    raise ValueError(f"malformed cell in {path}: {statement[:80]!r}")
                cell_names.add(match.group("name"))
                category_match = re.search(r"->\s*([A-Za-z0-9_-]+)", statement)
                if category_match:
                    category = category_match.group(1)
                    cells[category] += 1
                    file_cells[category] += 1
                else:
                    unclassified_cells_by_file[relative] += 1
            elif command == "wire":
                match = wire_re.match(statement)
                if not match:
                    raise ValueError(f"malformed wire in {path}: {statement[:80]!r}")
                wire_names.add(match.group("name"))
                signal = match.group("signal")
                if signal:
                    signal_classes[signal] += 1
                    file_wires[signal] += 1
                else:
                    unclassified_wires_by_file[relative] += 1
            elif command == "type":
                match = type_re.match(statement)
                if not match:
                    raise ValueError(f"malformed type in {path}: {statement[:80]!r}")
                type_names.add(match.group(1))
            elif command == "alias":
                match = alias_re.match(statement)
                if not match:
                    raise ValueError(f"malformed alias in {path}: {statement[:80]!r}")
                if match.group(1) == "cell":
                    cell_aliases += 1
                else:
                    wire_aliases += 1
        if file_cells:
            cells_by_file[relative] = file_cells
        if file_wires:
            wires_by_file[relative] = file_wires
        statement_counts_by_file[relative] = file_statement_counts

    undeclared_cell_categories = sorted(set(cells) - declared_categories)
    if undeclared_cell_categories:
        raise ValueError(f"undeclared cell categories: {undeclared_cell_categories}")

    all_categories = sorted(declared_categories)
    makefile = netlist_root / "Makefile"
    dmg_makefile_files = [
        f"netlist/{value}" for value in makefile_file_list(makefile, "NETLIST_FILES")
    ]
    sm83_makefile_files = [
        f"netlist/{value}" for value in makefile_file_list(makefile, "SM83_NETLIST_FILES")
    ]
    return {
        "files": [path.relative_to(root).as_posix() for path in files],
        "file_count": len(files),
        "statement_counts": dict(sorted(statement_counts.items())),
        "statement_counts_by_file": {
            path: dict(sorted(counter.items()))
            for path, counter in sorted(statement_counts_by_file.items())
        },
        "qualified_statements": dict(sorted(qualified_statements.items())),
        "cell_count": statement_counts["cell"],
        "wire_count": statement_counts["wire"],
        "type_count": statement_counts["type"],
        "unique_type_name_count": len(type_names),
        "declared_category_count": statement_counts["category"],
        "signal_count": statement_counts["signal"],
        "define_count": statement_counts["define"],
        "label_count": statement_counts["label"],
        "alias_count": statement_counts["alias"],
        "unique_cell_names": len(cell_names),
        "unique_wire_names": len(wire_names),
        "cell_alias_statements": cell_aliases,
        "wire_alias_statements": wire_aliases,
        "makefile_groups": {
            "NETLIST_FILES": dmg_makefile_files,
            "SM83_NETLIST_FILES": sm83_makefile_files,
        },
        "classified_cell_count": sum(cells.values()),
        "unclassified_cell_count": sum(unclassified_cells_by_file.values()),
        "unclassified_cells_by_file": dict(sorted(unclassified_cells_by_file.items())),
        "classified_wire_count": sum(signal_classes.values()),
        "unclassified_wire_count": sum(unclassified_wires_by_file.values()),
        "unclassified_wires_by_file": dict(sorted(unclassified_wires_by_file.items())),
        "categories": [
            {
                "name": category,
                "cells": cells[category],
            }
            for category in all_categories
        ],
        "signal_classes": [
            {"name": signal, "wires": signal_classes[signal]}
            for signal in sorted(signal_classes)
        ],
        "cells_by_file": {
            path: dict(sorted(counter.items()))
            for path, counter in sorted(cells_by_file.items())
        },
        "wires_by_file": {
            path: dict(sorted(counter.items())) for path, counter in sorted(wires_by_file.items())
        },
    }


def git_metadata(root: pathlib.Path) -> dict[str, object]:
    def git(*args: str) -> str:
        return subprocess.check_output(
            ["git", "-C", str(root), *args], text=True, stderr=subprocess.DEVNULL
        ).strip()

    try:
        return {
            "commit": git("rev-parse", "HEAD"),
            "commit_date": git("show", "-s", "--format=%cI", "HEAD"),
            "status": git("status", "--short").splitlines(),
        }
    except (OSError, subprocess.CalledProcessError):
        return {"commit": None, "commit_date": None, "status": []}


def build_index(root: pathlib.Path) -> dict[str, object]:
    projects = ("dmg_cpu_b", "sm83", "dmg_cells", "sm83_cells")
    schematics = [
        schematic_index(path, root)
        for project in projects
        for path in sorted((root / project).glob("*.kicad_sch"))
    ]
    by_path = {str(item["path"]): item for item in schematics}
    roots = {
        "dmg_cpu_b": "dmg_cpu_b/dmg_cpu_b.kicad_sch",
        "sm83": "sm83/sm83.kicad_sch",
    }
    hierarchy: dict[str, object] = {}
    for project, project_root in roots.items():
        reachable: set[str] = set()
        pending = [project_root]
        while pending:
            current = pending.pop()
            if current in reachable:
                continue
            reachable.add(current)
            for child in by_path[current]["children"]:  # type: ignore[index]
                child_path = f"{project}/{child['file']}"
                if child_path in by_path:
                    pending.append(child_path)
        project_files = {
            path for path in by_path if path.startswith(f"{project}/")
        }
        hierarchy[project] = {
            "root": project_root,
            "reachable": sorted(reachable),
            "orphans": sorted(project_files - reachable),
        }

    interfaces: list[dict[str, object]] = []
    for parent_path, parent in sorted(by_path.items()):
        parent_dir = pathlib.PurePosixPath(parent_path).parent
        for child in parent["children"]:  # type: ignore[index]
            child_path = (parent_dir / str(child["file"])).as_posix()
            target = by_path.get(child_path)
            if target is None:
                interfaces.append(
                    {
                        "parent": parent_path,
                        "child": child_path,
                        "resolved": False,
                        "parent_only_pins": sorted(set(child["pins"])),
                        "child_only_labels": [],
                    }
                )
                continue
            parent_pins = set(child["pins"])
            child_labels = set(target["labels"]["hierarchical"])  # type: ignore[index]
            interfaces.append(
                {
                    "parent": parent_path,
                    "child": child_path,
                    "resolved": True,
                    "parent_only_pins": sorted(parent_pins - child_labels),
                    "child_only_labels": sorted(child_labels - parent_pins),
                }
            )

    return {
        "source": str(root),
        "git": git_metadata(root),
        "schematic_count": len(schematics),
        "schematics": schematics,
        "hierarchy": hierarchy,
        "hierarchy_interfaces": interfaces,
        "netlist": netlist_index(root),
    }


def scanner_self_check() -> list[str]:
    fixture = '''
# A qualified, multiline cell with an attribute after its category.
cell:-codegen lmix:mixer
    in1@-1,2,3,4
    ->apu-analog attrib "sv:note=contains;a-semicolon";
cell:codegen audio:audio;
wire:-codegen mixed:analog
    lmix.out -> audio.lout;
alias cell audio_alias -> audio;
type:codegen audio in out:out "description; still one statement";
'''
    errors: list[str] = []
    source = pathlib.Path("<self-check>")
    statements = list(netlist_statements(fixture, source))
    commands = [netlist_command(statement) for statement in statements]
    expected = [
        ("cell", "-codegen"),
        ("cell", "codegen"),
        ("wire", "-codegen"),
        ("alias", None),
        ("type", "codegen"),
    ]
    if commands != expected:
        errors.append(f"statement scanner fixture: expected {expected}, got {commands}")
    if not re.search(r"->\s*apu-analog\b", statements[0]):
        errors.append("statement scanner fixture lost the multiline cell category")
    return errors


def validate_index(index: dict[str, object], reference_counts: bool) -> list[str]:
    errors = scanner_self_check()
    netlist = index["netlist"]
    interfaces = index["hierarchy_interfaces"]
    assert isinstance(netlist, dict)
    assert isinstance(interfaces, list)

    categories = netlist["categories"]
    signal_classes = netlist["signal_classes"]
    cells_by_file = netlist["cells_by_file"]
    wires_by_file = netlist["wires_by_file"]
    assert isinstance(categories, list)
    assert isinstance(signal_classes, list)
    assert isinstance(cells_by_file, dict)
    assert isinstance(wires_by_file, dict)

    invariants = {
        "declared category records": (
            len(categories),
            netlist["declared_category_count"],
        ),
        "classified plus unclassified cells": (
            int(netlist["classified_cell_count"]) + int(netlist["unclassified_cell_count"]),
            netlist["cell_count"],
        ),
        "per-file classified cells": (
            sum(sum(counter.values()) for counter in cells_by_file.values()),
            netlist["classified_cell_count"],
        ),
        "signal-class wires": (
            sum(int(item["wires"]) for item in signal_classes)
            + int(netlist["unclassified_wire_count"]),
            netlist["wire_count"],
        ),
        "per-file wires": (
            sum(sum(counter.values()) for counter in wires_by_file.values())
            + int(netlist["unclassified_wire_count"]),
            netlist["wire_count"],
        ),
        "cell plus wire aliases": (
            int(netlist["cell_alias_statements"]) + int(netlist["wire_alias_statements"]),
            netlist["alias_count"],
        ),
    }
    for name, (actual, expected) in invariants.items():
        if actual != expected:
            errors.append(f"{name}: expected {expected}, got {actual}")

    if reference_counts:
        git = index["git"]
        hierarchy = index["hierarchy"]
        schematics = index["schematics"]
        assert isinstance(git, dict)
        assert isinstance(hierarchy, dict)
        assert isinstance(schematics, list)
        mismatched_interfaces = [
            item
            for item in interfaces
            if not item["resolved"]
            or item["parent_only_pins"]
            or item["child_only_labels"]
        ]
        actual_counts = {
            "schematic_count": index["schematic_count"],
            "hierarchy_interface_count": len(interfaces),
            "hierarchy_interface_mismatch_count": len(mismatched_interfaces),
            "netlist_file_count": netlist["file_count"],
            **{name: netlist[name] for name in REFERENCE_COUNTS if name in netlist},
        }
        for name, expected in REFERENCE_COUNTS.items():
            actual = actual_counts[name]
            if actual != expected:
                errors.append(f"reference {name}: expected {expected}, got {actual}")

        if git["commit"] != REFERENCE_COMMIT:
            errors.append(
                f"reference commit: expected {REFERENCE_COMMIT}, got {git['commit']}"
            )
        if git["status"]:
            errors.append(f"reference checkout is dirty: {git['status']}")

        project_sheet_counts = collections.Counter(
            str(item["path"]).split("/", 1)[0] for item in schematics
        )
        expected_project_sheet_counts = {
            "dmg_cpu_b": 49,
            "sm83": 14,
            "dmg_cells": 1,
            "sm83_cells": 1,
        }
        if dict(project_sheet_counts) != expected_project_sheet_counts:
            errors.append(
                "reference schematic split: expected "
                f"{expected_project_sheet_counts}, got {dict(project_sheet_counts)}"
            )

        for project, expected_orphans in REFERENCE_ORPHANS.items():
            project_hierarchy = hierarchy[project]
            assert isinstance(project_hierarchy, dict)
            actual_orphans = tuple(project_hierarchy["orphans"])
            if actual_orphans != expected_orphans:
                errors.append(
                    f"reference {project} orphans: expected {expected_orphans}, "
                    f"got {actual_orphans}"
                )
        reachable_counts = {
            project: len(hierarchy[project]["reachable"])  # type: ignore[index]
            for project in REFERENCE_ORPHANS
        }
        if reachable_counts != {"dmg_cpu_b": 47, "sm83": 14}:
            errors.append(
                "reference reachable sheets: expected {'dmg_cpu_b': 47, 'sm83': 14}, "
                f"got {reachable_counts}"
            )

        if mismatched_interfaces != [REFERENCE_INTERFACE_MISMATCH]:
            errors.append(
                "reference interface mismatch: expected "
                f"{REFERENCE_INTERFACE_MISMATCH}, got {mismatched_interfaces}"
            )

        category_names = tuple(item["name"] for item in categories)
        if category_names != REFERENCE_CATEGORIES:
            errors.append(
                f"reference categories: expected {REFERENCE_CATEGORIES}, got {category_names}"
            )

        makefile_groups = netlist["makefile_groups"]
        assert isinstance(makefile_groups, dict)
        makefile_files = [
            path
            for group in ("NETLIST_FILES", "SM83_NETLIST_FILES")
            for path in makefile_groups[group]
        ]
        discovered_files = netlist["files"]
        if sorted(makefile_files) != sorted(discovered_files):
            errors.append("reference Makefile membership differs from discovered .nl files")
        makefile_group_counts = {
            group: len(makefile_groups[group])
            for group in ("NETLIST_FILES", "SM83_NETLIST_FILES")
        }
        if makefile_group_counts != {"NETLIST_FILES": 66, "SM83_NETLIST_FILES": 16}:
            errors.append(
                "reference Makefile split: expected 66/16, "
                f"got {makefile_group_counts}"
            )
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "root",
        nargs="?",
        type=pathlib.Path,
        default=pathlib.Path("../dmg-schematics"),
        help="path to the dmg-schematics checkout",
    )
    parser.add_argument("--compact", action="store_true", help="emit compact JSON")
    parser.add_argument(
        "--check",
        action="store_true",
        help="validate parser invariants and the reviewed source snapshot counts",
    )
    args = parser.parse_args()
    root = args.root.resolve()
    if not (root / "dmg_cpu_b" / "dmg_cpu_b.kicad_sch").is_file():
        parser.error(f"not a dmg-schematics checkout: {root}")
    index = build_index(root)
    errors = validate_index(index, reference_counts=args.check)
    if errors:
        for error in errors:
            print(f"ERROR: {error}", file=sys.stderr)
        return 1
    if args.check:
        netlist = index["netlist"]
        assert isinstance(netlist, dict)
        print(
            "OK: "
            f"{len(index['hierarchy_interfaces'])} hierarchy interfaces, "
            f"{netlist['file_count']} netlist files, "
            f"{netlist['cell_count']} cells, "
            f"{netlist['wire_count']} wires, "
            f"{netlist['type_count']} type declarations, "
            f"{netlist['declared_category_count']} categories, "
            f"{netlist['alias_count']} aliases"
        )
        return 0
    json.dump(
        index,
        sys.stdout,
        indent=None if args.compact else 2,
        sort_keys=False,
    )
    sys.stdout.write("\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
