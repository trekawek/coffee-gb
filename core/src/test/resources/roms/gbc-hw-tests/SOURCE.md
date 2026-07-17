# gbc-hw-tests provenance

- Upstream: https://github.com/alyosha-tas/gbc-hw-tests
- Commit: `631e60000c885154a8526df0b148847f9c34ce42`
- Archive: `gbc-hw-tests-631e600.zip`
- SHA-256: `f52b3acc9f4bc61867390ee1e61ba8bb4f28a525423ce859e401c91d50690aff`
- License: MIT (included as `LICENSE` inside the archive)

The archive contains the complete upstream tree except Git metadata. The automated
profile runs every ROM that has an SRAM capture containing the suite's
`12 34 56 78` completion marker, comparing DMG runs with `real_gb.sav` and CGB
runs with `real_gbc.sav`. ROMs without a machine-readable hardware capture remain
in the archive for manual investigation but are not treated as automated verdicts.
