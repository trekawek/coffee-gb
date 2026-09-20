# Game Boy sewing machines

Choose **Peripherals → Link port → Sewing machine → Connect** to attach the link
peripheral, then **Open controls…** in the same submenu to open its controls beside
the running game. The machine selector offers the Singer
IZEK-1500, Jaguar JN-100, and Jaguar JN-2000. Only the JN-2000 accepts the EM-2000
embroidery arm; its attachment and hoop size are independent controls.

In Sewing Machine Operation Software or Raku x Raku Mishin, choose a pattern and
use the game's **Data transfer** action. Hold the simulated foot pedal to sew,
or use **Stitch once**. Normal patterns repeat until a path-end command. For
embroidery software such as Mario Family, select JN-2000 with the arm attached
before transferring. Each transferred section runs automatically. Change thread
color and press **SW: next embroidery section** when the software asks to proceed.
The preview remains open while the emulator runs; closing it releases the pedal.

The tool provides pause, simulated motor speed, thread color, a fabric preview,
and PNG export. Clear fabric erases the preview, keeping the transferred pattern.
The preview is a fixed 512×512 sheet; stitches outside its bounds are clipped.
The embroidery preview follows the fabric-facing Y direction, opposite the arm
movement sign. Normal stitching uses the documented 0.25 mm horizontal and 0.0625 mm vertical
increments. Embroidery uses the arm's coordinate grid. The display is a path
simulation, not a physical model of thread tension, fabric deformation or a
measurement of the actual machine's sewing speed.

The endpoint supports the documented external-payload framing, plus the sequence
observed in the USA operation software: a cancelled external transfer, internally
clocked payload with an FF reply, then an external zero-byte status poll. Payload
writes before the first edge update the request. It implements status
bits, packet synchronization/checksums, headers, continuation packets, normal
coordinate pairs, embroidery offsets and shifts, padding and path-end markers.
The modeled external clock is 8192 bits/second in the master clock domain; its
physical rate remains undocumented. SW clicks last a simulated second so
software can debounce the button. Packets and accumulated patterns are bounded.
Final packets may place zero padding after the end marker, before the checksum
(as observed in Mario Family); that padding is excluded from stitch commands.

Save states preserve the partial serial byte, packet assembly, pattern, motor,
controls, thread color and rendered fabric. Preview copies cross the controller's
owner-thread boundary; tool actions carry the game session generation, stop input
recording before mutation, and clear discontinuous rewind/debug history.

Protocol facts are from Shonumi's [hardware research](https://shonumi.github.io/articles/art22.html)
and [Sewing Machine Documentation 0.3](https://github.com/shonumi/gbe-plus/blob/05a05e931b3993ff3e6316b0d841a1fb4d3ac7a7/src/docs/technical/Sewing_Machines.txt).
The implementation is independent Java code based on those documented behaviors.
