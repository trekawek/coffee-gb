# Gambatte emulator HWTests

`gambatte-hwtests.zip` contains 3,524 ROMs built from the
[`pokemon-speedrunning/gambatte-core`](https://github.com/pokemon-speedrunning/gambatte-core/tree/master/test)
hardware tests at commit `5a41a68c25402421fb1983ddadc9faf2418ddb0f`.

The archive also includes the upstream reference PNGs, assembly sources,
`qdgbas.py` assembler, and GPL-2.0 `COPYING` file. Keeping the complete suite in
one compressed test resource avoids adding thousands of loose files to Git.

The issue attachment was used to identify the requested suite. The ROMs in this
archive were rebuilt from the linked source so their expected DMG/CGB results
remain encoded in their canonical upstream filenames and the build is
reproducible.

The automated profile pins the archive at 3,524 ROMs and discovers every
canonical hexadecimal tile verdict: 4,674 strict model cases (1,651 DMG and
3,023 CGB) across 3,077 ROMs. Audio, PNG-only, `xout`, `blank`, and diagnostic
dumper entries do not encode a hexadecimal tile verdict and remain in the
complete archive. Optional deterministic batches partition the sorted manifest;
the default profile still selects every verdict.

`current-baseline.tsv` pins the exact hexadecimal output for the 171 cases that
do not yet match the hardware verdict encoded in the ROM filename. Green cases
continue to assert hardware directly. Any output change fails the profile; when
an emulation fix reaches the hardware result, remove that case from the baseline.

SHA-256: `5492d5a2d79296fedd732fc3dfc8e3de702946cea7cd7e4977f9470b00815900`
