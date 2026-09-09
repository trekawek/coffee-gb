# SKIP bootstrap register presets

SKIP starts immediately at the cartridge entry point. It does not execute a boot ROM.
`Gameboy.applySkippedBootRegisters` installs the deterministic register defaults after the
existing CPU/DIV presets and cartridge boot handshake. Component constructors retain their
standalone-fixture defaults; NORMAL and FAST_FORWARD still execute the boot ROM.

The defaults are checked against the bundled DMG, CGB (including CGB0), and SGB boot ROMs,
using synthetic valid cartridge headers. The comparison completes the final FF50 write:
FAST_FORWARD can return with PC already at 0100 while that instruction is still in flight.
MGB and SGB2 have no bundled boot ROM, so they use their existing model-specific CPU presets
and the corresponding DMG/SGB peripheral policy; they are not claimed to have boot-run parity.

| Subsystem | SKIP preset / retained policy |
| --- | --- |
| CPU | Existing hardware-profile AF/BC/DE/HL, SP=FFFE, PC=0100, IME off. On CGB running a non-color cartridge, DE=0008, HL=007C, C=0; B is the title-byte sum for Nintendo licensee 01 (including new licensee "01"), otherwise zero. |
| Interrupts | IE=00, IF=E1. |
| Timer | TIMA=TMA=00, TAC reads F8. Existing calibrated DIV presets remain. |
| Serial | SB=00; SC retains the model's initial clock selection/read mask. No transfer is started. |
| Joypad / SGB | No buttons held by the preset. JOYP selectors are 00 on DMG and native CGB, 30 after CGB compatibility setup and on SGB/SGB2. Live user input is still supplied by the configured input source. |
| APU | NR10=00, NR11=80, NR12=F3; other channels' control registers zero. NR50=77, NR51=F3. DMG/MGB/CGB CH1 retains the final chime frequency (07C1) and enabled DAC/channel, but its envelope has already faded to zero (NR52 reads F1). SGB/SGB2 never trigger the chime (NR52 reads F0). |
| Wave RAM | Retains the existing power-on contents; the boot ROM does not initialize it. |
| PPU | LCDC=91; SCY/SCX/LYC/WY/WX=00; BGP=FC; OBP0/OBP1=FF. STAT interrupt enables remain zero. |
| CGB palettes | All BG entries start at 7FFF. Native CGB retains the existing object-palette power-on pattern, BGPI=80 and OBPI=81 (readback adds bit 6). Compatibility mode uses the boot ROM's default BG colors 7FFF/1BEF/6180/0000 and default first two OBJ palettes 7FFF/421F/1CF2/0000, with BGPI=88 and OBPI=90. |
| OAM DMA | Retains the idle controller/register state. No FF46 write is issued. |
| CGB VRAM DMA | Inactive with HDMA5=FF, as after the boot's completed transfer. This is installed directly; writing FF to HDMA5 would start DMA. |
| CGB mode/banking | Existing KEY0 compatibility selection, normal speed, VRAM bank 0, WRAM bank 1, and register read masks. FF6C bit 0 is set in compatibility mode and clear in native mode. |
| Infrared / undocumented I/O | Existing register reset values and mode-dependent read masks are retained. |
| Boot overlay | FF50 is disabled before the first cartridge instruction. |

## Deliberate limits

These are register presets, not a saved full-boot machine image. SKIP preserves its existing
CPU/PPU/DIV/serial/frame-sequencer clock anchors, rather than adopting the elapsed time of an
animation it did not run. DIV, LY and STAT's mode/coincidence bits therefore differ at the
handoff. Chime envelope completion is installed without generating the boot sound or advancing
emulation time; waveform phase and other historical APU latches are not recreated.

The CGB boot ROM also selects special palettes from cartridge title/licensee data and can react
to buttons pressed during boot. SKIP uses the default compatibility palettes. It does not
reconstruct boot-written logo tiles, tile maps, working/high RAM, display history, or the
SGB boot command history. Existing cartridge-specific compatibility handling still applies.
Use NORMAL or FAST_FORWARD when that boot history or a title-specific colorization is needed.

## Coverage

`GameboySkippedBootRegistersTest` compares every FF00–FF7F readback except the three timing
registers noted above, plus the fixed STAT bits, IE and CPU registers, with completed boot runs. It also compares
all 64 bytes of both CGB palette memories for the default header, tests both Nintendo licensee
encodings and a non-Nintendo header, verifies the muted chime/idle DMA state, and round-trips
save state across every supported hardware profile. `GameboyBootStateTest` verifies waveform
output without ROM initialization of NR51 and retains the raw authentic-boot power-state test.
