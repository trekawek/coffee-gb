package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;

import java.io.Serializable;

/** OAM view used by the PPU, including the row driven by an active OAM DMA. */
public class DmaOamAddressSpace implements AddressSpace, Serializable {

    private final AddressSpace oam;

    private final Dma dma;

    public DmaOamAddressSpace(AddressSpace oam, Dma dma) {
        this.oam = oam;
        this.dma = dma;
    }

    @Override
    public boolean accepts(int address) {
        return oam.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        oam.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        return dma.getOamByteForPpu(address);
    }
}
