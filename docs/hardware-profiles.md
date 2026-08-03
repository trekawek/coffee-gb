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
| `mgb` | Game Boy Pocket (MGB) | DMG / `mgb` | the evidenced DMG-side monochrome and serial capabilities |

IDs are non-ordinal and permanent. Canonical lookup accepts only these exact strings. Persisted
settings additionally accept the finite historical aliases `DMG`, `CGB`, `CGB0`, and `SGB`;
notably, `SGB` always canonicalizes to `sgb`, never `sgb2`, and no uppercase `SGB2` or `MGB` alias exists.
Display names, enum ordinals, mixed case, and arbitrary aliases are never identities. Unknown IDs
fail before core construction and list every supported ID.

`HardwareCapabilities`, `BootSpec`, and `ClockSpec` are immutable profile-owned values. Mutable
arrays, callbacks, host clocks, event buses, and UI/device values are absent. The deprecated
three-value `GameboyType` remains a source adapter: both `sgb` and `sgb2` map back to coarse
`GameboyType.SGB`, while `mgb` maps back to `GameboyType.DMG`. Those coarse values map forward only
to `sgb` and `dmg`, respectively. Configuration copies for
restore, rollback, boot templates, linked sessions, and transaction rollback retain the exact
registered profile instance.

`HardwareProfileRegistry` is the executable source of truth. Registry, construction, synthetic
model, StateFile, and netplay tests cover identity, exact clocks/cadence, boot selectors, skip
defaults, and ordering through behavior rather than mirroring those values in a second matrix.

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

## MGB evidence and shared policy

The MGB evidence was accessed 2026-07-26. No MGB boot ROM or proprietary capture is committed.

- **Gekkio, _GB: Complete Technical Reference_, revision 188 (2026-07-14)**, CC BY-SA 4.0,
  [`gbctr.pdf`](https://gekkio.fi/files/gb-docs/gbctr.pdf), downloaded SHA-256
  `b147d6c49fb27ea8e803da9956e91ac1e47fd7bdedd7742ca1ffab63c7daaf07`, states that DMG and MGB
  use a nominal `4.194304 MHz = 2^22 Hz` external quartz oscillator. MGB therefore reuses exact
  immutable `ClockSpec.LEGACY`; Coffee GB's 60-Hz/69,905-tick controller cadence remains legacy
  scheduling policy rather than a physical refresh-rate claim.
- **Pan Docs commit `37526fad2d47c89fa6485fce0740c594686598b1`**, CC0-1.0,
  [`Power_Up_Sequence.md`](https://github.com/gbdev/pandocs/blob/37526fad2d47c89fa6485fce0740c594686598b1/src/Power_Up_Sequence.md),
  identifies the MGB boot ROM as a distinct 256-byte image whose only DMG boot-ROM difference is
  writing `A=0xff` rather than `A=0x01` at handoff. Its post-boot table gives the shared
  `BC=0x0013`, `DE=0x00d8`, `HL=0x014d`, `SP=0xfffe`, `PC=0x0100`, and DMG/MGB `DIV=0xab`.
  `F=0xb0` is the established non-zero-header-checksum case used by Coffee GB's deterministic skip
  policy; Pan Docs warns that half-carry/carry depend on the cartridge checksum.
- GBCTR revision 188 separately identifies the MGB boot ROM by hashes and records its distinct
  origin, but the copyrighted bytes are neither bundled nor downloaded here.

The available evidence supports no generic MGB PPU, APU, timer, mapper, or serial quirk. MGB
therefore shares immutable DMG capabilities, clock, and all skip-boot fields except `A`; that
sharing is deliberate composition, not a claim that unknown silicon differences do not exist.

`ClockSpec` therefore stores both master rate and frame cadence as reduced numerator/denominator
pairs. Its old integer-rate accessors remain deprecated source adapters and throw for a rational
rate rather than return a plausible false integer. Conversions use checked integer/`BigInteger`
arithmetic and explicit rounding. Consumer-owned `RateAccumulator` phases keep exact remainders;
the per-tick hot path allocates nothing and uses `BigInteger` only when an analytical bulk advance
would overflow `long`.

DMG/MGB/CGB/CGB0 retain Coffee GB's established 4,194,304-Hz, 60-Hz controller policy and floored
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
clock change resets host presentation history. DMG and MGB share the same exact clock instance;
24-hour frame/audio calculations prove identical totals and remainders.

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

Coffee GB includes DMG/SGB1/CGB bootstrap resources but does not bundle MGB or SGB2 boot ROMs. It
must never run the DMG image under MGB identity or SGB1 under SGB2 identity. Consequently `NORMAL`
and `FAST_FORWARD` with `mgb` or `sgb2` fail before component construction with an actionable “use
skip bootstrap” error. `SKIP` is fully supported. A future user-supplied model-specific boot path
requires separate legal/provenance and image validation; Coffee GB downloads or embeds no such
image.

## Selection, state, rewind, and netplay

The CLI accepts `--profile=sgb2` and `--profile=mgb`; either conflicts with legacy force flags.
Desktop model menus are registry-generated and include `Auto (default)` plus MGB. Auto is an
absent mapping property and preserves existing cartridge/header defaults: a ROM header cannot
identify that a user wants Pocket hardware. Explicit choices persist canonical IDs and resolve
before the first tick. Legacy `DMG` selects DMG, never MGB; legacy `SGB` selects SGB1.

`MachineSnapshot` records the exact profile ID and rejects cross-profile restore before mutation.
StateFile v1 bytes and meanings are frozen: coarse DMG always means `dmg`, never MGB; coarse SGB always means `sgb`; and its
MBC3 phase remains a fraction of the historical 4,194,304-unit second. StateFile v2 adds a bounded
explicit canonical ID to identity section v2 and is required for new `sgb`, `sgb2`, and `mgb`
captures. Applying old v1 SGB state performs a checked detached rational conversion before the
first mutation; decoding and re-encoding preserves the exact old bytes. See
[state-file-v2.md](state-file-v2.md). The released v1 golden fixture still re-encodes byte-for-byte.

Protocol v8 negotiates StateFile v1 and remains byte-for-byte frozen. It cannot distinguish MGB
from coarse DMG, the historical SGB RTC scalar from current exact-clock state, or SGB2 from SGB.
Coffee GB rejects MGB and both SGB-family linked loads before constructing a session or writing
state. A received coarse DMG remains canonical DMG. The `CGBR` v1 replay identity separately
carries the exact canonical ID and both reduced clock rationals; see
[replay-format-v1.md](replay-format-v1.md).
Headless `run`/`replay` profile and bootstrap selection is documented in
[headless-cli.md](headless-cli.md); replay defaults to the recording's exact canonical identity.

## Extension contract

To add or rename a profile:

1. never reuse or change an existing canonical ID;
2. add a complete immutable registry row and a finite alias only when migration requires it;
3. update the registry, behavioral construction/state tests, and evidence documentation;
4. prove boot/register/memory/render/timing behavior with deterministic baselines;
5. version portable/network metadata when an existing identity cannot represent it;
6. add mismatch-before-mutation, settings/CLI, rewind, linked, and compatibility coverage.

The enforceable extension checklist is [hardware-profile-contribution.md](hardware-profile-contribution.md).
Available AGB-in-GB-mode evidence does not justify a production profile, so no AGB profile is
implemented. Replay recording preserves the exact profile and clock identity as documented in
[replay-format-v1.md](replay-format-v1.md).
