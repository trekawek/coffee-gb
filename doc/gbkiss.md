# GBKiss

GBKiss uses the infrared transceiver in Hudson HuC-1 and HuC-3 cartridges. This
hardware works on the original Game Boy as well as Game Boy Color, independently
of the CGB's built-in infrared port.

## Transfer GBF files

Open a supported game and enter its GBKiss file menu.

- To install a file, choose **Peripherals → Infrared → GBKiss Link → Send GBF file**, open a
  `.gbf` file, and select **Receive** in the game. In GBKiss Mini Games, Start is
  the Receive shortcut. Choose a free slot; sending a duplicate title does not
  overwrite the existing file.
- To export a file, choose **Peripherals → Infrared → GBKiss Link → Receive GBF file**, then
  select the file and **Send** in the game. Confirm the game's connection screen.
  A save dialog opens when the complete file arrives. Choose a new filename.
- **Cancel transfer** disconnects the modem. If you cancel the save dialog, the
  received file stays in memory; choose **Receive GBF file** again to save it.

Transfers require the game to keep running. Status appears in the submenu and
desktop status area. The modem waits for a partner for up to one minute of
emulated time. The game controls available storage and cartridge restrictions.
GBF metadata, icons, optional transfer history, and payload are preserved by the
modem; the cartridge may update its own transfer history.

The host modem is available in standalone play. Close netplay and stop input
recording/playback before using it. Rewind history is cleared while transferring;
restoring a machine state or closing the game cancels the transfer.

## Netplay

Start an ordinary two-player netplay session and use the game's infrared
communication menus. HuC-1 and HuC-3 cartridge IR automatically connects to the
other player's cartridge; the GBKiss Link file submenu is not needed.

Netplay already runs both machines from synchronized inputs. Cartridge LED
changes use its existing paired infrared endpoints, just as CGB infrared does.
Each mapper saves its LED output, so rollback restores both ends of the link.
When a cartridge has IR hardware, the netplay infrared endpoint attaches there;
the CGB RP register cannot overwrite the cartridge LED.

## Implementation and verification

`GbKissLink` is a host modem clocked through the cartridge in master ticks. It
exchanges IR pulses and GBKiss commands; it does not modify cartridge RAM through
a debugger. Byte framing uses AA/55 and C3/3C handshakes, synchronized data bytes,
and additive checksums. RAM-write acknowledgements continue the same byte stream.
File data travels in blocks of up to 256 bytes; a zero block-length byte means
256. Files without history omit the history seek/write commands.

GBF input is bounded to 65535 bytes and validated before attachment. Receiving
checks packet checksums, write bounds, and coverage of every file byte before
making a file available to the desktop. File I/O runs outside the emulation
owner. Host transfer state is not serialized; older mapper states default the
new LED output field to off.

Tests cover mapper IR on DMG/CGB, netplay checkpoint restoration, historical
state files, native cartridge handshake framing, multi-block file round trips,
missing history, corrupted packets, cancellation, and Swing request correlation.
Manual verification with GBKiss Mini Games exercised sending and receiving the
public Hello World and LCD stopwatch GBFs, including execution of Hello World.
No commercial ROM, battery save, or gameplay capture is included in the tests.

## References

- [GBKiss history and hardware overview](https://shonumi.github.io/articles/art38.html)
- [GBKiss archive, cartridges, software, and technical documentation](https://gbkiss.org/)
- [GBF metadata](https://gbkiss.org/tech/metadata)
- [GBKiss infrared command interface](https://gbkiss.org/tech/infrared)
- [Dan Docs: GB KISS LINK pulse protocol](https://shonumi.github.io/dandocs.html)
- [GBKiss system assembly, including the IR and file-menu routines](https://github.com/sfiera/gbkasm)
