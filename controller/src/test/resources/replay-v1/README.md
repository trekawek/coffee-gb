# Replay v1 golden fixture

`synthetic-input.cgbreplay` is the exact canonical `CGBR` v1 output of
`ReplayGoldenFixture.create` in `ReplayGoldenFixtureTest`. The generator creates all data in the
test process; it does not read a ROM, save, StateFile, replay, or third-party fixture from disk.
The committed replay contains no ROM bytes and no embedded StateFile.

The source cartridge is a repository-owned 32 KiB zero-filled synthetic ROM. Its only nonzero
content is the title `CGBR-TEST` at `0x0134`, the `NOP; JR -3` loop at `0x0100`, and its computed
header checksum; CGB/SGB, mapper, ROM-size, and RAM-size header fields are zero. Its SHA-256 is
`00b65757ccf478ed00e3b435bef784198eb259e6f912266c195567fc7ed7eab7`. The test recreates those
bytes for playback rather than committing the ROM.

The generator uses the DMG profile, skipped bootstrap, disabled battery persistence, no slot ROM,
and RTC epoch `946684800000`. It records legacy P1 A press/release plus physical P1 LEFT,
LEFT+START, and release transitions and physical P2 B and release transitions. Physical hub
updates are held through the Joypad's real 64-master-tick poll boundaries at ticks 1, 65, and 129,
so regeneration preserves all seven input records instead of collapsing intermediate updates.
It completes the first 69,905-tick controller frame, executes seven tail ticks, and records
one-frame periodic plus final checkpoints. Metadata is fixed to producer
`coffee-gb-test/replay-v1`, creation time `1700000123456`, and note
`repository-owned synthetic input timeline`.

Format: explicit big-endian `CGBR` v1 sections encoded only by `ReplayCodec`; no Java native
serialization. License: generated Coffee GB test data under the repository license.

- Size: `945` bytes
- SHA-256: `316b08b9942674c1a46ac053c77dbad7ff53798b85f0e6092e0d4d3d6288325c`

Exact inspector summary:

```text
magic=CGBR format=1 checksum=true
required-features=0x0 optional-features=0x0 payload=873 decoded-sections=846 profile="dmg"
initial=BOOT_REFERENCE tick=0 frame=0 rtc=946684800000
inputs=7 checkpoints=2 final-tick=69911 final-frame=1 embedded-state=false
producer="coffee-gb-test/replay-v1" created=1700000123456 note="repository-owned synthetic input timeline"
section=1 version=1 required=true compression=NONE encoded=92 decoded=92
section=2 version=1 required=true compression=NONE encoded=28 decoded=28
section=3 version=1 required=true compression=DEFLATE encoded=46 decoded=90
section=4 version=1 required=true compression=DEFLATE encoded=501 decoded=550
section=5 version=1 required=false compression=NONE encoded=86 decoded=86
```

Normal tests only inspect, decode, byte-for-byte re-encode, and play the committed file. Regenerate
it only for an intentional reviewed format change by running from the repository root:

```sh
mvn -B -pl controller -am \
  -Dtest=ReplayGoldenFixtureTest \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -DcoffeeGb.updateReplayGolden=true test
```

The updater writes the source fixture and then fails deliberately while printing its size,
fixture and synthetic-ROM SHA-256 values, and inspector summary. Review and pin those values in
this README and `ReplayGoldenFixtureTest`, then run the normal focused test:

```sh
mvn -B -pl controller -am \
  -Dtest=ReplayGoldenFixtureTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```
