# SGB and hardware-profile Phase 0 baselines

This document describes the evidence and deterministic fixtures established by issue #340. The
phase is observational: no emulator behavior, clock, boot value, packet parser, renderer, input
contract, or portable-state schema is changed. Known differences are recorded for the later phases
of #317 rather than repaired here.

## Checked-in sources of truth

The complete command inventory is
[`controller/src/test/resources/sgb-baselines/sgb-command-matrix.tsv`](../controller/src/test/resources/sgb-baselines/sgb-command-matrix.tsv).
It has one row for every ID representable by the documented five-bit command field: `0x00` through
`0x1f`. IDs `0x00` through `0x19` are the 26 classes currently recognized by
`Commands.toCommand`; `0x1a` through `0x1f` are explicit reserved/unknown rows. Aliases are recorded
in the row instead of creating duplicate IDs.

The status breakdown is:

| Status | Rows | Meaning in this matrix |
| --- | ---: | --- |
| `implemented` | 13 | State and an observable Coffee GB effect are exercised; parser presence alone is insufficient. |
| `partial` | 4 | Some packet/state behavior exists but a documented effect is missing. |
| `intentionally-unsupported` | 9 | The command depends on an SNES CPU/APU/PPU or firmware service Coffee GB does not emulate. The row still records packet consumption. |
| `unknown` | 6 | Reserved IDs whose behavior is only partially known from one firmware revision. |

Each row separately states documented packet count, current collector validation, command-specific
length validation, persisted state, DMG-screen effect, border effect, multiplayer effect,
SNES dependency, save/restore coverage, evidence, and uncertainty. `SgbInventoryGuardTest` rejects
missing or duplicate IDs, invalid statuses, blank evidence/uncertainty cells, invalid evidence keys,
and any drift between the matrix and the production command registry.

The model/clock decision inventory is
[`controller/src/test/resources/sgb-baselines/model-decision-inventory.tsv`](../controller/src/test/resources/sgb-baselines/model-decision-inventory.tsv).
Its 67 rows classify every matching production source file by stable symbol/context, current
consumer, model set, evidence, uncertainty, and the earliest owning #317 phase. The scanner covers
`core`, `controller`, and `swing` production Java/Kotlin sources, ignores only package/import/comment
lines, and compares the complete `(category, path, occurrence count)` map. A new match cannot be
hidden by preserving one global hand-count. A second committed table hashes the ordered,
whitespace-normalized matching source contexts per file, so adding or replacing a decision while
coincidentally preserving its count also requires a reviewed inventory update; line numbers are not
part of the contract.

| Category | Files | Normalized occurrences |
| --- | ---: | ---: |
| `GAMEBOY_TYPE` | 12 | 58 |
| `CGB_FLAG` | 37 | 412 |
| `SGB_FLAG` | 8 | 36 |
| `CGB0` | 9 | 44 |
| `BOOTSTRAP` | 11 | 77 |
| `CLOCK` | 12 | 27 |
| `SGB_BORDER` | 7 | 41 |
| `MEALYBUG` | 9 | 43 |
| `CODEBREAKER` | 8 | 52 |
| `ROM_MODEL` | 8 | 41 |
| `PORTABLE_PROFILE` | 3 | 22 |

