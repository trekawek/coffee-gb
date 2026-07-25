package eu.rekawek.coffeegb.core.sgb;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class SgbPacketHarnessTest {

    @Test
    public void buildsOneAndSevenPacketBoundaryCommands() {
        assertEquals(1, SgbPacketTestBuilder.command(0x00, 1, 1, 2, 3).size());

        int[] payload = new int[SgbPacketTestBuilder.MAX_PAYLOAD_BYTES];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = i & 0xff;
        }
        List<int[]> packets = SgbPacketTestBuilder.command(0x05, 7, payload);

        assertEquals(7, packets.size());
        assertEquals((0x05 << 3) | 7, packets.get(0)[0]);
        int[] reconstructed = new int[payload.length];
        for (int i = 0; i < reconstructed.length; i++) {
            int flatIndex = i + 1;
            reconstructed[i] = packets.get(flatIndex / 16)[flatIndex % 16];
        }
        assertArrayEquals(payload, reconstructed);
    }

    @Test
    public void fixtureRejectsImpossibleCountsLengthsAndByteValues() {
        assertThrows(IllegalArgumentException.class,
                () -> SgbPacketTestBuilder.command(0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> SgbPacketTestBuilder.command(0, 8));
        assertThrows(IllegalArgumentException.class,
                () -> SgbPacketTestBuilder.command(0, 1, new int[16]));
        assertThrows(IllegalArgumentException.class,
                () -> SgbPacketTestBuilder.command(0, 1, 0x100));
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            assertThrows(IllegalArgumentException.class,
                    () -> fixture.sendPacket(new int[15]));
        }
    }

    @Test
    public void realJoypPathAssemblesSevenPacketsDeterministically() {
        int[] payload = new int[SgbPacketTestBuilder.MAX_PAYLOAD_BYTES];
        payload[0] = 110;
        for (int i = 0; i < 110; i++) {
            boolean horizontal = (i & 1) != 0;
            int line = i % (horizontal ? 18 : 20);
            payload[i + 1] = (horizontal ? 0x80 : 0) | (i % 4) << 5 | line;
        }
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            fixture.sendCommand(0x05, 7, payload);

            assertEquals(7, fixture.receivedPackets().size());
            assertEquals(1, fixture.commands().size());
            Commands.AbstractCommand command = fixture.commands().get(0);
            assertEquals(0x05, command.getCode());
            assertEquals(7, command.getLength());
            int[] expected = new int[112];
            expected[0] = (0x05 << 3) | 7;
            System.arraycopy(payload, 0, expected, 1, payload.length);
            assertArrayEquals(expected, command.packet);
        }
    }

    @Test
    public void incompleteJoypTransferCanRestartBeforeFollowingCommand() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            int[] abandoned = SgbPacketTestBuilder.rawPacket((0x07 << 3) | 1, 9, 8, 7);
            fixture.sendIncomplete(abandoned, 127);
            fixture.sendCommand(0x00, 1, 0x34, 0x12);

            assertEquals(1, fixture.commands().size());
            Commands.Pal01Cmd command = (Commands.Pal01Cmd) fixture.commands().get(0);
            assertEquals(0x1234, command.getPalette0()[0]);
        }
    }

    @Test
    public void explicitReceiverRestartAbortsIncompleteMultipacketBeforeFollowingCommand() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            List<int[]> incomplete = SgbPacketTestBuilder.command(0x00, 2, 0x11, 0x22);
            fixture.sendPacket(incomplete.get(0));
            fixture.restartReceiver();
            fixture.sendCommand(0x01, 1, 0x33, 0x44);

            assertEquals(1, fixture.commands().size());
            assertEquals(0x01, fixture.commands().get(0).getCode());
        }
    }

    @Test
    public void reservedUnknownCountBitsDoNotHideFollowingValidCommand() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            // The established SGB compatibility rule consumes reserved IDs one physical row at a
            // time, even when their otherwise-untrusted header advertises more rows.
            fixture.sendPacket(SgbPacketTestBuilder.command(0x1a, 7, 0xaa).get(0));
            fixture.sendCommand(0x03, 1, 0x78, 0x56);

            assertEquals(2, fixture.receivedPackets().size());
            assertEquals(1, fixture.commands().size());
            assertEquals(0x03, fixture.commands().get(0).getCode());
        }
    }

    @Test
    public void zeroPacketCountIsRejectedWithoutPoisoningFollowingCommand() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            fixture.sendPacket(SgbPacketTestBuilder.rawPacket(0));
            fixture.sendCommand(0x00, 1, 0x34, 0x12);
            assertEquals(1, fixture.commands().size());
            assertEquals(0x1234, ((Commands.Pal01Cmd) fixture.commands().get(0)).getPalette0()[0]);
        }
    }

    @Test
    public void completeMalformedPacketCountIsRejectedBeforeFollowingCommand() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            fixture.sendCommand(0x00, 2, new int[31]);
            fixture.sendCommand(0x02, 1, 0x55, 0x66);

            assertEquals(1, fixture.commands().size());
            assertEquals(0x02, fixture.commands().get(0).getCode());
        }
    }

    @Test
    public void unsupportedFamiliesAreConsumedWithoutEventsAndFollowingCommandSurvives() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            fixture.sendCommand(0x08, 1, 0x80, 0x80, 0, 0);
            fixture.sendCommand(0x09, 1);
            fixture.sendCommand(0x0c, 1, 0);
            fixture.sendCommand(0x0d, 1, 0);
            fixture.sendCommand(0x0e, 1, 0);
            fixture.sendCommand(0x0f, 1, 0, 0x18, 0, 1, 0xaa);
            fixture.sendCommand(0x10, 1, 0, 0x18, 0);
            fixture.sendCommand(0x12, 1, 0, 0x18, 0, 0, 0, 0);
            fixture.sendCommand(0x18, 1, 0xff);
            assertEquals(0, fixture.commands().size());

            fixture.sendCommand(0x03, 1, 0x78, 0x56);
            assertEquals(1, fixture.commands().size());
            assertEquals(0x03, fixture.commands().get(0).getCode());
        }
    }
}
