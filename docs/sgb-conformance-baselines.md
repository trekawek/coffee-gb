# SGB conformance baselines and practical-command contract

Issue #340 established the observational Phase 0 evidence and deterministic fixtures. Issue #341
used those artifacts to complete the platform-neutral practical command set, issue #342 added
independent local SGB P1-P4 host input, and issue #343 established immutable per-session hardware
profiles and clocks. Issue #344 added the evidence-backed `sgb2` identity and exact SGB-family
clock contract. Issue #345 adds the evidence-backed `mgb` identity and an enforceable contributor
extension process. Neither phase changes the practical SGB command set. The original 91 portable
record IDs and every existing StateFile-v1 field remain unchanged; mapper records append after them.

## Executable sources of truth

Compatibility is enforced at behavior boundaries. `CommandsValidationTest` covers every practical
and unsupported command ID plus unknown and malformed packets. The practical-command, renderer,
state-continuation, four-player, and hardware-model tests exercise the resulting effects. The ROM
profiles remain the primary external compatibility signal.

Source-token occurrence counts, source fingerprints, and a second command-registry spreadsheet
were deliberately retired. They made harmless renames and refactors require fixture updates without
running an emulated instruction or validating an SGB effect. The production parser and behavioral
tests are now the single executable command registry.

## Public technical evidence

The command sources below were accessed on 2026-07-25. Links are pinned to immutable commits.

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

