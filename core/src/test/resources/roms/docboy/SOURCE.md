# DocBoy test ROMs

The ROM archive in this directory was built from
[Docheinstein/docboy-test-suite](https://github.com/Docheinstein/docboy-test-suite)
at commit `e417e4e7cf1bf9f978b028a8f72204c7e1bfc5dd` (2026-08-19), using
RGBDS 1.0.3.

`docboy-e417e4e7.zip` has SHA-256
`e121e46ed799e028f72cc8f9401496945c2ad7fea274f72d7f45371594b616bc`.
It contains 162 non-interactive logic ROMs that write the suite's terminal
status to `$FFF0` (`$01` for pass and `$02` for fail):

- 65 DMG tests covering CPU/HALT, memory access, timers, and the STAT-write bug;
- 63 native-CGB tests covering memory and OAM-bug behavior, serial, KEY0, IR,
  undocumented registers, STAT writes, and WRAM banking;
- 34 CGB-in-DMG-mode tests covering memory access, mode selection, banking,
  serial, APU access, STAT writes, and CGB-specific I/O restrictions.

The profile is deliberately strict: every included ROM must report pass, and
there is no known-failure allowlist. The upstream suite also contains thousands
of visual, interactive, boot-sensitive, multi-device, and not-yet-passing logic
tests; those are outside this initial automated selection.

The files are distributed under the MIT license in `LICENSE`.
