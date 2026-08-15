package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpuRegisterValuesTest {

    @Test
    public void dmgCompatibilityIgnoresVramBankWrites() {
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setDmgCompat(true);
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(true);
        registers.setSpeedMode(speedMode);

        registers.setByte(GpuRegister.VBK.getAddress(), 1);

        assertEquals(0, registers.get(GpuRegister.VBK));
    }

    @Test
    public void nativeCgbModeAcceptsVramBankWrites() {
        SpeedMode speedMode = new SpeedMode(true);
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(true);
        registers.setSpeedMode(speedMode);

        registers.setByte(GpuRegister.VBK.getAddress(), 1);

        assertEquals(1, registers.get(GpuRegister.VBK));
    }

    @Test
    public void sharedLatchBanksMatchTheReleasedTransitions() {
        verifySequences(false, 6, new LegacyConflictModel(false), configured(false));
        verifySequences(true, 6, new LegacyConflictModel(true), configured(true));
    }

    @Test
    public void releasedIndependentStateSlotsMapOntoTheSharedLatches() throws Exception {
        int length = GpuRegister.values().length;
        int[] values = new int[length];
        values[GpuRegister.SCX.ordinal()] = 0x56;
        int[] visible = new int[length];
        int[] pending = new int[length];
        Arrays.fill(visible, -1);
        Arrays.fill(pending, -1);
        visible[GpuRegister.BGP.ordinal()] = 0xff;
        pending[GpuRegister.OBP0.ordinal()] = 0xa5;

        GpuRegisterValues registers = configured(true);
        registers.restoreState(releasedState(values, visible, pending, 2, 0x12, 0x34));

        assertEquals(0xff, registers.getEffective(GpuRegister.BGP));
        assertEquals(0, registers.getEffective(GpuRegister.OBP0));
        assertEquals(0x12, registers.getForFetcher(GpuRegister.SCX));
        assertTrue(registers.isWxJustChanged());

        registers.tickConflicts();
        assertEquals(0, registers.getEffective(GpuRegister.BGP));
        assertEquals(0xa5, registers.getEffective(GpuRegister.OBP0));
        assertEquals(0x34, registers.getForFetcher(GpuRegister.SCX));
        assertTrue(registers.isWxJustChanged());

        registers.tickConflicts();
        assertEquals(0, registers.getEffective(GpuRegister.OBP0));
        assertEquals(0x56, registers.getForFetcher(GpuRegister.SCX));
        assertFalse(registers.isWxJustChanged());
    }

    @Test
    public void nonPaletteEffectiveReadsIgnoreScxAndWxConflictLatches() {
        GpuRegisterValues registers = configured(true);
        registers.put(GpuRegister.SCX, 0x12);
        registers.setByte(GpuRegister.SCX.getAddress(), 0x34);
        registers.setByte(GpuRegister.WX.getAddress(), 0x27);

        registers.tickConflicts();

        assertEquals(0x12, registers.getForFetcher(GpuRegister.SCX));
        assertTrue(registers.isWxJustChanged());
        assertEquals(0x34, registers.getEffective(GpuRegister.SCX));
        assertEquals(0x27, registers.getEffective(GpuRegister.WX));
    }

    private static void verifySequences(boolean gbc, int remaining,
                                        LegacyConflictModel expected,
                                        GpuRegisterValues actual) {
        assertEquivalent(expected, actual);
        if (remaining == 0) {
            return;
        }
        int[][] actions = {
                null,
                {GpuRegister.BGP.getAddress(), 0x1b},
                {GpuRegister.OBP0.getAddress(), 0xa5},
                {GpuRegister.WX.getAddress(), 0x27},
                {GpuRegister.SCX.getAddress(), 0x12},
                {GpuRegister.SCX.getAddress(), 0x34}
        };
        for (int[] action : actions) {
            LegacyConflictModel nextExpected = new LegacyConflictModel(expected);
            GpuRegisterValues nextActual = configured(gbc);
            nextActual.restoreState(actual.captureState());
            if (action == null) {
                nextExpected.tick();
                nextActual.tickConflicts();
            } else {
                nextExpected.write(action[0], action[1]);
                nextActual.setByte(action[0], action[1]);
            }
            verifySequences(gbc, remaining - 1, nextExpected, nextActual);
        }
    }

    private static void assertEquivalent(LegacyConflictModel expected,
                                         GpuRegisterValues actual) {
        for (GpuRegister reg : GpuRegister.values()) {
            assertEquals(expected.values[reg.ordinal()], actual.get(reg));
            assertEquals(expected.effective(reg), actual.getEffective(reg));
        }
        assertEquals(expected.fetcherScx(), actual.getForFetcher(GpuRegister.SCX));
        assertEquals(expected.wxTicks > 0, actual.isWxJustChanged());
    }

    private static GpuRegisterValues configured(boolean gbc) {
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(gbc);
        registers.setSpeedMode(new SpeedMode(gbc));
        return registers;
    }

    @SuppressWarnings("unchecked")
    private static ComponentState<GpuRegisterValues> releasedState(
            int[] values, int[] mixValues, int[] pendingMixValues,
            int wxJustChangedTicks, int scxOldValue, int pendingScxOldValue) throws Exception {
        Class<?> type = Class.forName(GpuRegisterValues.class.getName()
                + "$GpuRegisterValuesState");
        Constructor<?> constructor = type.getDeclaredConstructor(
                int[].class, int[].class, int[].class,
                int.class, int.class, int.class);
        constructor.setAccessible(true);
        return (ComponentState<GpuRegisterValues>) constructor.newInstance(
                values, mixValues, pendingMixValues,
                wxJustChangedTicks, scxOldValue, pendingScxOldValue);
    }

    /** Frozen copy of the six-field implementation replaced by the shared latch banks. */
    private static class LegacyConflictModel {

        private final int[] values = new int[GpuRegister.values().length];
        private final int[] mixValues = new int[values.length];
        private final int[] pendingMixValues = new int[values.length];
        private final boolean gbc;
        private int wxTicks;
        private int scxOldValue = -1;
        private int pendingScxOldValue = -1;

        private LegacyConflictModel(boolean gbc) {
            this.gbc = gbc;
            Arrays.fill(mixValues, -1);
            Arrays.fill(pendingMixValues, -1);
            values[GpuRegister.OBP0.ordinal()] = 0xff;
            values[GpuRegister.OBP1.ordinal()] = 0xff;
        }

        private LegacyConflictModel(LegacyConflictModel source) {
            gbc = source.gbc;
            System.arraycopy(source.values, 0, values, 0, values.length);
            System.arraycopy(source.mixValues, 0, mixValues, 0, mixValues.length);
            System.arraycopy(source.pendingMixValues, 0, pendingMixValues, 0,
                    pendingMixValues.length);
            wxTicks = source.wxTicks;
            scxOldValue = source.scxOldValue;
            pendingScxOldValue = source.pendingScxOldValue;
        }

        private void write(int address, int value) {
            GpuRegister reg = Arrays.stream(GpuRegister.values())
                    .filter(r -> r.getAddress() == address)
                    .findFirst().orElseThrow();
            int i = reg.ordinal();
            if (!gbc && (reg == GpuRegister.BGP || reg == GpuRegister.OBP0
                    || reg == GpuRegister.OBP1)) {
                pendingMixValues[i] = values[i] | value;
            }
            if (reg == GpuRegister.WX) {
                wxTicks = 2;
            }
            if (gbc && reg == GpuRegister.SCX) {
                pendingScxOldValue = values[i];
            }
            values[i] = value;
        }

        private void tick() {
            scxOldValue = pendingScxOldValue;
            pendingScxOldValue = -1;
            for (GpuRegister reg : new GpuRegister[]{GpuRegister.BGP, GpuRegister.OBP0,
                    GpuRegister.OBP1}) {
                mixValues[reg.ordinal()] = pendingMixValues[reg.ordinal()];
                pendingMixValues[reg.ordinal()] = -1;
            }
            if (wxTicks > 0) {
                wxTicks--;
            }
        }

        private int effective(GpuRegister reg) {
            int mix = mixValues[reg.ordinal()];
            return mix >= 0 ? mix : values[reg.ordinal()];
        }

        private int fetcherScx() {
            return scxOldValue >= 0 ? scxOldValue : values[GpuRegister.SCX.ordinal()];
        }
    }
}
