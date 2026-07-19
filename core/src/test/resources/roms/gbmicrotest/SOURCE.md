# GBMicrotest ROMs

`gbmicrotest-v7.zip` contains all 513 GBMicrotest binaries and the suite's
running instructions from the
[game-boy-test-roms v7.0 release](https://github.com/c-sp/game-boy-test-roms/releases/tag/v7.0).
That release pins
[aappleby/GBMicrotest commit f3b5549](https://github.com/aappleby/GBMicrotest/commit/f3b55497c1d1202c784b8201dafc888c838b7302).

Upstream commit `463eb6b` adds the included MIT license without changing any
of the binaries from the pinned commit.

The automated profile pins the exact 31 interactive diagnostics and test benches
without a terminal pass/fail byte. It requires every one of the other 482 ROMs to
retain GBMicrotest's documented FF80-FF82 HRAM verdict protocol, so archive membership
cannot silently change because the same opcode bytes appeared in unrelated data.
