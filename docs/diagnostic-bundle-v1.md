# Diagnostic bundle v1

## Purpose and envelope

`coffee-gb-cli --bundle FILE` writes a bounded ZIP evidence package for local inspection or an
explicit bug-report attachment. A bundle is never uploaded by Coffee GB. Its manifest schema is
`coffee-gb/diagnostic-bundle-manifest`, version 1, and every possible entry has a fixed safe name;
callers cannot supply an archive entry name.

The writer completes all bounded encoders first, writes a forced sibling temporary file, and
publishes the finished ZIP without replacing an existing destination. An encoding or publication
failure leaves no successful bundle result. Entry names are unique, use `/`, contain
no empty, `.` or `..` segment, and are emitted in the format's canonical order. Entries are regular
files only—there are no symlinks, external attributes, comments, encryption, nested archives, or
host paths.

## Default contents

A default bundle contains exactly these logical entries:

| Entry | Media type | Contents |
| --- | --- | --- |
| `manifest.json` | `application/json` | self-describing manifest and entry inventory |
| `report.json` | `application/json` | canonical headless result with sensitive payloads redacted |
| `logs.ndjson` | `application/x-ndjson` | bounded, sanitized diagnostic events |

`manifest.json` has the fixed top-level field order `schema`, `version`, `application`, `runtime`,
`configuration`, `rom`, `execution`, `privacy`, and `entries`. Application/runtime include Coffee
GB, JVM, and OS names/versions useful for reproduction. Configuration contains only canonical
hardware/bootstrap/SGB choices, numeric bounds, and boolean feature choices—not raw arguments or
paths. ROM metadata contains SHA-256, bounded parser-sanitized header metadata, and size, never ROM
bytes or a source name. Execution contains termination, positions, and deterministic hashes.

The `privacy` object enumerates both included sensitive categories and exclusions. Every entry
descriptor records `name`, `mediaType`, byte count, SHA-256, and whether the entry is sensitive.
The manifest does not describe a file that is absent, and its descriptor values are computed from
the exact stored entry bytes. The manifest itself is excluded from its own entry list so it has no
recursive hash definition.

`logs.ndjson` contains stable event codes and sanitized values. It has fixed count/byte limits and
does not copy exception messages, stack traces, environment variables, complete command lines,
paths, tokens, ROM strings outside the bounded header fields, or arbitrary logger output.

## Structural exclusions

The following data is absent by default and is not representable by any bundle API or CLI flag:

- ROM bytes, slot-ROM bytes, and ROM archive contents;
- battery saves and portable save directories;
- bundled, external, or extracted boot-ROM bytes;
- filesystem paths, home/user directories, environment variables, and command-line text;
- credentials, invitations, peer secrets, authorization headers, and tokens.

There is deliberately no “include ROM,” “include save,” “include boot ROM,” “include path,” or
“include token” option. The bundle model accepts typed evidence, not an arbitrary map of entry
names to bytes, so those exclusions cannot be bypassed by choosing a filename.

Replay bytes, memory dumps, PNG screenshots, and WAV audio are also excluded from a default bundle.
They are available only through the two-gate categories below. A `report.json` in a default bundle
omits requested memory bytes even if the same command explicitly writes them to stdout or a
standalone `--json-out` file.

## Two-gate optional evidence

Each optional content class needs both its specific request and the independent
`--confirm-sensitive-bundle` flag on the same invocation. Confirmation by itself is invalid, as is
an include flag without `--bundle`.

| Request gate | Additional entry | Rules |
| --- | --- | --- |
| `--bundle-include-media` | `screenshot.png` and/or `audio.wav` | Includes only bounded media captured by this run |
| `--bundle-include-memory` | `sensitive/memory.json` | Also requires one or more bounded `--memory` requests |
| `--bundle-include-replay` | `replay.cgbreplay` or `sensitive/replay.cgbreplay` | Replay command only; includes the exact replay input |

For example:

```bash
java -jar cli/target/coffee-gb-cli.jar replay \
  --rom game.gb --replay reproduction.cgbreplay --max-ticks 100000000 \
  --bundle diagnostics.zip \
  --bundle-include-replay --bundle-include-media \
  --confirm-sensitive-bundle
```

The replay gate has one public spelling but two manifest categories. A `BOOT_REFERENCE` CGBR with
no embedded StateFile is classified `SAFE_REPLAY`, recorded under consent category `REPLAY`, and
stored as `replay.cgbreplay`. An `EMBEDDED_SESSION_STATE` CGBR can contain captured machine memory;
it is classified `RAW_REPLAY`, recorded under consent category `RAW_REPLAY`, and stored as
`sensitive/replay.cgbreplay`. The exact bytes are never transformed to make a raw replay appear
safe. Unsupported or undecodable replay state fails before a bundle is published.

Media may reveal copyrighted game graphics/audio and memory can contain game or user-derived
data, so the confirmation gate applies even when the same command also requested a standalone PNG,
WAV, or JSON report. Consent is per invocation and is not saved in settings, inferred from an
existing output file, or carried to a later run.

## Consumption and verification

Treat a bundle as untrusted input. A consumer should reject an unsupported manifest version,
duplicate or unexpected names, missing descriptors, size/hash mismatches, invalid UTF-8/JSON,
unsafe ZIP names, entry-count/size overflows, trailing data, and sensitive entries not declared in
`privacy.includedSensitive`. The manifest inventory is authoritative only after every listed entry
has been bounded and SHA-256 verified.

Sharing is a user action. Before attaching a bundle, inspect `privacy`, `entries`, and any
`sensitive/` entry. Hashes identify content and can themselves be correlatable; application/JVM/OS
versions also fingerprint a runtime. Delete a bundle using normal filesystem controls when it is
no longer needed—Coffee GB has no upload, retention, or remote-deletion service.

The command, stdout/stderr, exit-code, and standalone artifact contract is documented in
[headless-cli.md](headless-cli.md). Replay identity and embedded-state rules are documented in
[replay-format-v1.md](replay-format-v1.md).
