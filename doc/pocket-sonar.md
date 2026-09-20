# Pocket Sonar (MBC1S)

Pocket Sonar is detected from its `POCKETSONAR` title and MBC1/no-RAM header.
Its sensor replaces cartridge RAM: enable it at 6000–7FFF, write 1 then 0 at
4000–5FFF to pulse, and read successive three-bit samples at A000. ROM banking
retains the five-bit MBC1 register. Other external-memory addresses are open bus.

Use DMG hardware (or Auto). The original CGB cannot read the sensor; its enabled
input returns zero, including when running DMG software. Turning sensor power
off returns FF. This is modeled independently of the ROM's color-support flag.

The default input is a repeating **simulated** seabed with isolated fish echoes.
**Peripherals → Cartridge → Pocket Sonar** provides open water, the default scene,
power controls, and PNG input. PNGs must be 160 × 96 or 160 × 192. White is water, black is an
echo, dark gray (#555555) begins the floor, and light gray (#AAAAAA) is sediment
below it (a weaker echo above it). Short images hold their final row for the
additional samples used by magnification. Columns repeat after 160 pulses.
This is a deterministic sample source, not a physical acoustic simulation.

Save states include the scene, power, pulse, ROM bank and partial column. Changing
host input ends an input recording and clears rewind/reverse-debug history;
playback rejects host sensor changes.

Validation: the original Japanese cartridge on DMG previously crashed with a
zero-divisor in MBC1 RAM addressing on entering sonar mode. With this board it
enters the live scrolling sonar view and draws the simulated bed and fish echoes.
Unit tests cover detection, bank selection, sampled reads, power, CGB behavior,
portable state round trips and PNG interpretation.

Hardware register and sample behavior are based on Shonumi's
[End of the Game article](https://shonumi.github.io/articles/art13.html) and
[Pocket Sonar technical notes](https://github.com/shonumi/gbe-plus/blob/05a05e931b3993ff3e6316b0d841a1fb4d3ac7a7/src/docs/technical/Pocket_Sonar.txt).
