package eu.rekawek.coffeegb.core.ir;

import java.util.ArrayList;
import java.util.List;
import java.io.ByteArrayOutputStream;
import org.junit.Test;
import static org.junit.Assert.*;

public class GbKissLinkTest {
    @Test
    public void transfersMetadataHistoryAndMultipleBlocksOverPulses() {
        roundTrip(file(601, true));
    }

    @Test
    public void transfersFileWithoutHistoryAndExactBlockLength() {
        roundTrip(file(256, false));
    }

    private void roundTrip(byte[] bytes) {
        List<GbfFile> files = new ArrayList<>();
        List<GbKissLink.Progress> progress = new ArrayList<>();
        GbKissLink sender = GbKissLink.send(new GbfFile(bytes), progress::add);
        GbKissLink receiver = GbKissLink.receive(progress::add, files::add);
        for (int i = 0; i < 30_000_000 && (sender.isActive() || receiver.isActive()); i++) {
            sender.setLightOn(receiver.isLightOn());
            receiver.setLightOn(sender.isLightOn());
            sender.tick();
            receiver.tick();
        }
        // Let the receiver observe the last falling edge.
        for (int i = 0; i < 1000 && receiver.isActive(); i++) {
            receiver.setLightOn(sender.isLightOn());
            receiver.tick();
        }
        assertEquals(progress.toString(), GbKissLink.Status.COMPLETE, sender.status());
        assertEquals(progress.toString(), GbKissLink.Status.COMPLETE, receiver.status());
        assertEquals(1, files.size());
        assertArrayEquals(bytes, files.get(0).bytes());
    }

    @Test
    public void rejectsTruncatedAndMismatchedFiles() {
        byte[] bytes = file(1, true);
        bytes[0]--;
        assertThrows(IllegalArgumentException.class, () -> new GbfFile(bytes));
        byte[] truncated = {7, 0, 1, 0, 2, 0, 65};
        assertThrows(IllegalArgumentException.class, () -> new GbfFile(truncated));
    }

    @Test
    public void cancellationTurnsOffLightAndStopsCallbacks() {
        List<GbKissLink.Progress> progress = new ArrayList<>();
        GbKissLink link = GbKissLink.send(new GbfFile(file(1, false)), progress::add);
        for (int i = 0; i < 71_000; i++) link.tick();
        link.disconnect();
        int count = progress.size();
        for (int i = 0; i < 100_000; i++) link.tick();
        assertFalse(link.isLightOn());
        assertEquals(count, progress.size());
        assertEquals(GbKissLink.Status.CANCELLED, link.status());
    }

    @Test
    public void refusesCompletionWhenTransferHistoryNeverArrived() {
        CartridgePeer peer = new CartridgePeer();
        peer.command(0, 0, 0, 0, null, 0);
        peer.command(2, 0xc50c, 2, 0, new byte[]{0, 'A'}, 266);
        peer.command(3, 1, 0, 1, new byte[256], 266);
        peer.command(10, 0xc600, 1, 0, new byte[]{42}, 9);
        peer.command(4, (3 - 53) & 0xffff, 0, 0, null, 9);
        peer.command(10, 0xc50a, 1, 0, new byte[]{7}, 9);
        peer.command(11, 0xce00, 1, 0, new byte[]{2}, 1);
        peer.command(0, 0, 0, 0, null, 0);
        assertEquals(GbKissLink.Status.FAILED, peer.receiver.status());
        assertTrue(peer.received.isEmpty());
        assertFalse(peer.receiver.isLightOn());
    }

    @Test
    public void rejectsCorruptedPacketWithoutDeliveringAFile() {
        CartridgePeer peer = new CartridgePeer();
        peer.command(0, 0, 0, 0, null, 0);
        byte[] corrupt = GbKissLink.join(new byte[]{0x48, 0x75},
                GbKissLink.registers(0x30, 2, 0xc700, 0xc50c, 2, 0),
                GbKissLink.checked(new byte[]{0, 'A'}));
        corrupt[corrupt.length - 1] ^= 1;
        peer.exchange(corrupt, 0, false);
        assertEquals(GbKissLink.Status.FAILED, peer.receiver.status());
        assertTrue(peer.received.isEmpty());
    }

    @Test
    public void restoringMachineStateCancelsAHostTransfer() {
        GbKissLink link = GbKissLink.receive(ignored -> {}, ignored -> fail("Unexpected file"));
        link.onMachineStateRestored();
        assertEquals(GbKissLink.Status.CANCELLED, link.status());
        assertFalse(link.isLightOn());
    }

    @Test
    public void remoteReadUsesTheSourceAddressAndCurrentMenuStatus() {
        CartridgePeer peer = new CartridgePeer();
        peer.command(11, 0xce00, 1, 0, new byte[]{5}, 1);
        byte[] response = peer.exchange(GbKissLink.join(new byte[]{0x48, 0x75},
                GbKissLink.registers(0x30, 8, 0xce00, 0xd812, 2, 0)), 3, false);
        assertArrayEquals(new byte[]{5, 2, -7}, response);
    }

    /** A cartridge-side command driver, including the unhandshaked RAM-write echo. */
    private static final class CartridgePeer {
        final List<GbfFile> received = new ArrayList<>();
        final GbKissLink receiver = GbKissLink.receive(ignored -> {}, received::add);
        final GbKissWire wire = new GbKissWire(message -> fail(message));

        void command(int op, int remote, int length, int parameter, byte[] data, int replyLength) {
            exchange(GbKissLink.join(new byte[]{0x48, 0x75},
                    GbKissLink.registers(0x30, op, 0xc500, remote, length, parameter),
                    data == null ? new byte[0] : GbKissLink.checked(data)), replyLength, op == 11);
        }

        byte[] exchange(byte[] packet, int replyLength, boolean continuation) {
            boolean[] done = {false};
            ByteArrayOutputStream response = new ByteArrayOutputStream();
            wire.send(packet, () -> {
                java.util.function.IntPredicate accept = value -> {
                    response.write(value);
                    return response.size() == replyLength;
                };
                if (replyLength == 0) done[0] = true;
                else if (continuation) wire.receiveContinuation(accept, () -> done[0] = true);
                else wire.receive(accept, () -> done[0] = true);
            });
            int tail = 0;
            for (int i = 0; i < 5_000_000 && receiver.isActive(); i++) {
                wire.input(receiver.isLightOn());
                receiver.setLightOn(wire.output());
                wire.tick();
                receiver.tick();
                if (done[0] && ++tail > 500) return response.toByteArray();
            }
            assertFalse("Cartridge command timed out", receiver.isActive());
            return response.toByteArray();
        }
    }

    static byte[] file(int payloadSize, boolean history) {
        byte[] bytes = new byte[7 + (history ? 46 : 0) + payloadSize];
        bytes[0] = (byte) bytes.length;
        bytes[1] = (byte) (bytes.length >> 8);
        bytes[2] = (byte) (history ? 1 : 0);
        bytes[3] = 7;
        bytes[4] = 2;
        bytes[5] = 0;
        bytes[6] = 'A';
        for (int i = 7; i < bytes.length; i++) bytes[i] = (byte) (i * 31);
        return bytes;
    }
}
