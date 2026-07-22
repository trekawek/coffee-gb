#!/usr/bin/env python3
"""Derive Coffee GB release inputs from the root Maven project version."""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path


SNAPSHOT_VERSION = re.compile(r"^(?P<prefix>(?:\d+\.)*)(?P<last>\d+)-SNAPSHOT$")


def project_version(pom: Path) -> str:
    try:
        root = ET.parse(pom).getroot()
    except (OSError, ET.ParseError) as error:
        raise ValueError(f"cannot read {pom}: {error}") from error

    namespace = ""
    if root.tag.startswith("{"):
        namespace = root.tag.partition("}")[0] + "}"
    element = root.find(f"{namespace}version")
    if element is None or not element.text:
        raise ValueError(f"root project version not found in {pom}")
    return element.text.strip()


def derive(version: str) -> tuple[str, str]:
    match = SNAPSHOT_VERSION.fullmatch(version)
    if not match:
        raise ValueError(
            f"expected a numeric Maven snapshot such as 1.7.8-SNAPSHOT, got {version!r}"
        )
    release = version.removesuffix("-SNAPSHOT")
    development = (
        f"{match.group('prefix')}{int(match.group('last')) + 1}-SNAPSHOT"
    )
    return release, development


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pom", type=Path, default=Path("pom.xml"))
    args = parser.parse_args()

    try:
        release, development = derive(project_version(args.pom))
    except ValueError as error:
        print(error, file=sys.stderr)
        return 1

    print(f"release_version={release}")
    print(f"development_version={development}")
    print(f"tag=coffee-gb-{release}")
    print(f"title=Coffee GB {release}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
