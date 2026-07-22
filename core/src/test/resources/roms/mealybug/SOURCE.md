# Mealybug Tearoom test resources

The ROMs and original hardware references in this directory come from Matt Currie's
[Mealybug Tearoom Tests](https://github.com/mattcurrie/mealybug-tearoom-tests), which are
distributed under the MIT License (copyright 2018 Matt Currie).

The following additional DMG-blob references are copied from
[GBEmulatorShootout](https://github.com/trekawek/GBEmulatorShootout/tree/ee92ee8d3d3006f4b29ea1067ae8b4eae01a5c07/testroms/mealybug-tearoom-tests/ppu):

| Local file | Upstream file | SHA-256 |
| --- | --- | --- |
| `m3_lcdc_bg_en_change-dmg-blob.png` | `m3_lcdc_bg_en_change_dmg_blob.png` | `576d75d0b2af8c8e5d86411433a908386ad09cceed62aa59e17e3eac8a687f0a` |
| `m3_lcdc_win_en_change_multiple_wx-dmg-blob.png` | `m3_lcdc_win_en_change_multiple_wx_dmg_blob.png` | `93f32c3f5b0ee75d602b3df78fc1c4f172548f2ed36b22d566aa6f5ac870b1c3` |

Shootout pairs these references with the existing ROMs. Its ROM files are byte-identical
to `m3_lcdc_bg_en_change.gb` and `m3_lcdc_win_en_change_multiple_wx.gb`, so no duplicate
`-shootout` ROM copies are included here.