The 67 files comprise 32 hardware-policy, 15 compatibility-adapter, 7 configuration, 5
portable-state-adapter, 4 platform-adapter, and 4 legacy-importer rows. Fifty-nine rows belong first
to profile migration (#343), five clock/serial/audio rows to SGB2 timing (#344), two SGB display or
palette rows to command completion (#341), and Joypad multiplayer to #342. No current decision is
owned solely by #345 because MGB is not representable yet; #345 extends the registry produced by
#343 rather than changing an existing MGB branch.

## Public technical evidence

All sources below were accessed on 2026-07-25. Links are pinned to immutable commits.

| Evidence key | Version and license | Supports |
| --- | --- | --- |
| `PANDOCS_SUMMARY` | [Pan Docs `fe246067`](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Summary.md), [CC0-1.0](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/LICENSE) | The named `0x00..0x19` registry, packet counts, and high-level effects. |
| `PANDOCS_PACKET` | [command packet](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Packet.md) | JOYP framing, 16-byte packets, packet-count field, and transfer order. |
| `PANDOCS_PALETTE` | [palette commands](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Palettes.md) | Direct palettes, `PAL_SET`, `PAL_TRN`, and `PAL_PRI`. |
| `PANDOCS_ATTRIBUTE` | [attribute commands](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Attribute.md) | `ATTR_BLK/LIN/DIV/CHR`, attribute files, and transfer/select behavior. |
| `PANDOCS_SOUND` | [sound commands](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Sound.md) | SNES APU-dependent `SOUND` and `SOU_TRN`. |
| `PANDOCS_SYSTEM` | [system commands](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_System.md) | Firmware, data/execute, and mask commands. |
| `PANDOCS_MULTIPLAYER` | [multiplayer command](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Multiplayer.md) | `MLT_REQ`, player-count modes, and selected-player feedback. |
| `PANDOCS_BORDER` | [border commands](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Border.md) | Character, picture, attribute, and object transfers. |
| `PANDOCS_UNDOCUMENTED` | [undocumented commands](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/SGB_Command_Undocumented.md) | SGB1v2 disassembly observations for `0x1a..0x1f`; not generalized to other revisions. |
| `COFFEEGB_BASELINE` | This PR's real JOYP, renderer, input, model, and StateFile tests | What the current implementation actually consumes, stores, renders, and resumes. |

The current 105-frame border transition is also traced to
[SameBoy `213a12c`, `Core/sgb.c`](https://github.com/LIJI32/SameBoy/blob/213a12ce93d66b105a113debd9396306066a7cfc/Core/sgb.c),
licensed under Expat outside its documented exceptions. SameBoy labels the measurement as SGB2 and
allows approximately two frames of uncertainty. It is evidence for the origin of Coffee GB's
current approximation, not proof that SGB and SGB2 are identical.

Known source/implementation disagreements are deliberately visible:

- `ATTR_LIN`, `ATTR_DIV`, and `PAL_PRI` have parser classes but no display-side effect.
- Independent player input is absent. `MLT_REQ` rotates IDs, while selected players above one read
  released button lines even when the test fixture has independent input ready.
- An incomplete multi-packet command consumes the next complete packet as continuation. A header
  with packet count zero leaves the collector unable to complete until its state is reset. These are
  baselines, not desired validation semantics.
- Reserved `0x1a..0x1f` inputs are forced to one packet by Coffee GB. Public disassembly evidence is
  SGB1v2-specific and does not justify a generic implementation.
- Skip boot currently gives SGB the DMG `BC=0x0013` value; Pan Docs' pinned
  [power-up table](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/Power_Up_Sequence.md)
  documents SGB `C=0x14`. Phase 0 locks the current value without changing it.
- `TICKS_PER_SEC`, `TICKS_PER_FRAME`, host pacing, audio conversion, serial delays, and state bounds
  use the current global clock. Pan Docs' pinned
  [timer discussion](https://github.com/gbdev/pandocs/blob/fe246067b695b5404a4a6a47efb4fd6d921ececb/src/Timer_and_Divider_Registers.md)
  is reference evidence; exact per-session clock ownership remains #343/#344.

## Deterministic ROM-independent fixtures

The packet builder creates 16-byte packets and one-to-seven-packet commands, then drives the real
`Joypad` JOYP pulse decoder and `SuperGameboy` collector. It covers maximum payload assembly,
fixture-side invalid counts/lengths/bytes, incomplete bit transfer and restart, incomplete
multi-packet continuation, zero-count poisoning, reserved IDs, and a following valid command. No ROM
or host input/rendering API is involved.

The renderer fixture synthesizes all 160x144 DMG pixels, both 4 KiB character halves, the 32x28
picture map and border palettes, all 512 four-color system palettes, all 45 20x18 attribute files,
an active attribute map, all mask modes, and a nonzero border fade. It asserts a 256x224 output,
detached component-state ownership, no delivered-buffer leakage, and exact continuation after
capture/restore. Hash input is: array count, then each array's signed int32 length and signed int32
elements, all big-endian. Colors are Coffee GB's current packed int values; there is no host image,
locale, `hashCode`, clock, or unordered iteration.

| Renderer value | SHA-256 |
| --- | --- |
| synthetic DMG input | `9d7dfe9cebb3e09c550951ea9df3f56a1213a526c84166ed0f9f83d2d1c47795` |
| two character halves | `841417d7c8b916a817a6a9288258d8557d99e0bf34de7a46a546352151b0405b` |
| picture map/palettes | `993b83ca5c470408a3c850454f5023f7eb62db1b7b17b7a0f72e11a8f4704cc1` |
| system palette input | `5c44194d5284b6fbe40e57779244590f87c3174fa9ba7a677996bd31a32c1e96` |
| attribute-file input | `839341e286cfee7ce8747db2f10d9f12322d1dccddc416a873f0768bae8df2e0` |
| cancel output | `f10dda7240118f9fa804f773b03f48046b2e1417eca7ab6f6b1f74c9e26033ca` |
| black output | `5d672f18baa7f9ce20f820a623d0e97478b5fa01c8d8c17125cb3698ad89f9dc` |
| color-zero output | `75e7f7358eff1c7b2e9f61e91f279f8a302ac7b1562898326ea33b93055d780d` |
| restored continuation | `c78c334dbeb2a0cc9aab7f087fe0d9cdb46d292417eedb222355108880bd73ae` |

The model fixture builds one valid-checksum, 32 KiB synthetic ROM and runs exactly
`Gameboy.TICKS_PER_FRAME` ticks under DMG, CGB, CGB0, and SGB with skip boot. The synthetic ROM SHA
is `f89f3802d47dd31da0db6b5656ed5098194e85020ba735fb44c1c9d4f9043eee`. The exact register,
frame-event, frame-hash, and uncompressed StateFile-v1 hashes are reviewable in
`model-baselines.tsv`; notably SGB currently starts with the same DMG registers and emits both one
DMG and one SGB event.

The StateFile harness drives real PAL/ATTR/CHR/PCT/MASK/MLT commands, performs production VRAM
transfers and border progress, holds `A+START`, then captures with one packet waiting in the
two-packet collector and the next JOYP transfer 43 bits complete. Decode/apply reproduces the exact
portable bytes and continuation:

| State fixture value | SHA-256 |
| --- | --- |
| capture StateFile | `d4dedf96ac60775f14b15c3a88909313e948f85ccc71a378448e0d68af418323` |
| capture frame | `089c22de4291e57ff45098eb3bfbdaa71b81aecc30b5fa6c1d72a58ea7e6d063` |
| continuation StateFile | `f49abe625aebac7d698ee5ecee0100da50083f5f128dd4db613516ff026fd9d0` |
| continuation frame | `51dd2ed6cf24f04a64ea81af4e9768056ea48074445476c96d6dca015fd2e342` |

The fake four-player source is test-only and platform-neutral. It provides independently mutable
sets, deterministic sample counts, disconnect-to-release behavior, and stable sorted diagnostics.
It documents the gap without introducing the production `PlayerInputSource` assigned to #342.

## Deferred work

- #341 validates and completes the practical command set; none of its semantics are changed here.
- #342 introduces independent production player input and desktop mapping.
- #343 introduces stable immutable profiles and per-session `ClockSpec` with exact current parity.
- #344 adds SGB2 only after model/timing evidence and long-run clock tests.
- #345 adds MGB and the repeatable profile-extension process.

No Phase-0 artifact is real-hardware proof by itself. Synthetic hashes are implementation behavior
locks; external technical references and redistributable conformance inputs remain separately
identified under [test-fixture-provenance.md](test-fixture-provenance.md).
