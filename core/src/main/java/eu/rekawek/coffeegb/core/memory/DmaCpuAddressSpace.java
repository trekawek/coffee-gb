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
        if (dma.isOamBlocked() && address >= 0xfea0 && address < 0xff00) {
            // CGB normally mirrors parts of OAM through the unusable range. OAM DMA
            // disconnects that whole range, so a stack write there must not leak into
            // the mirror (or the generic backing address space).
            return;
        }
        if (dma.isCpuAccessBlocked(address, gbc)) {
            dma.onCpuBusWrite(value);
        } else {
            addressSpace.setByteFromCpu(dma.mapUnblockedCpuAddress(address), value);
        }
    }

    @Override
    public int getByte(int address) {
        if (dma.isOamBlocked() && address >= 0xfea0 && address < 0xff00) {
            return 0xff;
        }
        if (dma.isCpuAccessBlocked(address, gbc)) {
            return blockedReadsReturnFF ? 0xff : dma.getCpuBusValue();
        }
        int highBusValue = dma.getUnblockedDmgHighBusValue(address);
        if (highBusValue >= 0) {
            return highBusValue;
        }
        return addressSpace.getByte(dma.mapUnblockedCpuAddress(address));
    }
}
