package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerialPort implements AddressSpace, StatefulComponent<SerialPort> {

    /** Keep the component-side bulk contract bounded like the normal-speed CPU phase. */
    private static final int PERFORMANCE_MAX_QUIET_SPAN = 3;

    private static final Logger LOG = LoggerFactory.getLogger(SerialPort.class);

    private transient SerialEndpoint serialEndpoint = SerialEndpoint.NULL_ENDPOINT;

    private final InterruptManager interruptManager;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private int sb;

    // the CGB clock-speed bit (bit 1) reads 1 at power-on (mooneye boot_hwio-C)
    private int sc = 0x02;

    /** Free-running 8-bit link clock whose phase is reset by writes to DIV. */
    private int serialClocks;

    private boolean serialClockSignal;

    private int receivedBits;

    // IF is visible as soon as the eighth bit lands, while HALT's wake input
    // receives the serial edge one CPU machine cycle later.
    private int haltWakeDelay;

    /** Owner-thread observation only; deliberately absent from portable machine state. */
    private transient DebugHooks debugHooks;

    public SerialPort(InterruptManager interruptManager, boolean gbc, SpeedMode speedMode) {
        this.interruptManager = interruptManager;
        this.gbc = gbc;
        this.speedMode = speedMode;
        // The oscillator is already eight clocks into its phase when a DMG is
        // released from reset; CGB starts at zero. Authentic boot execution is
        // then captured in the integration runner's boot state.
        this.serialClocks = gbc ? 0 : 8;
    }

    public void init(SerialEndpoint serialEndpoint) {
        this.serialEndpoint = serialEndpoint;
    }

    /** True when the raw SC register has armed a transfer using this port's clock. */
    public boolean isInternalClockTransferActive() {
        return (sc & 0x81) == 0x81;
    }

    /** True when the raw SC register has armed a transfer waiting for the peer's clock. */
    public boolean isExternalClockTransferActive() {
        return (sc & 0x81) == 0x80;
    }

    /** True when an active native-CGB transfer has the fast-clock selector bit set. */
    public boolean isFastClockSelectedForActiveTransfer() {
        return (sc & 0x80) != 0 && isColorMode() && (sc & 0x02) != 0;
    }

    /** Allocation-free scalar comparison for the owning Gameboy's link timing check. */
    public boolean hasSameTimingState(SerialPort other) {
        return other != null
                && sb == other.sb
                && sc == other.sc
                && serialClocks == other.serialClocks
                && serialClockSignal == other.serialClockSignal
                && receivedBits == other.receivedBits
                && haltWakeDelay == other.haltWakeDelay;
    }

    public void tick() {
        // Link-port peripherals such as GPS receivers have their own wall clock and keep
        // driving the input pin even when no hardware serial transfer is armed. A built-in
        // endpoint which advertises the matching quiet span has explicitly guaranteed that its
        // callbacks and input pin are inert; use that same
        // capability on scalar event ticks instead of identity-checking only NULL_ENDPOINT.
        SerialEndpoint endpoint = serialEndpoint;
        boolean quietEndpoint = canAdvancePerformanceQuietTransfer(1);
        if (!quietEndpoint && endpoint != SerialEndpoint.NULL_ENDPOINT) {
            endpoint.tick();
        } else if (quietEndpoint) {
            endpoint.tickPerformanceQuietSpanTrusted(1);
        }
        acknowledgeInterruptIfNeeded();
        int speed = speedMode.getSpeedMode();
        if (quietEndpoint && haltWakeDelay == 0) {
            serialClocks = (serialClocks + speed) & 0xff;
            return;
        }
        for (int i = 0; i < speed; i++) {
            tickCpuClock();
        }
    }

    /**
     * Returns the largest exact PERFORMANCE span for an idle link port.
     *
     * <p>A quiet endpoint can advance its wall clock without changing the input level. A stopped
     * transfer, or an external-clock transfer whose endpoint guarantees that no input bit arrives,
     * has no serial edge to deliver. In those states the port advances only the endpoint's exact
     * arithmetic countdown and the free-running 8-bit phase. Other endpoints, internal-clock
     * transfers, HALT wake delay, and debug hooks remain scalar so endpoint callbacks and trace
     * ordering cannot be observed late.</p>
     */
    public int performanceQuietSpanLimit(int requested) {
        if (requested <= 0
                || speedMode.getSpeedMode() != 1
                || !canAdvancePerformanceQuietTransfer(requested)
                || haltWakeDelay != 0
                || debugHooks != null) {
            return 0;
        }
        return Math.min(requested, PERFORMANCE_MAX_QUIET_SPAN);
    }

    /** Same idle-link horizon for a settled HALT packet, without the normal three-dot cap. */
    public int performanceSettledHaltSpanLimit(int requested) {
        if (requested <= 0
                || speedMode.getSpeedMode() != 1
                || !canAdvancePerformanceQuietTransfer(requested)
                || haltWakeDelay != 0
                || debugHooks != null) {
            return 0;
        }
        return requested;
    }

    /** Returns the largest safe span using the scheduler's normal three-clock bound. */
    public int performanceQuietSpanLimit() {
        return performanceQuietSpanLimit(PERFORMANCE_MAX_QUIET_SPAN);
    }

    /** True when the requested span can be applied by {@link #tickPerformanceQuietSpan(int)}. */
    public boolean canTickPerformanceQuietSpan(int ticks) {
        return ticks > 0 && performanceQuietSpanLimit(ticks) >= ticks;
    }

    /**
     * Advances a quiet-endpoint serial port without entering its per-clock callback path.
     * A pending acknowledge is consumed at the same beginning-of-tick boundary as scalar
     * execution; no serial edge can land in an eligible span, so this cannot create a hidden
     * shift or completion event.
     *
     * @return false without mutation when the state is not exactly bulk-safe
     */
    public boolean tickPerformanceQuietSpan(int ticks) {
        if (!canTickPerformanceQuietSpan(ticks)) {
            return false;
        }
        serialEndpoint.tickPerformanceQuietSpanTrusted(ticks);
        acknowledgeInterruptIfNeeded();
        serialClocks = (serialClocks + ticks) & 0xff;
        return true;
    }

    /** Applies a span after the caller has already passed {@link #canTickPerformanceQuietSpan(int)}. */
    public void tickPerformanceQuietSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        // The packet preflight has already established a quiet endpoint and no pending
        // acknowledge/transfer edge. Do not repeat that walk on the hot commit path.
        serialEndpoint.tickPerformanceQuietSpanTrusted(ticks);
        acknowledgeInterruptIfNeeded();
        serialClocks = (serialClocks + ticks) & 0xff;
    }

    /** Native-CGB double-speed epoch guard; edge-producing transfers remain scalar. */
    public boolean performanceEpochIdle(int requested) {
        return requested > 0
                && speedMode.getSpeedMode() == 2
                && canAdvancePerformanceQuietTransfer(requested)
                && haltWakeDelay == 0
                && debugHooks == null;
    }

    /** Applies the preflighted quiet serial phase without a per-dot loop. */
    public void tickPerformanceEpochIdle(int ticks) {
        if (ticks <= 0) {
            return;
        }
        serialEndpoint.tickPerformanceQuietSpanTrusted(ticks);
        acknowledgeInterruptIfNeeded();
        serialClocks = (serialClocks + ticks * 2) & 0xff;
    }


    /** Physical-DMG normal-speed epoch guard; edge-producing transfers remain scalar. */
    public boolean performancePhysicalDmgEpochIdle(int requested) {
        return performanceNormalSpeedEpochIdle(requested, false);
    }

    /** Normal-speed epoch guard shared by physical DMG and CGB hardware. */
    public boolean performanceNormalSpeedEpochIdle(int requested, boolean cgbHardware) {
        boolean topologyMatches = cgbHardware
                ? speedMode.isGbc()
                : !speedMode.isGbc();
        return requested > 0
                && speedMode.getSpeedMode() == 1
                && topologyMatches
                && canAdvancePerformanceQuietTransfer(requested)
                && haltWakeDelay == 0
                && debugHooks == null;
    }

    /** Applies a preflighted physical-DMG quiet serial phase at one clock per master tick. */
    public void tickPerformancePhysicalDmgEpochIdle(int ticks) {
        tickPerformanceNormalSpeedEpochIdle(ticks);
    }

    /** Applies a preflighted fixed-x1 normal-speed serial phase. */
    public void tickPerformanceNormalSpeedEpochIdle(int ticks) {
        if (ticks <= 0) {
            return;
        }
        serialEndpoint.tickPerformanceQuietSpanTrusted(ticks);
        acknowledgeInterruptIfNeeded();
        serialClocks = (serialClocks + ticks) & 0xff;
    }

    /** True when scalar CPU clocks cannot shift a bit or expose an endpoint callback. */
    private boolean canAdvancePerformanceQuietTransfer(int requested) {
        if (requested <= 0) {
            return false;
        }
        if ((sc & 0x80) == 0) {
            return serialEndpoint.canTickPerformanceQuietSpan(requested);
        }
        if ((sc & 0x01) != 0) {
            return false;
        }
        return serialEndpoint.canTickPerformanceQuietSpan(requested)
                && serialEndpoint.performanceExternalClockWaitSpanLimit(requested) >= requested;
    }

    /** Naming alias for schedulers which use the GPU's advance-oriented bulk vocabulary. */
    public boolean advancePerformanceQuietSpan(int ticks) {
        return tickPerformanceQuietSpan(ticks);
    }

    /** Trusted naming alias for schedulers which use the GPU's advance-oriented vocabulary. */
    public void advancePerformanceQuietSpanTrusted(int ticks) {
        tickPerformanceQuietSpanTrusted(ticks);
    }

    private void acknowledgeInterruptIfNeeded() {
        if (!interruptManager.consumeSerialInterruptAcknowledge()) {
            return;
        }

        boolean internalTransfer = (sc & 0x81) == 0x81;
        if (internalTransfer) {
            int halfPeriod = getInternalClockHalfPeriod();
            int clocksToNextToggle = halfPeriod - (serialClocks & (halfPeriod - 1));
            int clocksToNextBit = clocksToNextToggle + (serialClockSignal ? 0 : halfPeriod);
            int clocksToCompletion = clocksToNextBit
                    + 2 * halfPeriod * (7 - receivedBits);
            // Coffee GB reaches IRQ_PUSH_2 four clocks before Gambatte's
            // event-based acknowledge point on CGB, so include that dispatch
            // lead in addition to Gambatte's four-clock peripheral window.
            int acknowledgeWindow = gbc ? 8 : 3;
            if (clocksToCompletion <= acknowledgeWindow) {
                shiftBit(serialEndpoint.sendBit());
            }
        }

        // Any completion pulled into the CPU's acknowledge window happened
        // before the acknowledge edge, so that edge wins and leaves IF clear.
        interruptManager.finishSerialInterruptAcknowledge();
    }

    /**
     * Rephases the serial divider from the reset pulse shared with DIV. The
     * output clock latch toggles when the divider stage immediately below the
     * selected serial tap was high. A resulting falling edge shifts one bit.
     */
    public void onDivReset() {
        boolean internalTransfer = (sc & 0x81) == 0x81;
        if (!internalTransfer) {
            serialClocks = 0;
            serialClockSignal = false;
            return;
        }

        int halfPeriod = getInternalClockHalfPeriod();
        boolean precedingStageHigh = (serialClocks & (halfPeriod >> 1)) != 0;
        serialClocks = 0;
        if (precedingStageHigh) {
            serialClockSignal = !serialClockSignal;
            if (!serialClockSignal) {
                shiftBit(serialEndpoint.sendBit());
            }
        }
    }

    private void tickCpuClock() {
        if (haltWakeDelay > 0 && --haltWakeDelay == 0) {
            interruptManager.releaseHaltWake(InterruptManager.InterruptType.Serial);
        }
        boolean transferInProgress = (sc & (1 << 7)) != 0;
        if ((sc & 1) == 0) {
            serialEndpoint.setExternalTransfer(transferInProgress);
            int incomingBit = serialEndpoint.recvBit();
            if (incomingBit != -1) {
                shiftBit(incomingBit);
            }
        } else if (transferInProgress) {
            int flipClocks = getInternalClockHalfPeriod();
            int oldPhase = serialClocks & (flipClocks - 1);
            if (oldPhase == flipClocks - 1) {
                serialClockSignal = !serialClockSignal;
                if (!serialClockSignal) {
                    shiftBit(serialEndpoint.sendBit());
                }
            }
        }
        serialClocks = (serialClocks + 1) & 0xff;
    }

    private void shiftBit(int incomingBit) {
        sb = (sb << 1) & 0xff | (incomingBit & 1);
        receivedBits++;
        boolean completed = receivedBits == 8;
        if (completed) {
            haltWakeDelay = 4;
            sc = sc & 0b01111111; // stop transfer
            receivedBits = 0;
        }
        notifyDebugEvent(SerialIrTrace.Kind.BIT_SHIFTED, sb);
        if (completed) {
            // The final byte and transfer state are visible before the serial IRQ request.
            // This also makes a final-byte breakpoint ready at the completed-tick safe point.
            notifyDebugEvent(SerialIrTrace.Kind.BYTE_TRANSFERRED, sb);
            interruptManager.requestInterruptBeforeHaltWake(InterruptManager.InterruptType.Serial);
            LOG.atDebug().log("[{}] Received sb = {}", this.hashCode(), Integer.toBinaryString(sb));
        }
    }

    private boolean isColorMode() {
        return gbc && !speedMode.isDmgCompat();
    }

    private int getInternalClockHalfPeriod() {
        return isColorMode() && (sc & (1 << 1)) != 0 ? 8 : 256;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff01 || address == 0xff02;
    }

    @Override
    public void setByte(int address, int value) {
        if (address == 0xff01) {
            sb = value;
            serialEndpoint.setSb(sb);
            LOG.atDebug().log("[{}] Set SB = {}", this.hashCode(), Integer.toBinaryString(sb));
        } else if (address == 0xff02) {
            boolean startsTransfer = (value & (1 << 7)) != 0;
            if (startsTransfer) {
                receivedBits = 0;
                serialClockSignal = false;
                if (isColorMode() && (sc & 0x80) != 0 && ((sc ^ value) & 0x02) != 0) {
                    int oldClockMask = (sc & 0x02) != 0 ? 1 << 2 : 1 << 7;
                    int newClockMask = (value & 0x02) != 0 ? 1 << 2 : 1 << 7;
                    if ((serialClocks & oldClockMask) != 0 && (serialClocks & newClockMask) == 0) {
                        serialClockSignal = true;
                    }
                }
                serialEndpoint.startSending();
                LOG.atDebug().log("[{}] Start transfer", this.hashCode());
            } else {
                receivedBits = 0;
            }
            sc = value;
            if (startsTransfer) {
                // A start observes the outgoing byte after SC and all transfer state settle.
                notifyDebugEvent(SerialIrTrace.Kind.TRANSFER_STARTED, sb);
            }
            LOG.atDebug().log("[{}] Set SC = {}", this.hashCode(), Integer.toBinaryString(sc));
        }
    }

    /** Installs an optional owner-thread observer without emitting an alignment event. */
    public void setDebugHooks(DebugHooks debugHooks) {
        this.debugHooks = debugHooks;
    }

    private void notifyDebugEvent(SerialIrTrace.Kind kind, int value) {
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onSerialIrEvent(SerialIrTrace.Endpoint.SERIAL, kind, value);
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff01) {
            LOG.atDebug().log("[{}] Get SB = {}", this.hashCode(), Integer.toBinaryString(sb));
            return sb;
        } else if (address == 0xff02) {
            int effectiveSc = sc | (isColorMode() ? 0b01111100 : 0b01111110);
            LOG.atDebug().log("[{}] Get SC = {}", this.hashCode(), Integer.toBinaryString(effectiveSc));
            return effectiveSc;
        } else {
            throw new IllegalArgumentException();
        }
    }

    /** Captures link-register and clock progress without logging or touching the endpoint. */
    public DebugHardwareInspection.Serial captureDebugSerialInspection() {
        int effectiveSc = sc | (isColorMode() ? 0b01111100 : 0b01111110);
        return new DebugHardwareInspection.Serial(
                sb, effectiveSc, receivedBits, serialClocks, serialClockSignal, haltWakeDelay);
    }

    @Override
    public ComponentState<SerialPort> captureState() {
        return new SerialPortState(sb, sc, serialClocks, serialClockSignal, receivedBits, haltWakeDelay);
    }

    @Override
    public void restoreState(ComponentState<SerialPort> state) {
        LOG.atDebug().log("[{}] Restore component state", this.hashCode());
        if (!(state instanceof SerialPortState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.sb = mem.sb;
        this.sc = mem.sc;
        this.serialClocks = mem.serialClocks;
        this.serialClockSignal = mem.serialClockSignal;
        this.receivedBits = mem.receivedBits;
        this.haltWakeDelay = mem.haltWakeDelay;
    }

    private record SerialPortState(int sb, int sc, int serialClocks, boolean serialClockSignal,
                                     int receivedBits, int haltWakeDelay) implements ComponentState<SerialPort> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record SerialPortMemento(int sb, int sc, int serialClocks, boolean serialClockSignal,
                                     int receivedBits, int haltWakeDelay) implements Memento<SerialPort> {
    }
}
