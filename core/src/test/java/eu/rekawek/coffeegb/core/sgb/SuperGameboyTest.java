package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class SuperGameboyTest {

    @Test
    public void transferCommandCapturesThirdFrameAfterPacket() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.PctTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.PctTrnCmd.class);

        int[] packet = new int[16];
        packet[0] = (0x14 << 3) | 1; // one-packet PCT_TRN
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet));

        postFrame(sgbBus, 1);
        assertNull(transfer.get());
        postFrame(sgbBus, 2);
        assertNull(transfer.get());
        postFrame(sgbBus, 3);

        assertEquals(3, transfer.get().dataTransfer[0]);
    }

    @Test
    public void characterTransferAlsoCapturesThirdFrameAfterPacket() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.ChrTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.ChrTrnCmd.class);

        int[] packet = new int[16];
        packet[0] = (0x13 << 3) | 1; // one-packet CHR_TRN
        packet[1] = 1; // upper tile half
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet));

        postFrame(sgbBus, 1);
        assertNull(transfer.get());
        postFrame(sgbBus, 2);
        assertNull(transfer.get());
        postFrame(sgbBus, 3);

        assertEquals(0x80, transfer.get().getTileOffset());
        assertEquals(3, transfer.get().dataTransfer[0]);
    }

    @Test
    public void restoreClearsTransferThatIsAbsentFromMemento() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        SuperGameboy superGameboy = new SuperGameboy(sgbBus);
        AtomicReference<Commands.PctTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.PctTrnCmd.class);
        var idleMemento = superGameboy.captureState();

        int[] packet = new int[16];
        packet[0] = (0x14 << 3) | 1;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet));
        superGameboy.restoreState(idleMemento);

        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        postFrame(sgbBus, 3);
        assertNull(transfer.get());
    }

    @Test
    public void mementoOwnsPartialMultipacketData() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        SuperGameboy superGameboy = new SuperGameboy(sgbBus);
        AtomicReference<Commands.AttrLinCmd> command = new AtomicReference<>();
        sgbBus.register(command::set, Commands.AttrLinCmd.class);

        int[] firstPacket = new int[16];
        firstPacket[0] = (0x05 << 3) | 2; // two-packet ATTR_LIN
        firstPacket[1] = 15;
        for (int i = 0; i < 14; i++) {
            firstPacket[i + 2] = 0x20 | i;
        }
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(firstPacket));
        var partialMemento = superGameboy.captureState();

        // Finish that command, then overwrite row zero of the live multipacket buffer.
        int[] continuation = new int[16];
        continuation[0] = 0x20 | 14;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(continuation));
        int[] overwrite = new int[16];
        overwrite[0] = (0x00 << 3) | 1;
        overwrite[1] = 0x78;
        overwrite[2] = 0x56;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(overwrite));

        superGameboy.restoreState(partialMemento);
        command.set(null);
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(continuation));

        assertEquals(1, command.get().getDataSet(1).getPaletteNumber());
        assertEquals(0, command.get().getDataSet(1).getLineNumber());
        assertEquals(14, command.get().getDataSet(15).getLineNumber());
    }

    @Test
    public void transferCommandMementoOwnsPacketAndFrameData() {
        int[] packet = new int[16];
        packet[0] = (0x13 << 3) | 1;
        packet[1] = 1;
        int[] frame = new int[0x1000];
        frame[0] = 7;
        Commands.ChrTrnCmd command = (Commands.ChrTrnCmd) Commands.toCommand(packet);
        command.setDataTransfer(frame);
        var memento = command.captureState();

        packet[1] = 0;
        frame[0] = 9;
        Commands.ChrTrnCmd restored =
                (Commands.ChrTrnCmd) Commands.TransferCommand.restoreState(memento);

        assertEquals(0x80, restored.getTileOffset());
        assertEquals(7, restored.dataTransfer[0]);
    }

    @Test
    public void laterValidTransferAtomicallyReplacesPendingCapture() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        List<Commands.ChrTrnCmd> transfers = new ArrayList<>();
        sgbBus.register(transfers::add, Commands.ChrTrnCmd.class);

        int[] first = packet(0x13);
        first[1] = 0;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(first));
        int[] replacement = packet(0x13);
        replacement[1] = 1;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(replacement));

        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        postFrame(sgbBus, 3);
        assertEquals(1, transfers.size());
        assertEquals(0x80, transfers.get(0).getTileOffset());
        assertEquals(3, transfers.get(0).dataTransfer[0]);
    }

    @Test
    public void malformedAndUnsupportedCommandsDoNotReplaceAcceptedPendingTransfer() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.PctTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.PctTrnCmd.class);

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x14)));
        int[] malformed = packet(0x14);
        malformed[1] = 1;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(malformed));
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x09))); // unsupported SOU_TRN

        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        postFrame(sgbBus, 3);
        assertEquals(3, transfer.get().dataTransfer[0]);
    }

    @Test
    public void receiverAbortDoesNotCancelAnAlreadyAcceptedTransfer() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.ChrTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.ChrTrnCmd.class);

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x13)));
        sgbBus.post(new SuperGameboy.PacketTransferAbortedEvent());
        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        postFrame(sgbBus, 3);

        assertEquals(3, transfer.get().dataTransfer[0]);
    }

    @Test
    public void invalidVramPayloadIsRejectedAndFollowingTransferStillCompletes() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.ChrTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.ChrTrnCmd.class);

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x13)));
        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        sgbBus.post(new VRamTransfer.VRamTransferComplete(new int[Commands.TRANSFER_SIZE - 1]));
        assertNull(transfer.get());

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x13)));
        postFrame(sgbBus, 4);
        postFrame(sgbBus, 5);
        postFrame(sgbBus, 6);
        assertEquals(6, transfer.get().dataTransfer[0]);
    }

    @Test
    public void invalidPicturePriorityIsRejectedBeforeBorderDeliveryAndFollowingTransferCompletes() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.PctTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.PctTrnCmd.class);

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x14)));
        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        int[] invalid = new int[Commands.TRANSFER_SIZE];
        setLittleEndian16(invalid, 0, 1 << 13);
        sgbBus.post(new VRamTransfer.VRamTransferComplete(invalid));
        assertNull(transfer.get());

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x14)));
        postFrame(sgbBus, 4);
        postFrame(sgbBus, 5);
        int[] valid = new int[Commands.TRANSFER_SIZE];
        sgbBus.post(new VRamTransfer.VRamTransferComplete(valid));
        assertEquals(0, transfer.get().dataTransfer[0]);
    }

    @Test
    public void completedTransferOwnsTheVramEventBuffer() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.ChrTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.ChrTrnCmd.class);

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x13)));
        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        int[] third = new int[Commands.TRANSFER_SIZE];
        third[0] = 0x5a;
        sgbBus.post(new VRamTransfer.VRamTransferComplete(third));
        third[0] = 0xa5;

        assertEquals(0x5a, transfer.get().dataTransfer[0]);
    }

    @Test
    public void unsupportedTransferDoesNotStartVramCapture() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        new SuperGameboy(sgbBus);
        AtomicReference<Commands.SoundTrnCmd> transfer = new AtomicReference<>();
        sgbBus.register(transfer::set, Commands.SoundTrnCmd.class);

        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet(0x09)));
        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        postFrame(sgbBus, 3);
        assertNull(transfer.get());
    }

    private static int[] packet(int code) {
        int[] packet = new int[16];
        packet[0] = code << 3 | 1;
        return packet;
    }

    private static void postFrame(EventBusImpl sgbBus, int value) {
        int[] frame = new int[0x1000];
        Arrays.fill(frame, value);
        sgbBus.post(new VRamTransfer.VRamTransferComplete(frame));
    }

    private static void setLittleEndian16(int[] data, int offset, int value) {
        data[offset] = value & 0xff;
        data[offset + 1] = value >>> 8 & 0xff;
    }
}
