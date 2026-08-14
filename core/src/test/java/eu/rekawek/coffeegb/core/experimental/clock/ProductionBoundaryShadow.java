package eu.rekawek.coffeegb.core.experimental.clock;

import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection;
import eu.rekawek.coffeegb.core.serial.SerialPort;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.util.EnumSet;

/**
 * Passive adapter between today's completed-callback core and a four-phase signal scheduler.
 *
 * <p>The adapter is test-only on purpose. It reads existing side-effect-free debugger views and
 * turns them into an immutable old-state vector. Local timer and serial request wires can then be
 * derived without executing either component or asking it for a deadline. A {@link Cycle} accepts
 * commutative drives, freezes them, captures the IF latch's next value, and finally commits it.
 * No drive can observe another drive from the same boundary.</p>
 *
 * <p>This is a migration seam rather than a second emulator. It models only dependencies already
 * observable at a production callback boundary. {@link #migrationBlockers()} lists the signals
 * that today's public debug boundary cannot expose and that therefore need real production
 * latches before the scheduler can replace {@code Gameboy.tick()}.</p>
 */
final class ProductionBoundaryShadow {

    static final int TIMER_MASK = 1 << InterruptManager.InterruptType.Timer.ordinal();

    static final int SERIAL_MASK = 1 << InterruptManager.InterruptType.Serial.ordinal();

    enum Phase {
        DRIVE,
        RESOLVE,
        CAPTURE,
        COMMIT
    }

    enum MissingSignal {
        /** T1..T4, address, RD, WR, and data are not persistent production CPU state. */
        CPU_BUS_T_STATE,
        /** IRQ_PUSH_2 clears IF before the physically constrained acknowledge edge. */
        INTERRUPT_ACKNOWLEDGE_HALF_DOT,
        /** Cpu.requestedIrq is private and is also replaced by a live-priority late path. */
        INTERRUPT_SELECTED_SOURCE_LATCH,
        /** The independent BOGA phase and NYDU/MOBA state are projected to an overflow count. */
        TIMER_BOGA_AND_OVERFLOW_LATCHES,
        /** External serial input is obtained by calling an endpoint, not sampling a pin wire. */
        SERIAL_EXTERNAL_INPUT_PIN,
        /** One CGB master callback collapses two CPU clocks and does not identify their subedge. */
        DOUBLE_SPEED_CPU_SUBEDGE
    }

    record ClockSample(boolean cgb, boolean dmgCompatibility, boolean doubleSpeed) {

        static ClockSample capture(SpeedMode speedMode) {
            return new ClockSample(
                    speedMode.isGbc(),
                    speedMode.isDmgCompat(),
                    speedMode.getSpeedMode() == 2);
        }

        int cpuClocksPerMasterTick() {
            return doubleSpeed ? 2 : 1;
        }

        boolean colorMode() {
            return cgb && !dmgCompatibility;
        }

        int interruptAcknowledgeDelayClocks() {
            return cgb ? 8 : 3;
        }
    }

    record CpuSample(Cpu.State state, int callbackPhase) {

        static CpuSample capture(Cpu cpu) {
            return new CpuSample(cpu.getState(), cpu.getDebugMachineCycle());
        }

        /**
         * Whether the next production CPU callback executes IRQ_PUSH_2's combined stack write,
         * source selection, and early IF clear. This uses only persistent CPU state; it does not
         * decode the next opcode or inspect a peripheral.
         */
        boolean productionAcknowledgeStartsOnNextTick(ClockSample clock) {
            int terminalPhase = clock.doubleSpeed() ? 1 : 3;
            return state == Cpu.State.IRQ_PUSH_2 && callbackPhase == terminalPhase;
        }
    }

    record TimerSample(
            int divider,
            int tima,
            int tma,
            int tac,
            boolean overflowPending,
            int clocksUntilReload) {

        static TimerSample capture(Timer timer) {
            return new TimerSample(
                    timer.getDivCounter(),
                    timer.getDebugTima(),
                    timer.getDebugTma(),
                    timer.getDebugTac(),
                    timer.isDebugOverflowPending(),
                    timer.getDebugOverflowDelayTicks());
        }

        /** Raw IF.2 set wire during the next production master tick. */
        int naturalRequestOnNextMasterTick(ClockSample clock) {
            // At most two CPU clocks occur in one master tick. A newly overflowing TIMA needs
            // three further clocks before INT_TIMER, so only an already-pending reload can assert.
            return overflowPending
                    && clocksUntilReload > 0
                    && clocksUntilReload <= clock.cpuClocksPerMasterTick()
                    ? TIMER_MASK : 0;
        }
    }

    record SerialSample(
            int sb,
            int sc,
            int receivedBits,
            int dividerLow,
            boolean serialClock,
            int haltWakeDelay) {

        static SerialSample capture(SerialPort serial) {
            DebugHardwareInspection.Serial state = serial.captureDebugSerialInspection();
            return new SerialSample(
                    state.sb(), state.sc(), state.receivedBits(), state.clockPhase(),
                    state.clockSignal(), state.haltWakeDelay());
        }

        /**
         * Raw IF.3 set wire during the next production master tick for the internal link clock.
         * The calculation is a local ripple transition from the captured old state, not a future
         * completion query. External-clock transfers deliberately remain a migration blocker.
         */
        int naturalRequestOnNextMasterTick(ClockSample clock) {
            int nextSc = sc;
            int nextBits = receivedBits;
            int nextDivider = dividerLow;
            boolean nextClock = serialClock;
            for (int i = 0; i < clock.cpuClocksPerMasterTick(); i++) {
                boolean internalTransfer = (nextSc & 0x81) == 0x81;
                if (internalTransfer) {
                    int halfPeriod = clock.colorMode() && (nextSc & 0x02) != 0 ? 8 : 256;
                    if ((nextDivider & (halfPeriod - 1)) == halfPeriod - 1) {
                        boolean oldClock = nextClock;
                        nextClock = !nextClock;
                        if (oldClock && !nextClock) {
                            nextBits++;
                            if (nextBits == 8) {
                                return SERIAL_MASK;
                            }
                        }
                    }
                }
                nextDivider = (nextDivider + 1) & 0xff;
            }
            return 0;
        }
    }

