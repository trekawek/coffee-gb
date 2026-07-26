# StateFile v2 SGB2 golden fixture

`sgb2-session-deflate.cgbstate` is generated entirely from the synthetic, ROM-independent
`StateCodecTestSupport.rom(seed = 2, sgb = true)` input. It contains no Nintendo ROM, boot ROM,
game asset, screenshot, or external capture. The generator is
`StateFileV2GoldenTest.createFixture`; run the explicitly opt-in command documented in
`docs/state-file-v2.md`, review the binary diff and reported SHA-256, then update the pinned test
hash. Normal tests never regenerate it.

Format: exact CGBS StateFile v2 bytes, deterministic raw-DEFLATE payload, SHA-256 pinned in the
test. License: generated Coffee GB test data under the repository license.
