package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;

public class GpuRegisterValues implements AddressSpace, StatefulComponent<GpuRegisterValues> {

    private static final GpuRegister[] ADDRESS_TO_REG = new GpuRegister[0xf];

    private static final int ADDRESS_TO_REG_BASE = GpuRegister.values()[0].getAddress();

    static {
        for (GpuRegister r : GpuRegister.values()) {
            ADDRESS_TO_REG[r.getAddress() - ADDRESS_TO_REG_BASE] = r;
        }
    }

    private final int[] values;

    // Two register-write latch banks, CPU-side pending and PPU-side visible. A slot
    // contains -1 when its write strobe is low; otherwise it carries the bus value
    // sampled by that register's consumer. Palette old|new, SCX old-value and WX's
    // just-written pulse therefore advance through the same two-stage path.
    private final int[] mixValues = new int[GpuRegister.values().length];

    private final int[] pendingMixValues = new int[GpuRegister.values().length];

    private static final GpuRegister[] CONFLICT_REGISTERS =
            {GpuRegister.BGP, GpuRegister.OBP0, GpuRegister.OBP1,
                    GpuRegister.SCX, GpuRegister.WX};

    private static final int[] WX_VISIBLE_BY_RELEASED_TICKS = {-1, 0, -1};

    private static final int[] WX_PENDING_BY_RELEASED_TICKS = {-1, -1, 0};

    private boolean gbc;

    private SpeedMode speedMode;

    public void setGbc(boolean gbc) {
        this.gbc = gbc;
    }

    public void setSpeedMode(SpeedMode speedMode) {
        this.speedMode = speedMode;
    }

    public boolean isGbc() {
        return gbc;
    }

    public int getSpeedMode() {
        return speedMode == null ? 1 : speedMode.getSpeedMode();
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

    /** Register value seen by the tile fetcher during the current PPU tick. */
    public int getForFetcher(GpuRegister reg) {
        if (reg == GpuRegister.SCX && mixValues[reg.ordinal()] >= 0) {
            return mixValues[reg.ordinal()];
        }
        return values[reg.ordinal()];
    }

    /** Register value as seen by the LCD output stage (with the DMG write-conflict mix). */
    public int getEffective(GpuRegister reg) {
        int mix = reg.ordinal() >= GpuRegister.BGP.ordinal() && reg.ordinal() <= GpuRegister.OBP1.ordinal()
                ? mixValues[reg.ordinal()] : -1;
        return mix >= 0 ? mix : values[reg.ordinal()];
    }

    /** Called once per GPU tick: CPU-side writes become visible for one PPU tick. */
    void tickConflicts() {
        for (GpuRegister reg : CONFLICT_REGISTERS) {
            mixValues[reg.ordinal()] = pendingMixValues[reg.ordinal()];
            pendingMixValues[reg.ordinal()] = -1;
        }
    }

    public boolean isWxJustChanged() {
        return mixValues[GpuRegister.WX.ordinal()] >= 0
                || pendingMixValues[GpuRegister.WX.ordinal()] >= 0;
    }

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
            // A physical CGB keeps VBK readable as bank 0 (FE) in DMG
            // compatibility mode, but writes to the bank-select bit are ignored.
            if (gbc && reg == GpuRegister.VBK
                    && speedMode != null && speedMode.isDmgCompat()) {
                return;
            }
            // the DMG palette-write conflict mix does not exist on the CGB
            if (!gbc && (reg == GpuRegister.BGP || reg == GpuRegister.OBP0 || reg == GpuRegister.OBP1)) {
                pendingMixValues[reg.ordinal()] = values[reg.ordinal()] | value;
            }
            if (reg == GpuRegister.WX) {
                pendingMixValues[reg.ordinal()] = 0;
            }
            if (gbc && reg == GpuRegister.SCX
                    && (speedMode == null || speedMode.getSpeedMode() == 1)) {
                pendingMixValues[reg.ordinal()] = values[reg.ordinal()];
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
    public ComponentState<GpuRegisterValues> captureState() {
        return new GpuRegisterValuesState(values.clone(), mixValues.clone(), pendingMixValues.clone(),
                captureWxJustChangedTicks(),
                mixValues[GpuRegister.SCX.ordinal()], pendingMixValues[GpuRegister.SCX.ordinal()]);
    }

    @Override
    public ComponentState<GpuRegisterValues> captureState(MachineStateCapture capture) {
        return new GpuRegisterValuesState(
                capture.ints(values),
                capture.ints(mixValues),
                capture.ints(pendingMixValues),
                captureWxJustChangedTicks(),
                mixValues[GpuRegister.SCX.ordinal()],
                pendingMixValues[GpuRegister.SCX.ordinal()]);
    }

    @Override
    public void restoreState(ComponentState<GpuRegisterValues> state) {
        if (!(state instanceof GpuRegisterValuesState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.values.length != mem.values.length) {
            throw new IllegalArgumentException("ComponentState array length doesn't match");
        }
        System.arraycopy(mem.values, 0, this.values, 0, this.values.length);
        if (mem.mixValues != null && mem.pendingMixValues != null) {
            System.arraycopy(mem.mixValues, 0, this.mixValues, 0, this.mixValues.length);
            System.arraycopy(mem.pendingMixValues, 0, this.pendingMixValues, 0, this.pendingMixValues.length);
        } else {
            java.util.Arrays.fill(this.mixValues, -1);
            java.util.Arrays.fill(this.pendingMixValues, -1);
        }
        mixValues[GpuRegister.WX.ordinal()] = WX_VISIBLE_BY_RELEASED_TICKS[mem.wxJustChangedTicks];
        pendingMixValues[GpuRegister.WX.ordinal()] =
                WX_PENDING_BY_RELEASED_TICKS[mem.wxJustChangedTicks];
        mixValues[GpuRegister.SCX.ordinal()] = mem.scxOldValue;
        pendingMixValues[GpuRegister.SCX.ordinal()] = mem.pendingScxOldValue;
    }

    private int captureWxJustChangedTicks() {
        int visible = mixValues[GpuRegister.WX.ordinal()] + 1;
        int pending = pendingMixValues[GpuRegister.WX.ordinal()] + 1;
        return pending * 2 + visible * (1 - pending);
    }

    private record GpuRegisterValuesState(int[] values, int[] mixValues, int[] pendingMixValues,
                                            int wxJustChangedTicks, int scxOldValue,
                                            int pendingScxOldValue)
            implements ComponentState<GpuRegisterValues> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record GpuRegisterValuesMemento(int[] values, int[] mixValues, int[] pendingMixValues,
                                            int wxJustChangedTicks, int scxOldValue,
                                            int pendingScxOldValue)
            implements Memento<GpuRegisterValues> {
    }
}
