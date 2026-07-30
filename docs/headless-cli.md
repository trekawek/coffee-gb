# Headless command-line interface

## Scope and process contract

The `coffee-gb-cli` Maven module is the supported non-Swing entry point for bounded compatibility
runs and deterministic replay. Its production sources depend on `controller`, never on the
desktop module, and do not reference or initialize AWT or Swing. Emulator operations still run on
the controller's owner thread at its documented safe points; the command line is only a strict
request, reporting, and artifact boundary.

Build the executable JAR with Java 16 or newer:

```bash
mvn -B -pl cli -am package
java -jar cli/target/coffee-gb-cli.jar --help
```

`--help` and `--version` write human-readable text to stdout and exit 0. Every `run` or `replay`
invocation, including a rejected invocation, writes exactly one versioned JSON object followed by
one LF to stdout. Diagnostics are separate, one-line, path-free messages on stderr. The CLI does
not print exception messages or stack traces. A caller can therefore treat stdout as a stable
machine channel without merging it with stderr.

## Commands and bounds

A fresh bounded run requires exactly one termination bound:

```bash
java -jar cli/target/coffee-gb-cli.jar run \
  --rom tests/example.gb --profile dmg --bootstrap skip \
  --frames 120 --screenshot frame.png --json-out report.json
```

`--ticks N` accepts `1..1000000000`; `--frames N` accepts `1..14000`. The frame ceiling keeps every
supported profile within the same one-billion-tick aggregate safety cap. Supplying neither or both
is invalid. Replay always requires its own positive hard limit, even though the recording contains
a final position:

```bash
java -jar cli/target/coffee-gb-cli.jar replay \
  --rom tests/example.gb --replay evidence.cgbreplay --max-ticks 100000000
```

Replay identity defaults to `--profile replay`, `--bootstrap replay`, and `--sgb-border replay`.
Those values require exact values from the recording. Explicit canonical profile values are `dmg`,
`cgb`, `cgb0`, `sgb`, `sgb2`, and `mgb`; an override still has to match the replay. `run` instead
defaults to `--profile auto`, `--bootstrap skip`, and `--sgb-border off`. Run bootstrap choices are
`skip`, `normal`, and `fast-forward`.

`--rom-entry ENTRY` selects one ROM in a bounded ZIP or 7z source. Super Game Boy pass-through
loading uses `--slot-rom FILE` and, for an archive, `--slot-rom-entry ENTRY`. Selection and ROM
validation use the same bounded controller preparation boundary as the desktop. `--rtc-epoch-millis
N` supplies a non-negative deterministic RTC epoch for `run`; replay obtains it from replay state.

The parser accepts only documented long options with a separate value. Unknown options,
positional operands after the command, `--name=value`, repeated single-value options, conflicting
input/output paths, and unbounded numeric values are errors. Only `--memory` is repeatable. This is
intentional: automation cannot silently fall back to a default or reinterpret a misspelled flag.

## Input scripts (`CGBI` v1)

`run --input-script FILE` applies a bounded absolute-input timeline. CGBI is strict UTF-8 text,
with LF or consistently parseable CRLF line endings and this exact header:

```text
CGBI	1	0
0	1	0x10
1200	1	0x00
1200	2	0x01
```

Each record is zero-based `tick-to-execute`, `player-number`, and an absolute eight-bit button
mask, separated by tabs. Transitions for tick `N` are applied before emulator tick `N`; tick zero
therefore describes input visible to the first tick. Player numbers are 1 through 4. Mask bits,
from least significant to most significant, are Right, Left, Up, Down, A, B, Select, and Start.
Ticks are canonical unsigned decimal values no greater than `Long.MAX_VALUE - 1`. Records are
ordered by tick and then by
strictly increasing player number; a player may occur once at a tick. A row that leaves that
player's mask unchanged is rejected.

The decoder accepts at most 16 MiB, one million records, four players, and 96 characters per line.
It rejects a UTF-8 BOM, malformed UTF-8, bare carriage returns, blank rows, comments, unknown
header flags/versions, extra fields, non-canonical numbers, duplicate transitions, and trailing
content. It reads the file through an independently checked byte bound, so a file that grows while
being read cannot bypass the limit. CGBI contains inputs only; it never embeds a ROM or save.

