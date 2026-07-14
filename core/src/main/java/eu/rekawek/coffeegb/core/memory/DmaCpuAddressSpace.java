package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;

import java.io.Serializable;

/** Applies OAM DMA bus conflicts to memory accesses made by the CPU. */
public class DmaCpuAddressSpace implements AddressSpace, Serializable {

    private final AddressSpace addressSpace;

    private final Dma dma;

    private final boolean gbc;

    public DmaCpuAddressSpace(AddressSpace addressSpace, Dma dma, boolean gbc) {
        this.addressSpace = addressSpace;
        this.dma = dma;
        this.gbc = gbc;
    }

    @Override
    public boolean accepts(int address) {
        return true;
    }

    @Override
    public void setByte(int address, int value) {
        if (!dma.isCpuAccessBlocked(address, gbc)) {
            addressSpace.setByte(address, value);
        }
    }

    @Override
    public int getByte(int address) {
        if (dma.isCpuAccessBlocked(address, gbc)) {
            return dma.getCpuBusValue();
        }
        return addressSpace.getByte(address);
    }
}
