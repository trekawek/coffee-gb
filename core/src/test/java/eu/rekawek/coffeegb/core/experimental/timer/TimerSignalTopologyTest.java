package eu.rekawek.coffeegb.core.experimental.timer;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Executable sketch of the timer signal topology in the DMG-CPU B netlist.
 *
 * <p>This deliberately does not share code with {@code Timer}.  Its purpose is to
 * test whether the observable overflow rules need a counted "overflow state" at
 * all.  The cell mapping is:
 *
 * <ul>
 *   <li>NUGA: TIMA bit 7</li>
 *   <li>NYDU: NUGA sampled by BOGA (the 1 MHz CPU clock)</li>
 *   <li>MERY: {@code NYDU.q && !NUGA.q}</li>
 *   <li>MOBA: MERY sampled by BOGA; its Q is INT_TIMER and the TIMA reload level</li>
 *   <li>NYBO: the reset-dominant IF.2 latch clocked by rising INT_TIMER</li>
 * </ul>
 *
 * <p>MEXU drives the parallel-load input of every TIMA bit and, through MUGY,
 * resets NYDU.  That one connection makes a pre-reload TIMA write cancel an
 * overflow without a special cancellation branch.
 */
public class TimerSignalTopologyTest {

    @Test
    public void sampledMsbFallCreatesExactlyOneReloadLevel() {
        for (int tma = 0; tma < 0x100; tma++) {
            DmgTimerIsland timer = new DmgTimerIsland(0xff, tma);

            timer.timerInputFallingEdge();
            assertEquals(0, timer.tima);
            assertFalse(timer.reloadLevel);
            assertFalse(timer.timerIf);

            timer.bogaRisingEdge(false);
            assertEquals(tma, timer.tima);
            assertTrue(timer.reloadLevel);
            assertTrue(timer.timerIf);

            timer.bogaRisingEdge(false);
            assertEquals(tma, timer.tima);
            assertFalse(timer.reloadLevel);

            // MERY is low after NYDU has been reset by the load level.  Merely
            // leaving TIMA at zero cannot retrigger IF.
            timer.clearTimerIf();
            timer.bogaRisingEdge(false);
            assertFalse(timer.timerIf);
        }
    }

    @Test
    public void aTimaLoadCancelsEveryPendingOverflowWithoutAnOverflowFlag() {
        for (int value = 0; value < 0x100; value++) {
            DmgTimerIsland timer = new DmgTimerIsland(0xff, 0xa5);
            timer.timerInputFallingEdge();

            timer.writeTima(value);
            timer.bogaRisingEdge(false);

            assertEquals(value, timer.tima);
            assertFalse(timer.reloadLevel);
            assertFalse(timer.timerIf);
        }
    }

    @Test
    public void reloadLevelOwnsTimaAndLiveTmaBus() {
        DmgTimerIsland timer = new DmgTimerIsland(0xff, 0xa5);
        timer.timerInputFallingEdge();
        timer.bogaRisingEdge(false);

        timer.writeTima(0x11);
        assertEquals(0xa5, timer.tima);

        timer.writeTma(0x3c);
        assertEquals(0x3c, timer.tima);

        timer.bogaRisingEdge(false);
        timer.writeTima(0x11);
        assertEquals(0x11, timer.tima);
    }

    @Test
    public void interruptAcknowledgeSuppressesOnlyAnOverlappingIntTimerEdge() {
        DmgTimerIsland overlap = new DmgTimerIsland(0xff, 0x42);
        overlap.timerInputFallingEdge();
        overlap.bogaRisingEdge(true);
        assertTrue(overlap.reloadLevel);
        assertFalse(overlap.timerIf);

        // INT_TIMER is a level lasting one BOGA period, but NYBO is edge
        // triggered.  Releasing acknowledge later must not invent another edge.
        overlap.bogaRisingEdge(false);
        assertFalse(overlap.timerIf);

        DmgTimerIsland endedBeforeEdge = new DmgTimerIsland(0xff, 0x42);
        endedBeforeEdge.timerInputFallingEdge();
        endedBeforeEdge.clearTimerIf();
        endedBeforeEdge.bogaRisingEdge(false);
        assertTrue(endedBeforeEdge.timerIf);
    }

    @Test
    public void haltedCpuSeesTheNewIfOnTheFollowingBogaEdge() {
        DmgTimerIsland timer = new DmgTimerIsland(0xff, 0x42);
        timer.cpuHalted = true;
        timer.timerInputFallingEdge();

        // All BOGA-clocked storage captures from the old signal vector.  NYBO
        // rises here, while the CPU halt latch still samples old IF=0.
        timer.bogaRisingEdge(false);
        assertTrue(timer.timerIf);
        assertTrue(timer.cpuHalted);

        timer.bogaRisingEdge(false);
        assertFalse(timer.cpuHalted);
    }

    @Test
    public void divResetFanoutIsOnlyASetOfFallingEdges() {
        for (int div = 0; div < 0x10000; div++) {
            for (int tac = 0; tac < 8; tac++) {
                DividerFanout fanout = new DividerFanout(div, tac, true, false);
                fanout.writeDiv();

                assertEquals(timerInput(div, tac) ? 1 : 0, fanout.timaClocks);
                assertEquals((div & (1 << 12)) != 0 ? 1 : 0, fanout.apuClocks);
            }
        }
    }