## Break and inspection options

One optional `--break` condition stops before the execution bound:

| Form | Meaning |
| --- | --- |
| `pc:0xNNNN` | program counter equals the 16-bit address |
| `opcode:0xNN` | retire the unprefixed opcode |
| `cb:0xNN` | retire the CB-prefixed opcode |
| `tick:N` | completed master-tick counter reaches `N` |
| `frame:N` | completed frame counter reaches `N` |

Tick and frame breakpoint values start at 1; zero can never be reached by the completed-counter
model and is rejected. A same-unit counter breakpoint may not exceed the command bound. A hit is a
normal, typed termination with exit code 4, not an emulator error.

`--memory SPACE:0xSTART:LENGTH` requests a side-effect-free named view in the JSON report. The
canonical spaces are `system-bus`, `rom`, `cartridge-ram`, `video-ram`, `work-ram`, `oam`,
`io-registers`, and `high-ram`. At most 16 blocks and 4096 aggregate bytes are accepted; every range
must be non-empty and remain inside the 16-bit address domain. Availability and narrower
space-specific rules are validated by the debug port without exposing a live memory object.

`--expect-full-hash` takes exactly 64 hexadecimal digits. A different deterministic final hash is
reported as divergence (exit 5). It never downgrades to an ordinary success.

## Reports and artifacts

The execution JSON uses top-level schema `coffee-gb/headless-report`, version 1. It identifies the
termination and exit code, effective hardware/bootstrap identity, completed position, ROM identity
metadata and SHA-256, registers, requested bounded memory, deterministic subsystem hashes, and
replay compatibility/divergence data when applicable. Consumers must reject an unsupported schema
version and ignore additive fields they do not need. The object contains no filesystem paths.

Artifact options are independent:

| Option | Result |
| --- | --- |
| `--screenshot FILE` | latest complete frame as deterministic 160x144 or SGB 256x224 RGB PNG |
| `--wav FILE` | bounded captured PCM as canonical stereo WAV |
| `--json-out FILE` | the same canonical report object written to a file |
| `--bundle FILE` | the diagnostic ZIP described in [diagnostic-bundle-v1.md](diagnostic-bundle-v1.md) |

Output paths must be distinct, cannot contain one another, and cannot overwrite a ROM, replay,
slot ROM, or CGBI input path.
Writers use bounded data, a forced sibling temporary file, and no-replace publication. An existing
destination is an error, and a partial write is never reported as a successful artifact. PNG/WAV
bytes do not appear in stdout JSON.

## Exit codes

| Code | Stable meaning |
| ---: | --- |
| 0 | the requested bound completed successfully |
| 2 | invalid, conflicting, repeated, unbounded, or unreadable command/ROM/CGBI arguments |
| 3 | replay or portable state is malformed, unsupported, or incompatible before mutation |
| 4 | a requested breakpoint was reached |
| 5 | deterministic replay/hash divergence was detected |
| 6 | emulation, inspection, encoding, or artifact publication failed |

Codes 4 and 5 are reserved outcomes. They are never reused for generic output or configuration
errors. Replay validation and expected-hash comparison happen before a success result is emitted.

## Privacy and deterministic ownership

The report and default diagnostic bundle use hashes and sanitized metadata, not user paths. A
default bundle never contains ROM bytes, saves, boot ROMs, replay bytes, memory dumps, screenshots,
audio, tokens, environment variables, or command-line paths. There is deliberately no ROM/save/
boot-ROM include option. Optional evidence has separate include and confirmation gates documented
in [diagnostic-bundle-v1.md](diagnostic-bundle-v1.md).

The CLI creates one isolated controller session. It submits inputs, breakpoints, bounded
inspection, replay, and capture requests through the controller runner; it does not mutate core
objects from the process thread. Disabled breakpoint/trace hooks retain the same no-allocation hot
path contract described in [debug-port.md](debug-port.md), and replay identity/hash behavior is the
same contract described in [replay-format-v1.md](replay-format-v1.md).
