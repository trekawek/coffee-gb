# Barcode Taisen Bardigun reader

TAM's reader used by Barcode Taisen Bardigun has its own serial protocol. Namco's
Barcode Boy, used by Battle Space and other games, remains a separate peripheral.

Open Barcode Taisen Bardigun, then choose **Peripherals → Link port → Bardigun
Reader → Connect**. Choose **Scan barcode…** in the same submenu and enter the
13 digits printed under an EAN-13 barcode. When the game is waiting for a scan,
choose **Scan**. The game decides whether the number is valid and what monster
or attack it represents; Coffee GB does not replace or calculate its check digit.

The scanner uses the Game Boy's internal serial clock. It reports `00` while its
button is released and streams sampled white/black bars during a swipe. Coffee
GB expands each EAN-13 module to 15 samples, within the published hardware
measurements, with white quiet zones before and after the barcode. It does not
send Barcode Boy's handshake or ASCII digits. A queued swipe begins at the next
serial-byte boundary. Pending input and partial scans are included in save
states and rewind; disconnecting releases the button and cancels the scan.

This is a deterministic optical sample model for entered EAN-13 numbers. It does
not simulate hand motion, print damage, or an attached physical scanner.

Regression tests cover EAN guards/parity/digits and sample widths, idle and
external-clock behavior, malformed input, queued/partial scan restoration,
portable state files, and the independent Swing selection. Manual verification in
Barcode Taisen Bardigun confirmed that `4902370501445` hatches Takora, matching
the published hardware result. The previous Barcode Boy endpoint gave the game's
retry message on the same scan screen.

Protocol evidence: [Shonumi's hardware investigation](https://shonumi.github.io/articles/art6.html)
and [technical notes version 0.3](https://github.com/shonumi/gbe-plus/blob/05a05e931b3993ff3e6316b0d841a1fb4d3ac7a7/src/docs/technical/Barcode_Taisen_Bardigun.txt),
consulted September 15, 2026. The implementation is written for Coffee GB from
the documented protocol and EAN-13 format; no emulator source, game ROM, save,
or card image is included.
