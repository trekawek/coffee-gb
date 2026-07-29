# Portable StateFile format, version 2

StateFile v2 is the smallest versioned extension needed to represent a profile beyond StateFile
v1's coarse hardware identities and make the SGB RTC phase scalar unambiguous. V1 cannot
distinguish MGB from DMG or SGB2 from SGB, and its canonical SGB phase has a frozen historical
denominator. V2 preserves
the v1 envelope, payload bytes, compression, limits, checksum, type registry, and atomic apply
contract. Identity section 1 is versioned from schema 1 to schema 2; that explicit profile ID also
selects the RTC scalar's exact numerator domain.

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
| `sgb` | SGB | 2 |
| `sgb2` | SGB | 2 |
| `mgb` | DMG | 2 |

StateFile v1 SGB always means canonical `sgb`; a reader must never infer `sgb2` from a boot value,
clock, display name, or an unused v1 bit. Likewise, StateFile v1 DMG always means canonical `dmg`,
never `mgb`. New SGB, SGB2, and MGB machine, session, and linked roots use v2. Every active member
of a linked root carries its own explicit ID.

## Payload and compatibility

Payload section schema 1 and the original 91 numeric record IDs are byte-for-byte unchanged; the
Xploder mapper state is appended as ID 92 and the VF001 Zook mapper state as ID 93. In v2, the
MBC3 `subSecondTicks` scalar is the explicit profile's numerator-domain phase:

| Identity | Valid phase | Live increment per machine tick |
|---|---:|---:|
| `sgb` | `0..47,249,999` | 11 |
| `sgb2` | `0..4,194,303` | 1 |
| `mgb` | `0..4,194,303` | 1 |

StateFile v1 canonical SGB instead freezes that scalar as a fraction with denominator `4,194,304`.
On v1 SGB apply, after bounded detached decode but before target graph reconstruction or any live
mutation, Coffee GB converts it to v2 SGB units with
`floor((old * 47,250,000 + 2,097,152) / 4,194,304)`: nearest exact rational rounding, ties upward,
with at most half a destination-unit error. Decode, inspection, and decode-to-encode retain the
original scalar and exact v1 bytes. Values outside the old domain reject as malformed.

Sound buffer dimensions and the converted/unconverted RTC phase are checked against the exact
target profile before mutation. A cross-profile file fails with typed
`HARDWARE_PROFILE_MISMATCH` before the first apply callback.

Readers continue accepting v1. A decoded v1 file retains format version 1 and re-encodes to its
exact original bytes. DMG, CGB, and CGB0 continue writing v1, so their portable and protocol-v8
bytes are stable. SGB/SGB2/MGB cannot be forced into v1: encoding is rejected rather than silently
assigning a historical phase or coarse model identity.

## Netplay and replay boundary

Protocol v8 explicitly negotiates StateFile v1. It neither sends nor accepts StateFile v2, and its
wire bytes are unchanged. SGB, SGB2, and MGB local linked loads therefore reject before linked
session construction, and outgoing state rejects before a payload is written. A coarse incoming
SGB header rejects before held-input/ROM/state payload reads; a coarse incoming DMG header remains
canonical `dmg`, never MGB. Support requires a separately negotiated profile-aware protocol.
StateFile v2 remains the local exact-profile format and is also the only peer-state format on the
explicit protocol-v9 #349 play path. That path carries complete CGBS bytes directly, rejects
v1/legacy/native/CGBN input before graph reconstruction, validates exact target identity and root,
and commits only through the controller's two-stage frame-safe transaction.

The protocol-v9 contract in [netplay-protocol-v9.md](netplay-protocol-v9.md) allocates a new wire
major and requires direct bounded StateFile v2 checkpoints with exact profile identities. The
Phase #347 production foundation implements framing and HELLO. Phase #349 adds checkpoint
admission and atomic application only when its explicit play plan is installed; earlier opt-in
boundaries and the default v8 path still reject CHECKPOINT before proportional payload allocation.
No v8 byte or behavior changes.

There is no #315 replay format yet. Any future replay must carry the exact canonical profile ID
(including `mgb` or `sgb2`) plus its rational `ClockSpec`; a coarse enum is insufficient.

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
