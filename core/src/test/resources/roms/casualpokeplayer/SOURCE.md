# CasualPokePlayer test ROMs

Source: [CasualPokePlayer/test-roms](https://github.com/CasualPokePlayer/test-roms).
The repository keeps each test on its own branch. The source revisions are:

- `rtc-invalid-banks-test`: `708634cda2ba7b5a64d9c0ea0ff1d7dca24048d1`
- `latch-rtc-test`: `0f5f4e094c05fb95007c3640ccc7bfcdadd40eb9`
- `ramg-mbc3-test`: `01d06d1fed62edbbef9b70939f794b0b2e5ca5a6`
- `sgb-ext-test`: `326f6f8f148a932e987d934dd9065783f9faacd2`

The built ROMs and cropped LCD reference screenshots are copied from
[GBEmulatorShootout](https://github.com/gbdev/GBEmulatorShootout) at commit
`38b926bdbc26993d1b4c43e97979ecc66287bf02` (2026-07-13).

None currently matches its reference. Exact Coffee GB outputs are stored under
`current-baseline/` and the strict pixel differences are recorded in
`known-failures.tsv`. A reference-perfect result passes automatically; any other
output change fails for review. See `LICENSE` for the source repository's MIT license.
