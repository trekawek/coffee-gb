# gbc-hw-tests provenance

- Upstream: https://github.com/alyosha-tas/gbc-hw-tests
- Commit: `631e60000c885154a8526df0b148847f9c34ce42`
- Archive: `gbc-hw-tests-631e600.zip`

## Capture selections and metadata corrections

The strict test runner preserves all 221 ROM/model verdicts, but applies seven
documented raw-reference selections or metadata corrections:

- `tac_set_enabled [CGB]`: its `real_gbc.sav` contains impossible values after an
  `and 4` write and large unwritten gaps. The byte-identical independent GB and GBP
  captures are used as a derived cross-model consensus for the old-enabled TAC
  transitions. The pinned archive's `timers/tac_set_everything/GBC_1.txt` explicitly
  specifies that case as "Same as DMG"; discovery validates both the documentation
  and the two consensus captures.
- `tma_set [DMG]`: `real_gb.sav` contains the CGB-only second-speed pass; the physical
  GBP single-speed capture is used. The parameter display name identifies it as the raw
  GBP/monochrome-family capture.
- `timer_reset_2`: the ROM does not clear SRAM, so later `$12,$34,$56,$78` sequences
  are stale. The completion offsets encoded by the ROM are 320 bytes (DMG) and 640
  bytes (CGB).
- `corrupted_stop [DMG]` is a partial manual checkpoint, not a completed 256-case
  capture. Only `00,12,12,12,12` was written after the first STOP; the following
  `$34,$56,$78` bytes are stale SRAM that accidentally resemble a completion marker.
  The runner compares only the five captured bytes, presses and releases the wake
  button, and requires the pinned ROM to enter its second STOP at PC `$01bd`. It never
  seeds the stale tail.
- `dma_timing_lcd_on [DMG]` deliberately exercises OAM row-boundary read corruption.
  SameBoy's hardware-derived implementation documents these cases as revision- and
  console-instance-specific, including nondeterministic results and instance-dependent
  row or odd/even-byte copies ([pinned `memory.c`](https://github.com/LIJI32/SameBoy/blob/213a12ce93d66b105a113debd9396306066a7cfc/Core/memory.c#L196-L247)).
  The pinned `real_gb.sav` and `real_gbp.sav` have the same 2,564-byte completion length
  and differ in exactly 31 result bytes. The deterministic SameBoy-derived model matches
  the complete raw GBP capture, so that physical monochrome-family capture is selected
  canonically and named explicitly in the parameter display. Discovery validates the
  completion lengths and difference count; the runner compares all 2,564 selected bytes
  exactly, with no mask, tolerance, skip, or accepted alternate output.
- `hdma_halt [CGB]` is a partial manual checkpoint. Its third pass records HDMA5 and
  LY through byte 8, sets B to `$99`, and then polls LY at `$0375..$0378`. The physical
  CGB misses LY's short double-speed line-153 latch and remains in that loop. Byte 9 is
  stale SRAM: it is `$78` in the CGB capture but `$ff` in the GBA-SP capture, even though
  both have the same executed third-pass prefix through byte 8 and the same apparent
  marker at bytes 10..13. The runner therefore compares all nine bytes actually written
  and requires PC/B/C/HL plus the SRAM prefix and tail to remain at the pinned loop for
  a full frame. Discovery validates both physical prefixes, the differing byte 9, the
  shared stale marker, and the exact ROM instructions at the call site and loop.

Each selected file/offset is validated at test discovery time so an upstream archive
change fails loudly instead of silently changing the comparison.
- SHA-256: `f52b3acc9f4bc61867390ee1e61ba8bb4f28a525423ce859e401c91d50690aff`
- License: MIT (included as `LICENSE` inside the archive)

The archive contains the complete upstream tree except Git metadata. The automated
profile runs every ROM that has an SRAM capture containing the suite's
`12 34 56 78` completion marker, comparing DMG runs with `real_gb.sav` and CGB
runs with `real_gbc.sav` except for the documented raw selections above. ROMs without
a machine-readable hardware capture remain in the archive for manual investigation
but are not treated as automated verdicts.
