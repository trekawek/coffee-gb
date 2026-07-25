package eu.rekawek.coffeegb.core.sgb;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (i * 73 + 19) & 0xff;
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
    public void incompleteMultipacketConsumesFollowingPacketThenRecovers() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            List<int[]> incomplete = SgbPacketTestBuilder.command(0x00, 2, 0x11, 0x22);
            fixture.sendPacket(incomplete.get(0));
            fixture.sendCommand(0x01, 1, 0x33, 0x44);

            // Current behavior treats the next complete packet as packet two, regardless of its
            // own header. This is a Phase-0 lock, not an endorsement of the missing validation.
            assertEquals(1, fixture.commands().size());
            assertEquals(0x00, fixture.commands().get(0).getCode());

            fixture.sendCommand(0x02, 1, 0x55, 0x66);
            assertEquals(2, fixture.commands().size());
            assertEquals(0x02, fixture.commands().get(1).getCode());
        }
    }

    @Test
    public void reservedUnknownCommandDoesNotHideFollowingValidCommand() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            // Reserved IDs force one-packet consumption in the current collector even if the
            // low header bits advertise seven packets.
            fixture.sendPacket(SgbPacketTestBuilder.rawPacket((0x1a << 3) | 7, 0xaa));
            fixture.sendCommand(0x03, 1, 0x78, 0x56);

            assertEquals(2, fixture.receivedPackets().size());
            assertEquals(1, fixture.commands().size());
            assertEquals(0x03, fixture.commands().get(0).getCode());
        }
    }

    @Test
    public void zeroPacketCountPoisonsCollectorUntilExplicitStateRestore() {
        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            var idle = fixture.captureCollectorState();
            fixture.sendPacket(SgbPacketTestBuilder.rawPacket(0));
            fixture.sendCommand(0x00, 1, 0x34, 0x12);
            assertTrue(fixture.commands().isEmpty());

            fixture.restoreCollectorState(idle);
            fixture.sendCommand(0x00, 1, 0x34, 0x12);
            assertEquals(1, fixture.commands().size());
        }
    }
}
