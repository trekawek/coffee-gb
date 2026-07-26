# Hardware profiles and session clocks

## Permanent identity and ownership

Every `GameboyConfiguration` resolves one registered immutable `HardwareProfile` before a
`Gameboy` is constructed or ticks. There is no mutable global current profile. The permanent
lowercase-ASCII IDs, in registry order, are:

| ID | Display name | Family/revision | Game Boy-side capabilities |
|---|---|---|---|
| `dmg` | Game Boy (DMG) | DMG / `dmg` | monochrome LCD, serial link |
| `cgb` | Game Boy Color (CGB) | CGB / `cgb` | color/CGB mode, double speed, infrared, serial link |
| `cgb0` | Game Boy Color (CGB revision 0) | CGB / `cgb0` | CGB capabilities plus existing revision-zero boot/OAM policy |
| `sgb` | Super Game Boy | SGB / `sgb` | SGB commands, multiplexed input, border, DMG-side serial |
| `sgb2` | Super Game Boy 2 | SGB / `sgb2` | the same currently evidenced Game Boy-side capabilities as `sgb` |

IDs are non-ordinal and permanent. Canonical lookup accepts only these exact strings. Persisted
settings additionally accept the finite historical aliases `DMG`, `CGB`, `CGB0`, and `SGB`;
notably, `SGB` always canonicalizes to `sgb`, never `sgb2`, and no uppercase `SGB2` alias exists.
Display names, enum ordinals, mixed case, and arbitrary aliases are never identities. Unknown IDs
fail before core construction and list every supported ID.

`HardwareCapabilities`, `BootSpec`, and `ClockSpec` are immutable profile-owned values. Mutable
arrays, callbacks, host clocks, event buses, and UI/device values are absent. The deprecated
three-value `GameboyType` remains a source adapter: both `sgb` and `sgb2` map back to coarse
`GameboyType.SGB`, while that coarse value maps forward only to `sgb`. Configuration copies for
restore, rollback, boot templates, linked sessions, and transaction rollback retain the exact
registered profile instance.

The executable registry contract is
[`hardware-profile-matrix.tsv`](../controller/src/test/resources/sgb-baselines/hardware-profile-matrix.tsv).
Its guard checks identity, capabilities, exact clocks/cadence, boot selectors, skip defaults, and
registry order. `SgbInventoryGuardTest` separately fingerprints every production profile/clock
decision site.

## Evidence and exact SGB-family clocks

Evidence was accessed 2026-07-26. No referenced or generated Nintendo ROM bytes are committed.

