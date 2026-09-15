# Turbo File GB and Turbo File Advance

Choose **Peripherals → Link-port device → Turbo File GB** for RPG Tsukuru GB or
Uchuujin Tanaka Tarou de RPG Tsuku-ru GB 2. The **Turbo File** submenu imports or
exports each 1 MiB memory image, inserts/ejects the card and controls write protection.
An ejected card retains its contents; importing a card image replaces them.

The device has 1 MiB of internal flash and a separate 1 MiB removable card. Guest
writes persist at the end of a transfer session and when the device is detached.
The combined 2 MiB backing files, `turbo-file-gb.bin` and
`turbo-file-advance.bin`, live beside the application settings file. They belong
to the device, independently of the game cartridge's battery save. Save states
also preserve flash, switches, banking and partially transmitted packets/bits.
Host imports require an explicit replacement confirmation. A failed durable write
retains dirty memory for retry and reports an error; individual memories can
still be exported from the menu. Card attachment and the write-protect switch
start off when attaching a device.

Both models implement the documented external-clock serial packet protocol,
checksums, status, read/write banks and 64-byte transfers. The Advance model also
supports command 34, the 64-byte block-fill operation. Coffee GB does not emulate
GBA software: Advance support covers the peripheral's shared eight-bit protocol
and its extension, tested with synthetic transactions. Physical compatibility of
a Turbo File Advance with GB software is not established by the hardware research.
The modeled clock is 8192 bits/second on the master clock, including CGB double
speed; this is a compatibility rate rather than a measured hardware clock.

Protocol facts come from Shonumi's
[hardware research](https://shonumi.github.io/articles/art18.html) and
[Turbo File GB](https://github.com/shonumi/gbe-plus/blob/05a05e931b3993ff3e6316b0d841a1fb4d3ac7a7/src/docs/technical/Turbo_File_GB.txt)
and [Advance](https://github.com/shonumi/gbe-plus/blob/05a05e931b3993ff3e6316b0d841a1fb4d3ac7a7/src/docs/technical/Turbo_File_Advance.txt)
technical notes. In particular, the secondary sync waits for F1 followed by 7E;
a stray earlier 7E is acknowledged but must not finish that handshake.
Status retains the researcher implementation's high bit 1; that bit's physical
meaning remains undocumented. A newly attached peripheral receives the current
SB value and clocks an already armed external transfer.
