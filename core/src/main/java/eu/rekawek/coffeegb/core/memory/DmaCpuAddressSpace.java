package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;

import java.io.Serializable;

/** Applies OAM DMA bus conflicts to memory accesses made by the CPU. */
public class DmaCpuAddressSpace implements AddressSpace, Serializable {

    private final AddressSpace addressSpace;

    private final Dma dma;

    private final boolean gbc;

    private final boolean blockedReadsReturnFF;

    public DmaCpuAddressSpace(AddressSpace addressSpace, Dma dma, boolean gbc) {
        this(addressSpace, dma, gbc, false);
    }

    public DmaCpuAddressSpace(AddressSpace addressSpace, Dma dma, boolean gbc,
                              boolean blockedReadsReturnFF) {
        this.addressSpace = addressSpace;
        this.dma = dma;
        this.gbc = gbc;
        this.blockedReadsReturnFF = blockedReadsReturnFF;
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
            return blockedReadsReturnFF ? 0xff : dma.getCpuBusValue();
        }
        return addressSpace.getByte(address);
    }
}
