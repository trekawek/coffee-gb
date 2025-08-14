package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.events.Event;
import eu.rekawek.coffeegb.events.EventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

public class VRamTransfer {

    private static final Logger LOG = LoggerFactory.getLogger(VRamTransfer.class);

    private final EventBus sgbBus;

    private final int[] buffer = new int[0x1000];

    private int i;

    public VRamTransfer(EventBus sgbBus) {
        this.sgbBus = sgbBus;
    }

    public void putPixel(int pixel) {
        int tileX = (i % 160) / 8;
        int tileY = (i / 160) / 8;

        int tileLineY = (i / 160) % 8;

        int j = tileY * 20 * 16 + tileX * 16 + tileLineY * 2;
        if (j < buffer.length) {
            buffer[j] <<= 1;
            buffer[j] |= pixel & 1;

            buffer[j + 1] <<= 1;
            buffer[j + 1] |= (pixel & 2) >> 1;
        }
        i++;
    }

    public void frameIsReady() {
        if (sgbBus != null) {
            sgbBus.post(new VRamTransferComplete(buffer));
            Arrays.fill(buffer, 0);
            i = 0;
        }
    }

    public record VRamTransferComplete(int[] buffer) implements Event {
    }
}
