package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

/** The model-dependent $FEA0-$FEFF area immediately after OAM. */
final class OamEchoRam implements AddressSpace, Serializable, Originator<OamEchoRam> {

    private static final int OFFSET = 0xfea0;

    private final boolean gbc;

    private final boolean cgb0Revision;

    private final int[] ram = new int[0x60];

    private Gpu gpu;

    OamEchoRam(boolean gbc) {
        this(gbc, false);
    }

    OamEchoRam(boolean gbc, boolean cgb0Revision) {
        this.gbc = gbc;
        this.cgb0Revision = cgb0Revision;
    }

    void setGpu(Gpu gpu) {
        this.gpu = gpu;
    }

    @Override
    public boolean accepts(int address) {
        return address >= OFFSET && address < OFFSET + ram.length;
    }

    @Override
    public void setByte(int address, int value) {
        if (gpu != null && !gpu.isOamAvailableForCpu(true)) {
            return;
        }
        if (gbc) {
            ram[translate(address)] = value;
        }
    }

    @Override
    public int getByte(int address) {
        if (gpu != null && !gpu.isOamAvailableForCpu(false)) {
            return 0xff;
        }
        return gbc ? ram[translate(address)] : 0x00;
    }

    private int translate(int address) {
        if (cgb0Revision) {
            // CGB-0 does not decode address bits 3 and 4 in this area.
            return ((address & 0xff) & ~0x18) - (OFFSET & 0xff);
        }
        // CGB-D decodes FEA0-FEBF normally. In FEC0-FEFF address bits 5
        // and 4 are not decoded, so each 16-byte window aliases FEF0-FEFF.
        return address < 0xfec0 ? address - OFFSET : 0x20 + (address & 0x0f);
    }

    @Override
    public Memento<OamEchoRam> saveToMemento() {
        return new OamEchoRamMemento(ram.clone());
    }

    @Override
    public void restoreFromMemento(Memento<OamEchoRam> memento) {
        if (!(memento instanceof OamEchoRamMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
    }

    private record OamEchoRamMemento(int[] ram) implements Memento<OamEchoRam> {
    }
}
