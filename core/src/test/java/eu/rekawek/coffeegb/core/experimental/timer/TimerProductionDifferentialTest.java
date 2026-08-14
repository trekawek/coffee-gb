package eu.rekawek.coffeegb.core.experimental.timer;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.timer.Timer;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * A CPU-clock-granular differential between the current counted overflow implementation and the
 * two-latch DMG timer topology.
 *
 * <p>This is an equivalence test, not an independent silicon oracle. Cell wiring and dominance are
 * asserted independently by {@link TimerSignalTopologyTest}; this class only asks whether that
 * topology can encode every state transition exposed by the current legal callback schedule. In
 * particular, its debug-delay comparisons and delayed publication of a reload-owned TMA write are
 * adapter compatibility checks, not new hardware claims.
 *
 * <p>The model deliberately has a BOGA phase independent of DIV. FF04 clears the divider stages,
 * but it does not re-anchor the 1 MHz CPU clock. This distinction is lost if reload timing is
 * reconstructed only from the low bits of {@code DIV}.
 *
 * <p>Writes are issued after a timer clock, matching the current {@code Gameboy.tick()} ordering:
 * Timer advances first and a CPU bus callback can follow later in the same public tick. A TMA write
 * while the asynchronous reload input is high is therefore published by this adapter at the next
 * CPU-clock boundary. There is no intervening CPU read slot, so this preserves every observable
 * production boundary without pretending that Java callback order is the hardware topology.
 */
public class TimerProductionDifferentialTest {

    private static final int[] FREQ_TO_BIT = {9, 3, 5, 7};

    @Test
    public void naturalOverflowsMatchForEveryTacTapAndCpuClock() {
        for (int selector = 0; selector < 4; selector++) {
            int bit = FREQ_TO_BIT[selector];
            int divImmediatelyBeforeFallingEdge = (1 << (bit + 1)) - 1;
            DifferentialTimer timer = DifferentialTimer.stable(
                    divImmediatelyBeforeFallingEdge, 0x04 | selector, 0xff, 0x20 + selector);

            for (int clock = 0; clock < 12; clock++) {
                timer.tickAndAssert("TAC selector " + selector + ", clock " + clock);
            }
        }
    }

    @Test
    public void divWriteOverflowKeepsBogaIndependentOfAllFourLowDividerPhases() {
        for (int divPhase = 0; divPhase < 4; divPhase++) {
            // Bit 3 is high in each seed, so clearing DIV produces the TIMA clock edge.
            DifferentialTimer timer = DifferentialTimer.stable(
                    0x0008 | divPhase, 0x05, 0xff, 0x40 + divPhase);

            timer.writeDivAndAssert("DIV phase " + divPhase + ", write");
            for (int clock = 0; clock < 9; clock++) {
                timer.tickAndAssert("DIV phase " + divPhase + ", clock " + clock);
            }
        }
    }

    @Test
    public void timaWritesMatchAtEveryPreReloadAndReloadClockPhase() {
        for (int writeSlot = 0; writeSlot < 8; writeSlot++) {
            DifferentialTimer timer = naturallyOverflowingTimer();
            for (int clock = 0; clock < writeSlot; clock++) {
                timer.tickAndAssert("TIMA slot " + writeSlot + ", prefix " + clock);
            }

            timer.writeTimaAndAssert(0x80 | writeSlot, "TIMA slot " + writeSlot + ", write");
            for (int clock = 0; clock < 12; clock++) {
                timer.tickAndAssert("TIMA slot " + writeSlot + ", suffix " + clock);
            }
        }
    }

    @Test
    public void tmaWritesRemainObservationallyEquivalentAtEveryReloadClockPhase() {
        for (int writeSlot = 0; writeSlot < 8; writeSlot++) {
            DifferentialTimer timer = naturallyOverflowingTimer();
            for (int clock = 0; clock < writeSlot; clock++) {
                timer.tickAndAssert("TMA slot " + writeSlot + ", prefix " + clock);
            }

            timer.writeTmaAndAssert(0x60 | writeSlot, "TMA slot " + writeSlot + ", write");
            for (int clock = 0; clock < 12; clock++) {
                timer.tickAndAssert("TMA slot " + writeSlot + ", suffix " + clock);
            }
        }
    }

    private static DifferentialTimer naturallyOverflowingTimer() {
        DifferentialTimer timer = DifferentialTimer.stable(0x000f, 0x05, 0xff, 0x42);
        timer.tickAndAssert("natural overflow edge");
        return timer;
    }

    private static boolean timerInput(int div, int tac) {
        int bit = FREQ_TO_BIT[tac & 0x03];
        return (tac & 0x04) != 0 && (div & (1 << bit)) != 0;
    }

    private static final class DifferentialTimer {

        private final InterruptManager interrupts;

        private final Timer production;

        private final TwoLatchTimer topology;

        private DifferentialTimer(InterruptManager interrupts, Timer production, TwoLatchTimer topology) {
            this.interrupts = interrupts;
            this.production = production;
            this.topology = topology;
            assertEquivalent("initial state");
        }