- **Pan Docs commit `37526fad2d47c89fa6485fce0740c594686598b1`**, CC0-1.0:
  [`SGB_Functions.md`](https://github.com/gbdev/pandocs/blob/37526fad2d47c89fa6485fce0740c594686598b1/src/SGB_Functions.md)
  says NTSC SGB derives its Game Boy master clock from the approximately 21.477 MHz SNES master,
  runs approximately 2.41% fast, and SGB2 uses a separate approximately 20.972 MHz crystal at the
  correct Game Boy speed.
- The same immutable Pan Docs revision's
  [`Power_Up_Sequence.md`](https://github.com/gbdev/pandocs/blob/37526fad2d47c89fa6485fce0740c594686598b1/src/Power_Up_Sequence.md)
  and
  [`SGB_Unlocking.md`](https://github.com/gbdev/pandocs/blob/37526fad2d47c89fa6485fce0740c594686598b1/src/SGB_Unlocking.md)
  identify SGB/SGB2 by `C=0x14`, distinguish them with `A=0x01`/`0xff`, state that their boot ROMs
  differ by that final value, and leave their post-boot DIV value unknown.
- **Gekkio, _GB: Complete Technical Reference_, revision 188, 2026-07-14**, CC BY-SA 4.0,
  [`gbctr.pdf`](https://gekkio.fi/files/gb-docs/gbctr.pdf), downloaded SHA-256
  `b147d6c49fb27ea8e803da9956e91ac1e47fd7bdedd7742ca1ffab63c7daaf07`, corroborates that ICD2
  divides the SNES source by five and that SGB2 uses a 20.971520 MHz crystal divided by five.
- **SNESdev Wiki, “Timing”, revision 1467 (2026-05-14)**, CC0-1.0,
  [permanent revision](https://snes.nesdev.org/w/index.php?title=Timing&oldid=1467), records the
  NTSC SNES master as `945/44 MHz`. Pan Docs' immutable approximate value independently
  corroborates it.

The resulting exact rates are:

```text
NTSC SGB SNES master = 945/44 MHz = 945,000,000/44 Hz
SGB Game Boy master  = (945,000,000 / 44) / 5
                      = 47,250,000 / 11 ticks/second

SGB2 crystal         = 20,971,520 Hz
SGB2 Game Boy master = 20,971,520 / 5
                      = 4,194,304 ticks/second

Game Boy LCD frame   = 70,224 master ticks
SGB frames/second    = (47,250,000 / 11) / 70,224 = 140,625 / 2,299
SGB2 frames/second   = 4,194,304 / 70,224 = 262,144 / 4,389
```

The technical reference prints “21.447 MHz” for NTSC SNES, which disagrees with Pan Docs' 21.477
MHz and the published theoretical expression. Coffee GB uses the exact theoretical expression,
records the disagreement, and never substitutes a rounded integer frequency.

`ClockSpec` therefore stores both master rate and frame cadence as reduced numerator/denominator
pairs. Its old integer-rate accessors remain deprecated source adapters and throw for a rational
rate rather than return a plausible false integer. Conversions use checked integer/`BigInteger`
arithmetic and explicit rounding. Consumer-owned `RateAccumulator` phases keep exact remainders;
the per-tick hot path allocates nothing and uses `BigInteger` only when an analytical bulk advance
would overflow `long`.

DMG/CGB/CGB0 retain Coffee GB's established 4,194,304-Hz, 60-Hz controller policy and floored
69,905-tick frame. SGB/SGB2 now use the exact 70,224-tick LCD frame. This intentionally changes the
previous SGB baseline: CPU/timer/PPU/APU/RTC/serial consumers run at the evidenced fast NTSC SGB
rate, and skip boot now exposes `AF=0x0100`, `BC=0x0014`, `DE=0`, `HL=0xc060`. SGB2 exposes the same
values except `AF=0xff00`. The synthetic state hashes record those intentional changes; DMG, CGB,
and CGB0 hashes are unchanged.

Production consumers receive the owning session clock: controllers, rollback history, host pacing,
Agent bounds, Sound and Swing audio conversion, MBC3/Datel-slot RTCs, serial/GPS, four-player
adapter scaling, and clock-derived state semantics. Linked preflight compares the complete reduced
master/cadence identity, not a rounded rate or coincidentally equal frame budget. State preflight
derives Sound capacity and RTC phase bounds from the exact target clock before the first mutation.

## Long-run, audio, RTC, and host pacing policy

Tests advance at least 24 emulated hours algebraically and compare exact 44.1-kHz output totals,
frame totals, and remainders with independent `BigInteger` equations. No real sleep is used. The
core Sound buffer holds one profile frame of tick-domain stereo samples. Swing's host sink owns an
exact sample-rate phase; pause and same-clock restore leave it intact, while an actual profile
clock change resets host presentation history.

MBC3 `subSecondTicks` is an exact numerator-domain phase: one master tick adds the clock
denominator, and the exclusive phase bound is the clock numerator. That scalar and Sound buffer
shape are validated against the target profile during prepare, before any live component mutation.
The injected wall `TimeSource` remains uncaptured.

`TimingTicker` owns only wall pacing. Different fake `nanoTime`/park schedules at the same emulated
tick boundary produce the same complete machine (including tick-domain audio) state. A pause or
late-host re-anchor advances no emulator tick and captures no host deadline.

## Boot policy and capability limits

Skip boot uses the cited SGB-family CPU registers. Pan Docs leaves SGB/SGB2 DIV unknown, so both
retain Coffee GB's deterministic `0xabcc` skip preset; this is explicitly an emulator policy, not a
hardware measurement. No further SGB2 register, command, renderer, multiplayer, serial, or
capability difference is claimed without evidence.

Coffee GB includes an SGB1 bootstrap resource but does not bundle an SGB2 boot ROM. It must never
run the SGB1 image under SGB2 identity. Consequently `NORMAL` and `FAST_FORWARD` bootstrap with
`sgb2` fail before component construction with an actionable “use skip bootstrap” error. `SKIP` is
fully supported. A future user-supplied SGB2 boot path requires separate legal/provenance and image
validation work; this phase downloads or embeds nothing.

## Selection, state, rewind, and netplay

The CLI accepts `--profile=sgb2`; it conflicts with either legacy force flag. Desktop model menus
are registry-generated and include `Auto (default)`, SGB, and SGB2. Auto is represented by an
absent mapping property and preserves existing cartridge/header defaults. Explicit choices persist
canonical IDs and are resolved before the first tick. Legacy `SGB` settings continue to select
SGB1.

`MachineSnapshot` records the exact profile ID and rejects cross-profile restore before mutation.
StateFile v1 bytes and meanings are frozen: its coarse SGB identity always means `sgb`. StateFile
v2 adds a bounded explicit canonical ID to identity section v2 and is required for `sgb2`; see
[state-file-v2.md](state-file-v2.md). The released v1 golden fixture still re-encodes byte-for-byte.

Protocol v8 negotiates StateFile v1 and remains byte-for-byte frozen. It cannot represent SGB2.
Coffee GB therefore rejects a local SGB2 linked load before constructing a linked session and also
rejects any SGB2 state at the transport boundary; it never sends SGB2 as coarse SGB. The reusable
linked clock policy can compare two exact SGB2 clocks, but enabling network sessions needs a
separately negotiated protocol version. Future #315 replay metadata must likewise carry the exact
canonical ID and rational clock identity; no replay format is implemented here.

## Extension contract

To add or rename a profile:

1. never reuse or change an existing canonical ID;
2. add a complete immutable registry row and a finite alias only when migration requires it;
3. update the evidence-backed matrix and source inventory/fingerprints;
4. prove boot/register/memory/render/timing behavior with deterministic baselines;
5. version portable/network metadata when an existing identity cannot represent it;
6. add mismatch-before-mutation, settings/CLI, rewind, linked, and compatibility coverage.

MGB remains deferred to #345. Replay recording remains deferred to #315.
