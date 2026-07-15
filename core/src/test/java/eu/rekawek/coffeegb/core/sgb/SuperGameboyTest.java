package eu.rekawek.coffeegb.core.sgb;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import org.junit.Test;

import java.util.Arrays;
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
        var idleMemento = superGameboy.saveToMemento();

        int[] packet = new int[16];
        packet[0] = (0x14 << 3) | 1;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(packet));
        superGameboy.restoreFromMemento(idleMemento);

        postFrame(sgbBus, 1);
        postFrame(sgbBus, 2);
        postFrame(sgbBus, 3);
        assertNull(transfer.get());
    }

    @Test
    public void mementoOwnsPartialMultipacketData() {
        EventBusImpl sgbBus = new EventBusImpl(null, null, false);
        SuperGameboy superGameboy = new SuperGameboy(sgbBus);
        AtomicReference<Commands.Pal01Cmd> command = new AtomicReference<>();
        sgbBus.register(command::set, Commands.Pal01Cmd.class);

        int[] firstPacket = new int[16];
        firstPacket[0] = 2; // two-packet PAL01
        firstPacket[1] = 0x34;
        firstPacket[2] = 0x12;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(firstPacket));
        var partialMemento = superGameboy.saveToMemento();

        // Finish that command, then overwrite row zero of the live multipacket buffer.
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(new int[16]));
        int[] overwrite = new int[16];
        overwrite[0] = 1;
        overwrite[1] = 0x78;
        overwrite[2] = 0x56;
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(overwrite));

        superGameboy.restoreFromMemento(partialMemento);
        command.set(null);
        sgbBus.post(new SuperGameboy.PacketReceivedEvent(new int[16]));

        assertEquals(0x1234, command.get().getPalette0()[0]);
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
        var memento = command.saveToMemento();

        packet[1] = 0;
        frame[0] = 9;
        Commands.ChrTrnCmd restored =
                (Commands.ChrTrnCmd) Commands.TransferCommand.restoreFromMemento(memento);

        assertEquals(0x80, restored.getTileOffset());
        assertEquals(7, restored.dataTransfer[0]);
    }

    private static void postFrame(EventBusImpl sgbBus, int value) {
        int[] frame = new int[0x1000];
        Arrays.fill(frame, value);
        sgbBus.post(new VRamTransfer.VRamTransferComplete(frame));
    }
}