    @Test
    public void allNaturalTapEdgesAreDerivedFromTheOldAndNewDividerVector() {
        for (int div = 0; div < 0x10000; div++) {
            int nextDiv = (div + 1) & 0xffff;
            for (int tac = 0; tac < 8; tac++) {
                DividerFanout fanout = new DividerFanout(div, tac, true, false);
                fanout.advanceDivider(1);

                boolean timerFell = timerInput(div, tac) && !timerInput(nextDiv, tac);
                boolean apuFell = (div & (1 << 12)) != 0 && (nextDiv & (1 << 12)) == 0;
                assertEquals(timerFell ? 1 : 0, fanout.timaClocks);
                assertEquals(apuFell ? 1 : 0, fanout.apuClocks);
            }
        }
    }

    @Test
    public void cgbDoubleSpeedTapKeepsTheApuRateConstantInMasterTime() {
        DividerFanout normal = new DividerFanout(0, 0, true, false);
        DividerFanout doubled = new DividerFanout(0, 0, true, true);

        for (int masterTick = 0; masterTick < 0x10000; masterTick++) {
            normal.advanceDivider(1);
            doubled.advanceDivider(2);
        }

        assertEquals(normal.apuClocks, doubled.apuClocks);
        assertEquals(8, normal.apuClocks);
    }

    private static boolean timerInput(int div, int tac) {
        int[] bits = {9, 3, 5, 7};
        return (tac & 0x04) != 0 && (div & (1 << bits[tac & 0x03])) != 0;
    }

    private static final class DmgTimerIsland {

        private int tima;

        private int tma;

        /** NYDU.q. */
        private boolean sampledTimaMsb;

        /** MOBA.q / INT_TIMER / TIMA parallel-load ownership. */
        private boolean reloadLevel;

        /** NYBO.q / IF.2. */
        private boolean timerIf;

        private boolean cpuHalted;

        private DmgTimerIsland(int tima, int tma) {
            this.tima = tima & 0xff;
            this.tma = tma & 0xff;
            this.sampledTimaMsb = msb(this.tima);
        }

        private void timerInputFallingEdge() {
            if (!reloadLevel) {
                tima = (tima + 1) & 0xff;
            }
        }

        private void bogaRisingEdge(boolean timerIfReset) {
            // CPU and timer latches capture the same old vector.
            boolean oldTimerIf = timerIf;
            boolean oldReloadLevel = reloadLevel;
            boolean mery = sampledTimaMsb && !msb(tima);

            if (cpuHalted && oldTimerIf && !timerIfReset) {
                cpuHalted = false;
            }

            boolean nextReloadLevel = mery;
            boolean intTimerRising = !oldReloadLevel && nextReloadLevel;

            // NYBO is a D=1 edge latch with an asynchronous, dominant reset.
            if (timerIfReset) {
                timerIf = false;
            } else if (intTimerRising) {
                timerIf = true;
            }

            // NYDU samples NUGA, except that the old or newly asserted MEXU
            // reload level asynchronously clears it through MUGY.
            boolean nextSampledMsb = msb(tima);
            reloadLevel = nextReloadLevel;
            sampledTimaMsb = oldReloadLevel || nextReloadLevel ? false : nextSampledMsb;

            if (reloadLevel) {
                tima = tma;
            }
        }

        private void writeTima(int value) {
            if (reloadLevel) {
                tima = tma;
            } else {
                tima = value & 0xff;
            }
            // A CPU TIMA load uses MEXU too, so MUGY clears NYDU even if the
            // value written has bit 7 set.
            sampledTimaMsb = false;
        }

        private void writeTma(int value) {
            tma = value & 0xff;
            if (reloadLevel) {
                tima = tma;
            }
        }

        private void clearTimerIf() {
            timerIf = false;
        }

        private static boolean msb(int value) {
            return (value & 0x80) != 0;
        }
    }

    private static final class DividerFanout {

        private int div;

        private final int tac;

        private final boolean apuEnabled;

        private final boolean doubleSpeed;

        private int timaClocks;

        private int apuClocks;

        private DividerFanout(int div, int tac, boolean apuEnabled, boolean doubleSpeed) {
            this.div = div & 0xffff;
            this.tac = tac & 7;
            this.apuEnabled = apuEnabled;
            this.doubleSpeed = doubleSpeed;
        }

        private void writeDiv() {
            settleDivider(0);
        }

        private void advanceDivider(int clocks) {
            for (int i = 0; i < clocks; i++) {
                settleDivider((div + 1) & 0xffff);
            }
        }

        private void settleDivider(int nextDiv) {
            boolean oldTimer = timerInput(div, tac);
            boolean newTimer = timerInput(nextDiv, tac);
            if (oldTimer && !newTimer) {
                timaClocks++;
            }

            int apuBit = doubleSpeed ? 13 : 12;
            boolean oldApu = apuEnabled && (div & (1 << apuBit)) != 0;
            boolean newApu = apuEnabled && (nextDiv & (1 << apuBit)) != 0;
            if (oldApu && !newApu) {
                apuClocks++;
            }

            div = nextDiv;
        }
    }
}
