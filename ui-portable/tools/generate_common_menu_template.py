#!/usr/bin/env python3
"""Reproduce the one packaged Coffee GB menu frame from the selected ImageGen output.

The built-in ImageGen edit is retained by digest rather than committed as another runtime image.
This tool validates that exact source and invokes ffmpeg with the same deterministic conversion
used for the checked-in 924x736, RGB, non-interlaced PNG.
"""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import shutil
import struct
import subprocess


MODULE = Path(__file__).resolve().parents[1]
OUTPUT = (
    MODULE
    / "src/main/resources/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates"
    / "common-menu-frame.png"
)
EXPECTED_SOURCE_SHA256 = (
    "415bedc2480f5d9f8b8a3ff572f10ba4fc388e30db4502ca0e42dcb4a6361af6"
)
EXPECTED_OUTPUT_SHA256 = (
    "b8bacd0c2db9a996a8977c708f5daafe3036e02a44acb5f6f846b0e62d8eedb5"
)


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def validate_png(path: Path) -> None:
    with path.open("rb") as stream:
        header = stream.read(33)
    if len(header) != 33 or header[:8] != b"\x89PNG\r\n\x1a\n":
        raise RuntimeError(f"Not a PNG: {path}")
    length, chunk = struct.unpack(">I4s", header[8:16])
    width, height, depth, color, compression, filtering, interlace = struct.unpack(
        ">IIBBBBB", header[16:29]
    )
    actual = (length, chunk, width, height, depth, color, compression, filtering, interlace)
    expected = (13, b"IHDR", 924, 736, 8, 2, 0, 0, 0)
    if actual != expected:
        raise RuntimeError(f"Unexpected packaged PNG header: {actual!r}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path, help="selected built-in ImageGen PNG")
    args = parser.parse_args()
    source = args.source.resolve()
    if digest(source) != EXPECTED_SOURCE_SHA256:
        raise RuntimeError("source does not match the selected ImageGen output")
    ffmpeg = shutil.which("ffmpeg")
    if ffmpeg is None:
        raise RuntimeError("ffmpeg is required to regenerate the common menu template")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-y",
            "-i",
            str(source),
            "-vf",
            "scale=924:736:flags=lanczos",
            "-pix_fmt",
            "rgb24",
            "-frames:v",
            "1",
            str(OUTPUT),
        ],
        check=True,
    )
    validate_png(OUTPUT)
    if digest(OUTPUT) != EXPECTED_OUTPUT_SHA256:
        raise RuntimeError("ffmpeg output differs from the audited common menu frame")


if __name__ == "__main__":
    main()
