# SameSuite ROMs

The ROMs in this directory were built from
[LIJI32/SameSuite](https://github.com/LIJI32/SameSuite) commit
`f15645fb049a47ea235f6d2c9a033e72d8087901` using RGBDS 1.0.1.

Tests whose names target CGB revisions A, 0, B, or 0–C are retained for
reference but excluded from the Maven profile. Coffee GB's generic CGB model
targets the later CGB behavior; the `-cgbDE` variant is included.

Files with the `-shootout` suffix are the historical ROM builds used by
[GBEmulatorShootout](https://github.com/trekawek/GBEmulatorShootout) commit
`ee92ee8d3d3006f4b29ea1067ae8b4eae01a5c07`. They are retained alongside the
current SameSuite files and run through the CGB boot ROM, matching the Shootout
startup path.

The six unsuffixed RGBDS 1.0.1 APU rebuilds corresponding to the APU files below
are retained in the resource tree but excluded from the test parameters. RGBDS
changed `ld [rDIV], a` from the historical 12-T-cycle LDH encoding (`E0 04`) to
the 16-T-cycle absolute LD encoding (`EA 04 FF`), but the source retained the
original NOP counts and expected result tables. The rebuilds are therefore four
T-cycles inconsistent with their own expectations; their `-shootout` builds
provide the valid hardware-timing coverage instead.

SHA-256 checksums:

- `apu/channel_1/channel_1_stop_div-shootout.gb` — `3309d7a3bba3558b3d5c161cc08de2dea30444f236ea105fff1d3d35225d2de5`
- `apu/channel_1/channel_1_sweep-shootout.gb` — `29acde857f938a9d188e73d6a314dca5b470c36e6414bf4897795520097bc006`
- `apu/channel_1/channel_1_sweep_restart-shootout.gb` — `b9d700c15cc94734add97163c4299d69678adbcde823a0b3ba46e6814d3643f4`
- `apu/channel_1/channel_1_volume_div-shootout.gb` — `cc3097b9787ef0eddd27f7a5acd3f3b4f27a9f2a2493c62b21335799443a4bb4`
- `apu/channel_2/channel_2_stop_div-shootout.gb` — `1e9fc4fb8a114b89119c7893ece0192df6871255e39e971b350dd7dc009ad4eb`
- `apu/channel_2/channel_2_volume_div-shootout.gb` — `73c058d2022db9a5fabb8dcc669ed9b16c1685b29adfc31eb7cd72c4e34b2811`
- `dma/gdma_addr_mask-shootout.gb` — `44919ce25c2d049d1abbbcec84c89c3acecb82d7c68b4d5551ef4868a4d9655a`
- `dma/hdma_lcd_off-shootout.gb` — `d49e67a7e176832909790e9bd6356490d2937dd1d907ac319b66cacb420605a2`
- `dma/hdma_mode0-shootout.gb` — `2c5e448d091b2c774ebbb1dac74936abf16f6b4e39a011aff087d9cbc4b28d58`
