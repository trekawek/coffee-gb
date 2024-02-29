package eu.rekawek.coffeegb.gpu;

import eu.rekawek.coffeegb.AddressSpace;

import java.io.Serializable;

public class GpuRegisterValues implements AddressSpace, Serializable {

    private static final GpuRegister[] ADDRESS_TO_REG = new GpuRegister[0xf];

    private static final int ADDRESS_TO_REG_BASE = GpuRegister.values()[0].getAddress();

    static {
        for (GpuRegister r : GpuRegister.values()) {
            ADDRESS_TO_REG[r.getAddress() - ADDRESS_TO_REG_BASE] = r;
        }
    }

    private final int[] values;

    public GpuRegisterValues() {
        values = new int[GpuRegister.values().length];
    }

    public int get(GpuRegister reg) {
        return values[reg.ordinal()];
    }

    public void put(GpuRegister reg, int value) {
        values[reg.ordinal()] = value;
    }

    public int preIncrement(GpuRegister reg) {
        return ++values[reg.ordinal()];
    }

    @Override
    public boolean accepts(int address) {
        return fromAddress(address) != null;
    }

    @Override
    public void setByte(int address, int value) {
        GpuRegister reg = fromAddress(address);
        if (reg != null && reg.getType().isAllowsWrite()) {
            values[reg.ordinal()] = value;
        }
    }

    @Override
    public int getByte(int address) {
        GpuRegister reg = fromAddress(address);
        if (reg != null && reg.getType().isAllowsRead()) {
            return values[reg.ordinal()];
        } else {
            return 0xff;
        }
    }

    private static GpuRegister fromAddress(int address) {
        int index = address - ADDRESS_TO_REG_BASE;
        if (index >= 0 && index < ADDRESS_TO_REG.length) {
            return ADDRESS_TO_REG[index];
        } else {
            return null;
        }
    }
}
