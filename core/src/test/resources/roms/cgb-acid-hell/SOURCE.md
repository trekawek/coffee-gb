# CGB-ACID-HELL

Source: [mattcurrie/cgb-acid-hell](https://github.com/mattcurrie/cgb-acid-hell),
commit `107b7c5a875f26473ebc1193e32c59394bdd3049` (2021-03-22).

The source repository deliberately does not distribute a built ROM. The ROM in this
directory is the build distributed by
[GBEmulatorShootout](https://github.com/gbdev/GBEmulatorShootout) at commit
`38b926bdbc26993d1b4c43e97979ecc66287bf02` (2026-07-13). The reference PNG is
byte-identical to `img/reference.png` in the original repository.

Coffee GB's current output differs from the reference in 176 pixels. It is pinned as
`current-baseline.png`; a reference-perfect result passes automatically, while any
other output change fails for review. See `LICENSE` for Matt Currie's MIT license.
