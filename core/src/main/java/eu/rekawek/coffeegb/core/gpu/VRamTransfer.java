package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.util.Arrays;

public class VRamTransfer implements Originator<VRamTransfer> {

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

    @Override
    public Memento<VRamTransfer> saveToMemento() {
        return new VRamTransferMemento(buffer.clone(), i);
    }

    @Override
    public void restoreFromMemento(Memento<VRamTransfer> memento) {
        if (!(memento instanceof VRamTransferMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.buffer, 0, buffer, 0, mem.buffer.length);
        i = mem.i;
    }

    private record VRamTransferMemento(int[] buffer, int i) implements Memento<VRamTransfer> {}

    public record VRamTransferComplete(int[] buffer) implements Event {
    }
}