The profile/timing evidence was accessed on 2026-07-26 and is pinned in
[hardware-profiles.md](hardware-profiles.md). Pan Docs commit
[`37526fad2d47c89fa6485fce0740c594686598b1`](https://github.com/gbdev/pandocs/tree/37526fad2d47c89fa6485fce0740c594686598b1),
CC0-1.0, establishes the SGB/SGB2 clock-source distinction and post-boot A/C values. Gekkio's
_GB: Complete Technical Reference_ revision 188 (CC BY-SA 4.0; downloaded SHA-256
`b147d6c49fb27ea8e803da9956e91ac1e47fd7bdedd7742ca1ffab63c7daaf07`) corroborates ICD2's
divide-by-five path and the exact SGB2 crystal. SNESdev Wiki `Timing` revision 1467
([permanent revision](https://snes.nesdev.org/w/index.php?title=Timing&oldid=1467), CC0-1.0)
specifies the exact NTSC SNES source as `945/44 MHz`. GBCTR prints a disagreeing “21.447 MHz”
approximation; the disagreement is retained rather than hidden by rounding.

The current 105-frame border transition is also traced to
[SameBoy `213a12c`, `Core/sgb.c`](https://github.com/LIJI32/SameBoy/blob/213a12ce93d66b105a113debd9396306066a7cfc/Core/sgb.c),
licensed under Expat outside its documented exceptions. SameBoy labels the measurement as SGB2 and
allows approximately two frames of uncertainty. It is evidence for the origin of Coffee GB's
current approximation, not proof that SGB and SGB2 are identical.

The Phase 1 interpretations and remaining disagreements are deliberately visible:

- `ATTR_LIN` implements Pan Docs' x `0..19`, y `0..17`, and last-entry-wins rules. `ATTR_DIV`
  assigns lower coordinates to above/left, the named coordinate to the line palette, and higher
  coordinates to below/right. These choices also agree with the pinned SameBoy implementation;
  Pan Docs supplies the authoritative coordinate bounds where SameBoy's guard is permissive.
- `PAL_PRI` is stored and restored, and every later game `PAL_*` update remains deterministic. Its
  documented visible effect requires an SNES firmware palette selected by the user. Coffee GB has
  no such UI/source, so the command remains `partial`: no fictitious override is invented.
- Pan Docs says bytes without an indicated purpose are ignored, while several command tables label
  specific fields “not used (zero)”. The validator accepts arbitrary values only in the former
  category and requires zero in the latter. Direct command-validation tests cover the distinction.
- A PCT map entry should select palette `4..6` in Pan Docs. Coffee GB retains its prior safe use of
  the complete three-bit palette field and the real-game `0x2ff` transparent-tile workaround from
  issue #174; narrowing either would break established continuation tests. The documented priority
  bit must be zero and is rejected before a new or restored border transfer changes state.
- `MLT_REQ` controls `0`, `1`, and `3` select P1, rotating P1/P2, and rotating P1-P4 respectively,
  with both JOYP selector lines high returning IDs `0xf..0xc`. Reducing the mode masks the selected
  zero-based player by the new control, matching pinned Pan Docs. Control `2` remains the prior
  explicitly tested compatibility state (`1/2 -> 2`, `0/3 -> 0`) because the pinned public source
  does not document it; no new hardware claim is made for that value.
- Reserved `0x1a..0x1f` preserve Coffee GB's conformance-tested ICD behavior: each physical row is
  ignored independently even when its untrusted count bits advertise more rows. This keeps the
  CasualPokePlayer extended-protocol result unchanged. Public disassembly evidence is
  SGB1v2-specific and does not justify an effect or a generic multi-packet schema.
- Skip boot now follows the pinned SGB-family values: SGB uses `AF=0x0100`, SGB2 uses
  `AF=0xff00`, and both use `BC=0x0014`, `DE=0`, `HL=0xc060`. Their DIV value remains unknown;
  Coffee GB's retained `0xabcc` value is explicitly a deterministic policy, not a hardware claim.
- The exact SGB Game Boy master is `(1,890,000,000 / 88) / 5 = 47,250,000 / 11` ticks/second.
  SGB2 is `20,971,520 / 5 = 4,194,304` ticks/second. Both use the 70,224-tick LCD frame, yielding
  exact cadences `140,625 / 2,299` and `262,144 / 4,389` frames/second. Deprecated integer clock
  adapters throw for a rational rate rather than report a false rounded value. Protocol v8 remains
  StateFile-v1-only and rejects current exact-clock SGB/SGB2 before payload transmission.

## Phase 1 framing, mutation, and state contract

The production path is still `JOYP -> Joypad -> SuperGameboy -> Commands -> component event`.
`Commands.parse` is total and side-effect-free. It accepts only one to seven complete 16-byte rows,
requires byte-valued fields and an exact header count, and validates all command-specific counts,
payload arithmetic, indices, enums, documented reserved bits/bytes, and coordinates before an
event is constructed. Malformed, unsupported, and unknown commands produce no component event and
therefore cannot partially mutate display, input, border, or transfer state. They are logged once
per completely collected command, never from a frame loop.

Count zero is rejected immediately and resets the collector. A complete continuation packet has no
distinguishing header, so the collector does not guess whether its payload begins a new command.
Instead, a second JOYP start/reset while a physical packet is already active emits an explicit
receiver-restart signal. This clears the incomplete multi-packet collector; a following valid
packet starts cleanly. A normal start after a completed row continues the declared command. Both
the Joypad bit phase and SuperGameboy collector rows are detached state, so a captured valid
in-progress transfer resumes exactly.

Only practical transfer commands (`PAL_TRN`, `CHR_TRN`, `PCT_TRN`, and `ATTR_TRN`) start the existing
three-frame ICD2 capture. A later valid practical transfer atomically replaces an older pending
one and restarts its countdown. Malformed, unsupported, and receiver-restart inputs do not disturb
an already accepted transfer. The third frame must contain exactly 4096 byte-valued entries and is
validated before delivery; invalid data clears only that attempted transfer. Consumers prepare
complete palette/attribute tables before committing them. Command packet rows, transfer payloads,
active rows, and emitted frame arrays are cloned or newly allocated at their ownership boundary.

The same allowlist is an apply-time invariant. A restored delayed capture must contain one of those
four practical commands, a countdown `1..3`, and no already-committed payload. `SOU_TRN`/`DATA_TRN`
compatibility records are never executable. A restored pending picture must be `PCT_TRN` with one
complete 4096-byte payload that passes the same priority-bit validation; a nonzero border-animation
count requires that picture. Malformed portable, MachineSnapshot, or imported compatibility state
is rejected before live mutation rather than normalized into a command. The released 1.7.13 and
1.7.14 fixtures contain no such invalid pending transfer and remain supported.

Historical display records may contain a null `systemPalettes` row. This is the one compatibility
normalization in this path: restore replaces each such row with a newly owned four-zero palette
before `PAL_SET` can select it. Present rows and active palettes are independently cloned, so later
game palette commands cannot alias or mutate the normalized system table.

`MASK_EN` is defined at produced-frame boundaries: cancel resumes newly rendered frames, freeze
emits nothing and leaves the frontend's last owned frame visible, black emits black game-window
pixels, and color-zero renders palette-0 color zero. The current border remains visible in all four
modes. `PAL_SET` and `ATTR_SET` cancel the mask only when their documented flag is set.

PAL_PRI reuses bit `0x100` of the existing non-negative `SgbDisplayState.borderFade` scalar; the
actual fade stays in its original low-byte range `0..32`. This is a compatible value supplement,
not a StateFile schema or record-ID change: old values decode with priority disabled, and legacy
Java descriptors are unchanged.

## Phase 2 independent-input contract

`PlayerInputSource` is a platform-neutral core service returning one deeply owned immutable sample
for exactly zero-based slots `0..3` (P1-P4). Invalid indices are rejected. Joypad samples the whole
value once at its emulator clock `tick()` boundary; JOYP reads never call AWT or SDL and repeated or
rapid reads between ticks see the same latch. An unassigned source is all released. The legacy
`ButtonPressEvent`/`ButtonReleaseEvent`, `Gameboy.pressedButtons`, and protocol-v8 input retain their
P1 meaning for DMG/CGB, agents, linked emulators, and historical state. The desktop bridge emits
logical-slot transitions for all four players. A Basic/local machine reads desktop P1 only from
the atomic tick latch; a `LinkedController` translates logical P1 into its existing frame-owned
protocol stream. Direct legacy events remain available to agents, replay, protocol input, and
historical callers without mirroring desktop P1 into that ownership model.

Desktop keyboard and gamepad adapters contribute through opaque source handles. Per-player state is
the set union of those handles, so autorepeat is idempotent and releasing/disconnecting one device
cannot cancel the same button held by another. Focus loss, ROM/session replacement, reassignment,
device replacement, thread exit, and emulator stop release their scoped latches. SDL polling is
behind an in-memory test seam, handles at most four assignments, and binds by a logged SHA-256 of
the SDL GUID/path/name rather than enumeration index. A path-less SDL backend adds the current
connection's instance ID so identical pads cannot collide. Array-order churn cannot move a held
state; an OS path change or path-less reconnect is conservatively treated as device replacement.
Every newly enumerated attached pad is logged once per connection even when unassigned. Keyboard,
mouse, and P1-gamepad tilt share one last-writer lifecycle bridge; focus loss, controller/ROM
replacement, thread exit, and stop recenter it, reset keyboard ramp state, and prevent stopped AWT
listeners from relatching. Right-stick tilt and rumble remain P1-only.

The Joypad's mode, selected player, JOYP selector/filter, and packet receiver already round-trip in
the stable component record. Physical P1-P4 input remains a service: MachineSnapshot and StateFile
restore never resurrect historical presses. Rollback replay applies its existing P1 event history
with `PlayerInputSource.RELEASED`. Every live and replay Game Boy owned by `LinkedController` uses
that released source: protocol v8 cannot transmit local SGB P2-P4, so those slots are deliberately
unavailable in linked/network mode pending a versioned capability. Local SGB slots are never
encoded as linked emulator players, so StateFile v1 and protocol v8 bytes do not change. Apply-time semantics accept
only control/player pairs reachable by production (`0:0`, `1:0..1`, `2:{0,2}`, `3:0..3`) before
the first live mutation.

## Deterministic ROM-independent fixtures

The packet builder creates 16-byte packets and one-to-seven-packet commands, then drives the real
`Joypad` JOYP pulse decoder and `SuperGameboy` collector. It covers maximum payload assembly,
fixture-side invalid counts/lengths/bytes, incomplete bit transfer and explicit restart, abandoned
multi-packet recovery, count-zero recovery, unsupported/reserved IDs, and a following valid command.
No ROM or host input/rendering API is involved.

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

The model fixture builds one valid-checksum, 32 KiB synthetic ROM and runs exactly each profile's
`ClockSpec.controllerTicksPerFrame()`: 69,905 under DMG/MGB/CGB/CGB0 and the full 70,224-tick LCD
frame under SGB/SGB2, all with skip boot. The synthetic ROM SHA
is `f89f3802d47dd31da0db6b5656ed5098194e85020ba735fb44c1c9d4f9043eee`. The exact register,
frame-event, frame-hash, and portable StateFile hashes are reviewable in `model-baselines.tsv`.
DMG/CGB/CGB0 rows are unchanged. MGB's new state hash is
`7c93ea0f6f4912084e978e5f467226dbb1a7a4fd4111b8e32f253793ea55a48e`; its frame hash is the
unchanged DMG value `9a7c6c11a4190432f0ba3ddd817318ea63582040b8e56f30d80bfab968645b70`.
Only the cited `AF=0xffb0` skip default and explicit StateFile-v2 `mgb` identity distinguish this
synthetic baseline from DMG. The SGB row uses StateFile v2 so its exact-clock RTC scalar cannot
be confused with the frozen v1 denominator; this revision changes only that portable metadata hash,
not registers or frame output. The SGB2 row differs by its cited `AF=0xff00` identity and
StateFile-v2 metadata. Both emit one DMG frame and one SGB output event.

The StateFile harness drives real PAL/ATTR/CHR/PCT/MASK/MLT commands plus `ATTR_LIN`, `ATTR_DIV`, and
`PAL_PRI`. It performs the production three-frame VRAM transfer and border progress, holds
`A+START`, then captures with one packet waiting in a valid
two-packet `ATTR_LIN` collector and the next JOYP transfer 43 bits complete. Decode/apply reproduces
the exact portable bytes and continuation:

| State fixture value | SHA-256 |
| --- | --- |
| capture StateFile v2 | `76bb387ea0ae6f61fee473101c1855ea801e460cfd205682548626f43f8575d3` |
| capture frame | `089c22de4291e57ff45098eb3bfbdaa71b81aecc30b5fa6c1d72a58ea7e6d063` |
| continuation StateFile v2 | `bb170bafca8e074e59dfc18d41229e3331c802710c9aef1778d50b3197434f51` |
| continuation frame | `51dd2ed6cf24f04a64ea81af4e9768056ea48074445476c96d6dca015fd2e342` |

The Phase 0 fake source has been removed. The reusable production `PlayerInputSource`, immutable
snapshot, and source-union hub are exercised directly with four non-overlapping patterns, invalid
indices, alias attempts, disconnects, mode transitions, tick sampling, rewind/StateFile restore,
rollback replay, keyboard mappings, and fake no-SDL devices.

## Profile ownership and deferred work

The authoritative registry is `HardwareProfileRegistry`; its permanent IDs are `dmg`, `cgb`,
`cgb0`, `sgb`, `sgb2`, and `mgb`. `HardwareProfileRegistryTest`, `HardwareProfileGameboyTest`, the
model baselines, and StateFile/netplay tests cover its externally relevant behavior. The full
ownership, CLI, StateFile-v1/v2, protocol-v8, evidence, and extension contract is documented in
[hardware-profiles.md](hardware-profiles.md) and [state-file-v2.md](state-file-v2.md). The committed synthetic SGB2 SESSION golden is 59,486 bytes
with SHA-256 `2d2178e6eba26a8debdacf84be144cccd1b42e50bf0dbce5c41612bcb16aa226`.

- #345 adds MGB and the repeatable profile-extension process. The separate AGB-in-GB-mode evidence
  audit [#391](https://github.com/trekawek/coffee-gb/issues/391) remains open; it adds no production profile.
- #315 `CGBR` v1 consumes the canonical profile/clock identity and four-slot input seam; its exact
  timing and privacy contract is documented in [replay-format-v1.md](replay-format-v1.md).

No Phase-0 artifact is real-hardware proof by itself. Synthetic hashes are implementation behavior
locks; external technical references and redistributable conformance inputs remain separately
identified under [test-fixture-provenance.md](test-fixture-provenance.md).
