package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class Dma implements AddressSpace, Serializable, Originator<Dma> {

    private final AddressSpace addressSpace;

    private final AddressSpace oam;

    private final SpeedMode speedMode;

    private boolean transferInProgress;

    private boolean restarted;

    private int from;

    private int ticks;

    private int regValue = 0xff;

    public Dma(AddressSpace addressSpace, AddressSpace oam, SpeedMode speedMode) {
        this.addressSpace = new DmaAddressSpace(addressSpace);
        this.speedMode = speedMode;
        this.oam = oam;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff46;
    }

    public void tick() {
        if (transferInProgress) {
            if (++ticks >= 648 / speedMode.getSpeedMode()) {
                transferInProgress = false;
                restarted = false;
                ticks = 0;
                for (int i = 0; i < 0xa0; i++) {
                    oam.setByte(0xfe00 + i, addressSpace.getByte(from + i));
                }
            }
        }
    }

    @Override
    public void setByte(int address, int value) {
        from = value * 0x100;
        restarted = isOamBlocked();
        ticks = 0;
        transferInProgress = true;
        regValue = value;
    }

    @Override
    public int getByte(int address) {
        return regValue;
    }

    public boolean isOamBlocked() {
        return restarted || (transferInProgress && ticks >= 5);
    }

    @Override
    public Memento<Dma> saveToMemento() {
        return new DmaMemento(transferInProgress, restarted, from, ticks, regValue);
    }

    @Override
    public void restoreFromMemento(Memento<Dma> memento) {
        if (!(memento instanceof DmaMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.transferInProgress = mem.transferInProgress;
        this.restarted = mem.restarted;
        this.from = mem.from;
        this.ticks = mem.ticks;
        this.regValue = mem.regValue;
    }

    public record DmaMemento(boolean transferInProgress, boolean restarted, int from, int ticks,
                             int regValue) implements Memento<Dma> {
    }
}
