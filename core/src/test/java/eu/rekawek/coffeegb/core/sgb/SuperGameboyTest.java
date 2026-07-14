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

    private static void postFrame(EventBusImpl sgbBus, int value) {
        int[] frame = new int[0x1000];
        Arrays.fill(frame, value);
        sgbBus.post(new VRamTransfer.VRamTransferComplete(frame));
    }
}