        private static DifferentialTimer stable(int div, int tac, int tima, int tma) {
            InterruptManager interrupts = new InterruptManager(false);
            interrupts.setByte(0xff0f, 0);
            Timer production = new Timer(interrupts, new SpeedMode(false));
            production.restoreState(new Timer.TimerState(
                    div,
                    tac,
                    tma,
                    tima,
                    timerInput(div, tac),
                    false,
                    0,
                    false,
                    0,
                    Integer.MAX_VALUE,
                    false,
                    false,
                    false));
            // Phase zero is the just-completed BOGA boundary. The first subsequent
            // CPU clock occupies phase one; the fourth reaches the next BOGA edge.
            TwoLatchTimer topology = new TwoLatchTimer(div, tac, tima, tma, 0);
            return new DifferentialTimer(interrupts, production, topology);
        }

        private void tickAndAssert(String context) {
            production.tick();
            topology.tickCpuClock();
            assertEquivalent(context);
        }

        private void writeDivAndAssert(String context) {
            production.setByte(0xff04, 0);
            topology.writeDiv();
            assertEquivalent(context);
        }

        private void writeTimaAndAssert(int value, String context) {
            production.setByte(0xff05, value);
            topology.writeTima(value);
            assertEquivalent(context);
        }

        private void writeTmaAndAssert(int value, String context) {
            production.setByte(0xff06, value);
            topology.writeTma(value);
            assertEquivalent(context);
        }

        private void assertEquivalent(String context) {
            assertEquals(context + ": DIV", topology.div, production.getDivCounter());
            assertEquals(context + ": TIMA", topology.tima, production.getDebugTima());
            assertEquals(context + ": TMA", topology.tma, production.getDebugTma());
            assertEquals(context + ": TAC", topology.tac | 0xf8, production.getDebugTac());
            assertEquals(context + ": overflow/reload state", topology.overflowPending(),
                    production.isDebugOverflowPending());
            assertEquals(context + ": clocks until reload", topology.clocksUntilReload(),
                    production.getDebugOverflowDelayTicks());
            assertEquals(context + ": IF.2", topology.timerIf,
                    interrupts.isInterruptFlagSet(InterruptManager.InterruptType.Timer));
        }
    }

    /**
     * The minimal CPU-clock projection of NUGA/NYDU/MERY/MOBA/NYBO.
     *
     * <p>{@code cpuPhase} is clock-router state, not divider state. NYDU and MOBA capture together
     * at phase zero. A TIMA load clears NYDU; an asserted old or new MOBA level owns TIMA for the
     * complete four-clock interval. IF.2 sees only the rising MOBA edge.
     */
    private static final class TwoLatchTimer {

        private int div;

        private final int tac;

        private int tima;

        private int tma;

        /** NYDU.q. */
        private boolean sampledTimaMsb;

        /** MOBA.q. */
        private boolean reloadLevel;

        /** NYBO.q. */
        private boolean timerIf;

        /** BOGA phase 0..3, independent of DIV and unaffected by FF04. */
        private int cpuPhase;

        private TwoLatchTimer(int div, int tac, int tima, int tma, int cpuPhase) {
            this.div = div & 0xffff;
            this.tac = tac & 0x07;
            this.tima = tima & 0xff;
            this.tma = tma & 0xff;
            this.cpuPhase = cpuPhase & 3;
            this.sampledTimaMsb = msb(this.tima);
        }

        private void tickCpuClock() {
            boolean oldReloadLevel = reloadLevel;
            boolean oldTimerInput = timerInput(div, tac);
            div = (div + 1) & 0xffff;
            boolean newTimerInput = timerInput(div, tac);
            if (oldTimerInput && !newTimerInput && !oldReloadLevel) {
                incrementTima();
            }

            cpuPhase = (cpuPhase + 1) & 3;
            if (cpuPhase == 0) {
                captureBoga(oldReloadLevel);
            }

            // The load input is asynchronous. At this adapter boundary an old load
            // remains effective through the edge that releases MOBA.
            if (oldReloadLevel || reloadLevel) {
                tima = tma;
            }
        }

        private void captureBoga(boolean oldReloadLevel) {
            boolean mery = sampledTimaMsb && !msb(tima);
            boolean nextReloadLevel = mery;
            if (!oldReloadLevel && nextReloadLevel) {
                timerIf = true;
            }

            boolean nextSampledTimaMsb = msb(tima);
            reloadLevel = nextReloadLevel;
            // MEXU is asserted by either the old load interval or the newly captured
            // MOBA level, and MUGY then asynchronously clears NYDU.
            sampledTimaMsb = oldReloadLevel || nextReloadLevel ? false : nextSampledTimaMsb;
        }

        private void writeDiv() {
            boolean oldTimerInput = timerInput(div, tac);
            div = 0;
            if (oldTimerInput && !reloadLevel) {
                incrementTima();
            }
        }

        private void writeTima(int value) {
            if (reloadLevel) {
                return;
            }
            tima = value & 0xff;
            // CPU TIMA writes use the same MEXU load path as reload.
            sampledTimaMsb = false;
        }

        private void writeTma(int value) {
            tma = value & 0xff;
            // The current Java adapter publishes the resulting asynchronous TIMA
            // load at the following timer clock. No CPU read can occur in between.
        }

        private void incrementTima() {
            tima = (tima + 1) & 0xff;
        }

        private boolean overflowPending() {
            return reloadLevel || sampledTimaMsb && !msb(tima);
        }

        private int clocksUntilReload() {
            return !reloadLevel && sampledTimaMsb && !msb(tima) ? 4 - cpuPhase : 0;
        }

        private static boolean msb(int value) {
            return (value & 0x80) != 0;
        }
    }
}