    record InterruptSample(int flags, int enable, boolean ime) {

        static InterruptSample capture(InterruptManager interrupts) {
            return new InterruptSample(
                    interrupts.getDebugInterruptFlags(),
                    interrupts.getDebugInterruptEnableFlags(),
                    interrupts.isIme());
        }
    }

    record Snapshot(
            ClockSample clock,
            CpuSample cpu,
            TimerSample timer,
            SerialSample serial,
            InterruptSample interrupts) {

        static Snapshot capture(
                Cpu cpu,
                Timer timer,
                SerialPort serial,
                InterruptManager interrupts,
                SpeedMode speedMode) {
            return new Snapshot(
                    ClockSample.capture(speedMode),
                    CpuSample.capture(cpu),
                    TimerSample.capture(timer),
                    SerialSample.capture(serial),
                    InterruptSample.capture(interrupts));
        }
    }

    interface Driver {

        void drive(Snapshot oldState, WireCollector wires);
    }

    record ResolvedWires(int requestSet, int acknowledgeClear) {
    }

    record Capture(Snapshot oldState, ResolvedWires wires, int nextInterruptFlags) {
    }

    static Driver timerRequestDriver() {
        return (oldState, wires) ->
                wires.driveRequest(oldState.timer().naturalRequestOnNextMasterTick(
                        oldState.clock()));
    }

    static Driver serialRequestDriver() {
        return (oldState, wires) ->
                wires.driveRequest(oldState.serial().naturalRequestOnNextMasterTick(
                        oldState.clock()));
    }

    static Driver requestDriver(int mask) {
        return (oldState, wires) -> wires.driveRequest(mask);
    }

    static Driver acknowledgeDriver(int mask) {
        return (oldState, wires) -> wires.driveAcknowledge(mask);
    }

    static final class Cycle {

        private final Snapshot oldState;

        private final WireCollector wires = new WireCollector();

        private Phase phase = Phase.DRIVE;

        private ResolvedWires resolved;

        private Capture captured;

        private int committedInterruptFlags;

        Cycle(Snapshot oldState) {
            if (oldState == null) {
                throw new NullPointerException("oldState");
            }
            this.oldState = oldState;
        }

        void drive(Driver driver) {
            require(Phase.DRIVE, "drive");
            driver.drive(oldState, wires);
        }

        ResolvedWires resolve() {
            require(Phase.DRIVE, "resolve");
            resolved = new ResolvedWires(wires.requestSet, wires.acknowledgeClear);
            phase = Phase.RESOLVE;
            return resolved;
        }

        Capture capture() {
            require(Phase.RESOLVE, "capture");
            int nextFlags = (oldState.interrupts().flags() | resolved.requestSet())
                    & ~resolved.acknowledgeClear() & 0x1f;
            captured = new Capture(oldState, resolved, nextFlags);
            phase = Phase.CAPTURE;
            return captured;
        }

        int commit() {
            require(Phase.CAPTURE, "commit");
            committedInterruptFlags = captured.nextInterruptFlags();
            phase = Phase.COMMIT;
            return committedInterruptFlags;
        }

        Phase phase() {
            return phase;
        }

        private void require(Phase expected, String operation) {
            if (phase != expected) {
                throw new IllegalStateException(
                        operation + " called in " + phase + " instead of " + expected);
            }
        }
    }

    /** Captures the physically placed CPU acknowledge strobe as a local delay line. */
    static final class CpuAcknowledgeDelay {

        private int selectedMask;

        private int clocksRemaining;

        void armAfterProductionClearBoundary(Snapshot beforeCpuCallback, int selectedMask) {
            if (!beforeCpuCallback.cpu().productionAcknowledgeStartsOnNextTick(
                    beforeCpuCallback.clock())) {
                throw new IllegalArgumentException("snapshot is not an IRQ_PUSH_2 boundary");
            }
            if ((selectedMask & ~0x1f) != 0 || Integer.bitCount(selectedMask) != 1) {
                throw new IllegalArgumentException("selected interrupt must be one-hot");
            }
            this.selectedMask = selectedMask;
            clocksRemaining = beforeCpuCallback.clock().interruptAcknowledgeDelayClocks();
        }

        int stepCpuClock() {
            if (clocksRemaining == 0) {
                return 0;
            }
            clocksRemaining--;
            if (clocksRemaining == 0) {
                int result = selectedMask;
                selectedMask = 0;
                return result;
            }
            return 0;
        }

        boolean pending() {
            return clocksRemaining != 0;
        }
    }

    static EnumSet<MissingSignal> migrationBlockers() {
        return EnumSet.allOf(MissingSignal.class);
    }

    private static final class WireCollector {

        private int requestSet;

        private int acknowledgeClear;

        private void driveRequest(int mask) {
            requestSet |= mask & 0x1f;
        }

        private void driveAcknowledge(int mask) {
            acknowledgeClear |= mask & 0x1f;
        }
    }
}
