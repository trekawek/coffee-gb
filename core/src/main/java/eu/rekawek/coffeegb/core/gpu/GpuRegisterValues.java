package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class GpuRegisterValues implements AddressSpace, Serializable, Originator<GpuRegisterValues> {

    private static final GpuRegister[] ADDRESS_TO_REG = new GpuRegister[0xf];

    private static final int ADDRESS_TO_REG_BASE = GpuRegister.values()[0].getAddress();

    static {
        for (GpuRegister r : GpuRegister.values()) {
            ADDRESS_TO_REG[r.getAddress() - ADDRESS_TO_REG_BASE] = r;
        }
    }

    private final int[] values;

    // DMG palette write conflict (mealybug m3_bgp_change): during the T-cycle in which
    // a CPU write to BGP/OBP0/OBP1 lands, the LCD output stage reads old|new; the new
    // value settles one T-cycle later. -1 = no write this tick.
    private final int[] mixValues = new int[GpuRegister.values().length];

    private final int[] pendingMixValues = new int[GpuRegister.values().length];

    // ticks remaining in which WX counts as "just written": the DMG's WX==position+6
    // window desync is suppressed for one machine cycle after a WX write (SameBoy
    // wx_just_changed)
    private int wxJustChangedTicks;

    private static final int WX_CHANGE_TICKS = 2;

    private boolean gbc;

    public void setGbc(boolean gbc) {
        this.gbc = gbc;
    }

    public GpuRegisterValues() {
        values = new int[GpuRegister.values().length];
        java.util.Arrays.fill(mixValues, -1);
        java.util.Arrays.fill(pendingMixValues, -1);
        // the object palettes are uninitialized at power on and read 0xff; neither
        // boot ROM writes them (gbtests INITREGS)
        values[GpuRegister.OBP0.ordinal()] = 0xff;
        values[GpuRegister.OBP1.ordinal()] = 0xff;
    }

    public int get(GpuRegister reg) {
        return values[reg.ordinal()];
    }

    /** Register value as seen by the LCD output stage (with the DMG write-conflict mix). */
    public int getEffective(GpuRegister reg) {
        int mix = mixValues[reg.ordinal()];
        return mix >= 0 ? mix : values[reg.ordinal()];
    }

    /** Called once per GPU tick: a mix value lives for the single tick after the write. */
    void tickConflicts() {
        for (GpuRegister reg : PALETTE_REGISTERS) {
            mixValues[reg.ordinal()] = pendingMixValues[reg.ordinal()];
            pendingMixValues[reg.ordinal()] = -1;
        }
        if (wxJustChangedTicks > 0) {
            wxJustChangedTicks--;
        }
    }

    public boolean isWxJustChanged() {
        return wxJustChangedTicks > 0;
    }

    private static final GpuRegister[] PALETTE_REGISTERS =
            {GpuRegister.BGP, GpuRegister.OBP0, GpuRegister.OBP1};

    public void put(GpuRegister reg, int value) {
        values[reg.ordinal()] = value;
    }

    public void inc(GpuRegister reg) {
        ++values[reg.ordinal()];
    }

    @Override
    public boolean accepts(int address) {
        return fromAddress(address) != null;
    }

    @Override
    public void setByte(int address, int value) {
        GpuRegister reg = fromAddress(address);
        if (reg != null && reg.getType().isAllowsWrite()) {
            // the DMG palette-write conflict mix does not exist on the CGB
            if (!gbc && (reg == GpuRegister.BGP || reg == GpuRegister.OBP0 || reg == GpuRegister.OBP1)) {
                pendingMixValues[reg.ordinal()] = values[reg.ordinal()] | value;
            }
            if (reg == GpuRegister.WX) {
                wxJustChangedTicks = WX_CHANGE_TICKS;
            }
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

    @Override
    public Memento<GpuRegisterValues> saveToMemento() {
        return new GpuRegisterValuesMemento(values.clone(), mixValues.clone(), pendingMixValues.clone(), wxJustChangedTicks);
    }

    @Override
    public void restoreFromMemento(Memento<GpuRegisterValues> memento) {
        if (!(memento instanceof GpuRegisterValuesMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.values.length != mem.values.length) {
            throw new IllegalArgumentException("Memento array length doesn't match");
        }
        System.arraycopy(mem.values, 0, this.values, 0, this.values.length);
        if (mem.mixValues != null && mem.pendingMixValues != null) {
            System.arraycopy(mem.mixValues, 0, this.mixValues, 0, this.mixValues.length);
            System.arraycopy(mem.pendingMixValues, 0, this.pendingMixValues, 0, this.pendingMixValues.length);
        } else {
            java.util.Arrays.fill(this.mixValues, -1);
            java.util.Arrays.fill(this.pendingMixValues, -1);
        }
        this.wxJustChangedTicks = mem.wxJustChangedTicks;
    }

    private record GpuRegisterValuesMemento(int[] values, int[] mixValues, int[] pendingMixValues,
                                            int wxJustChangedTicks)
            implements Memento<GpuRegisterValues> {
    }
}
