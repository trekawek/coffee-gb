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

    private int regValue;

    public Dma(AddressSpace addressSpace, AddressSpace oam, SpeedMode speedMode) {
        this.addressSpace = new DmaAddressSpace(addressSpace);
        this.speedMode = speedMode;
        this.oam = oam;
        // FF46 most commonly powers up as 00 on CGB and FF on DMG.
        // Doc Cosmos reads FF46 to choose the phase of its title-screen scroller.
        regValue = speedMode.isGbc() ? 0x00 : 0xff;
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

    public boolean isCpuAccessBlocked(int address, boolean gbc) {
        if (!isOamBlocked() || address == 0xff46 || (address >= 0xfe00 && address < 0xff00)
                || (address >= 0xff80 && address < 0xffff)) {
            return false;
        }
        int cpuBus = getBus(address, gbc);
        return cpuBus >= 0 && cpuBus == getBus(from, gbc);
    }

    public int getCpuBusValue() {
        int byteIndex = Math.max(0, Math.min(0x9f, (ticks - 5) / 4));
        return addressSpace.getByte(from + byteIndex);
    }

    private static int getBus(int address, boolean gbc) {
        if (address < 0x8000 || (address >= 0xa000 && address < 0xc000)) {
            return 0; // cartridge
        } else if (address >= 0x8000 && address < 0xa000) {
            return 1; // VRAM
        } else if (address >= 0xc000 && address < 0xfe00) {
            return gbc ? 2 : 0; // WRAM has its own bus only on CGB
        } else {
            return -1; // OAM, I/O and HRAM are not on a DMA source bus
        }
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
