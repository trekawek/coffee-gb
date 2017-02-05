package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.SpeedMode;

public class Dma implements AddressSpace {

    private final AddressSpace addressSpace;

    private final SpeedMode speedMode;

    private boolean transferInProgress;

    private int from;

    private int ticks;

    public Dma(AddressSpace addressSpace, SpeedMode speedMode) {
        this.addressSpace = addressSpace;
        this.speedMode = speedMode;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff46;
    }

    public void tick() {
        if (transferInProgress) {
            if (++ticks >= 671 / speedMode.getSpeedMode()) {
                transferInProgress = false;
                for (int i = 0; i < 0xa0; i++) {
                    addressSpace.setByte(0xfe00 + i, addressSpace.getByte(from + i));
                }
            }
        }
    }

    @Override
    public void setByte(int address, int value) {
        from = value * 0x100;
        ticks = 0;
        transferInProgress = true;
    }

    @Override
    public int getByte(int address) {
        return 0;
    }
}