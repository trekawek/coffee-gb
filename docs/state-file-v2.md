# Portable StateFile format, version 2

StateFile v2 is the smallest versioned extension needed to represent a canonical hardware profile
that StateFile v1's coarse hardware tag cannot distinguish. It preserves the v1 envelope, payload,
compression, limits, checksum, type registry, and atomic apply contract. Only identity section 1 is
versioned from schema 1 to schema 2.

This document is normative together with [state-file-v1.md](state-file-v1.md). Fields not changed
below have exactly the v1 layout and meaning.

## Envelope and canonical order

The fixed 68-byte `CGBS` envelope is unchanged except:

- format version at offset 4 is unsigned `2`;
- required identity section 1 has schema version `2`;
- required payload section 2 remains schema version `1`;
- optional diagnostics section 3 remains schema version `1`.

All multibyte integers remain big-endian. Flags, raw deterministic DEFLATE, SHA-256 coverage,
encoded/decoded lengths, canonical section order, trailing-data rejection, unknown-section policy,
and every centralized allocation/work bound are unchanged. In particular, format v2 does not
consume a formerly undefined v1 flag.

## Identity section schema 2

Each present identity retains every v1 field in the same order:

1. player index and presence;
2. primary ROM SHA-256;
3. optional slot-ROM presence and SHA-256;
4. profile schema version, coarse hardware tag, bootstrap tag, and behavior flags.

It then appends:

| Width | Field |
|---:|---|
| 2 | unsigned canonical-profile-ID byte length |
| length | lowercase ASCII canonical profile ID |

The ID is nonempty, at most 32 bytes, matches `[a-z][a-z0-9-]*`, and contains printable ASCII only.
It must resolve to the authoritative registry and agree with the coarse hardware family plus CGB0
flag. Unknown, mixed-case, oversized, truncated, or contradictory IDs are rejected before payload
graph reconstruction. The bytes are inside the encoded payload and therefore covered by the
envelope SHA-256.

Current mapping:

| Canonical ID | Coarse tag | Version used for new captures |
|---|---|---:|
| `dmg` | DMG | 1 |
| `cgb` | CGB, CGB0 clear | 1 |
| `cgb0` | CGB, CGB0 set | 1 |
| `sgb` | SGB | 1 |
| `sgb2` | SGB | 2 |

StateFile v1 SGB always means canonical `sgb`; a reader must never infer `sgb2` from a boot value,
clock, display name, or an unused v1 bit. New SGB2 machine, session, and linked roots use v2. Every
active member of a linked root carries its own explicit ID.

## Payload and compatibility

Payload section schema 1 and all 91 numeric record IDs are byte-for-byte unchanged. The rational
clock remainder fits the existing MBC3 `subSecondTicks` scalar: it is interpreted against the exact
target profile during prepare. Sound buffer dimensions are likewise checked against that target
before mutation. A cross-profile file fails with typed `HARDWARE_PROFILE_MISMATCH` before the first
apply callback.

Readers continue accepting v1. A decoded v1 file retains format version 1 and re-encodes to its
exact original bytes. Existing v1 profiles continue writing v1, so their portable and protocol-v8
bytes are stable. SGB2 cannot be forced into v1: encoding is rejected rather than aliased to SGB.

## Netplay and replay boundary

Protocol v8 explicitly negotiates StateFile v1. It neither sends nor accepts StateFile v2, and its
wire bytes are unchanged. An SGB2 local linked load is rejected before linked-session construction,
and an SGB2 state is rejected before a payload is written or delivered; support requires a
separately negotiated protocol version. StateFile v2 is currently for local portable snapshots and
the explicit codec/apply seam.

There is no #315 replay format yet. Any future replay must carry canonical `sgb2` plus its exact
rational `ClockSpec`; a coarse SGB enum is insufficient.

## Golden fixture and update procedure

The committed ROM-independent fixture is
`controller/src/test/resources/state-file-v2/sgb2-session-deflate.cgbstate`:

```text
SHA-256  2d2178e6eba26a8debdacf84be144cccd1b42e50bf0dbce5c41612bcb16aa226
size     59,486 bytes
root     SESSION
profile  sgb2
```

It is generated only from Coffee GB's synthetic test ROM and contains no Nintendo code or asset.
Normal tests decode meaningful non-default facts and re-encode exact bytes; they never regenerate
the file. To intentionally regenerate from the controller module:

```bash
mvn -B -pl controller -am \
  -Dtest=StateFileV2GoldenTest \
  -DupdateStateFileV2Golden=true \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The test deliberately fails after writing and prints the candidate hash. Review the code, binary
diff, size, provenance, and hash before updating the pinned expectation.

## Extension rule

Version 2 is not a general extension bag. New required identity semantics need a new identity
section schema and, when an older reader cannot safely skip them, a new envelope version. New
optional diagnostic sections must remain bounded and cannot drive compatibility. Existing numeric
IDs, checksums, flags, and canonical mappings are immutable.
