# Daid test ROMs

The ROMs and reference screenshots in this directory are vendored from the
[GB Emulator Shootout Daid suite](https://github.com/gbdev/GBEmulatorShootout/tree/7c6ee9ba380fab277f30784c5de7d35b21a4b679/testroms/daid),
at commit `7c6ee9ba380fab277f30784c5de7d35b21a4b679`.

`rom_and_ram.gb` is informational upstream and has no reference image. `DaidRomAndRamTest`
asserts the suite's recommended always-enabled, wrapping 2 KiB RAM behavior directly; the
eight screenshot-backed cases are exercised by `DaidRomTest`.

The files are distributed under the MIT license in `LICENSE`.
