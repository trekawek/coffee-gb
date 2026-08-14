package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.debug.DebugHardwareInspection;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.SerialIrTrace;
import eu.rekawek.coffeegb.core.signal.EdgeDetector;
import eu.rekawek.coffeegb.core.signal.UnsignedRippleCounter;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SerialPort implements AddressSpace, StatefulComponent<SerialPort> {

    private static final Logger LOG = LoggerFactory.getLogger(SerialPort.class);

    private transient SerialEndpoint serialEndpoint = SerialEndpoint.NULL_ENDPOINT;

    private final InterruptManager interruptManager;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private int sb;

    // the CGB clock-speed bit (bit 1) reads 1 at power-on (mooneye boot_hwio-C)
    private int sc = 0x02;

    /** Free-running link divider whose phase is reset by writes to DIV. */
    private final UnsignedRippleCounter serialDivider;

    private final EdgeDetector serialClock = new EdgeDetector(false);

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
        this.serialDivider = new UnsignedRippleCounter(8, gbc ? 0 : 8);
    }

    public void init(SerialEndpoint serialEndpoint) {
        this.serialEndpoint = serialEndpoint;
    }

    public void tick() {
        // Link-port peripherals such as GPS receivers have their own wall clock and keep
        // driving the input pin even when no hardware serial transfer is armed.
        if (serialEndpoint != SerialEndpoint.NULL_ENDPOINT) {
            serialEndpoint.tick();
        }
        acknowledgeInterruptIfNeeded();
        int speed = speedMode.getSpeedMode();
        if (serialEndpoint == SerialEndpoint.NULL_ENDPOINT
                && (sc & 0x80) == 0
                && haltWakeDelay == 0) {
            serialDivider.advanceUnobserved(speed);
            return;
        }
        for (int i = 0; i < speed; i++) {
            tickCpuClock();
        }
    }

    private void acknowledgeInterruptIfNeeded() {
        if (!interruptManager.consumeSerialInterruptAcknowledge()) {
            return;
        }

        boolean internalTransfer = (sc & 0x81) == 0x81;
        if (internalTransfer) {
            int halfPeriod = getInternalClockHalfPeriod();
            int clocksToNextToggle = halfPeriod
                    - ((int) serialDivider.value() & (halfPeriod - 1));
            int clocksToNextBit = clocksToNextToggle
                    + (serialClock.previousLevel() ? 0 : halfPeriod);
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
            serialDivider.restore(0);
            serialClock.restore(false);
            return;
        }

        int halfPeriod = getInternalClockHalfPeriod();
        serialDivider.resolve(false, true);
        boolean togglesClock = serialDivider.fell(
                Integer.numberOfTrailingZeros(halfPeriod) - 1);
        serialDivider.commit();
        if (togglesClock) {
            toggleSerialClock();
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
            int halfPeriod = getInternalClockHalfPeriod();
            serialDivider.resolve(true, false);
            if (serialDivider.fell(Integer.numberOfTrailingZeros(halfPeriod) - 1)) {
                toggleSerialClock();
            }
            serialDivider.commit();
            return;
        }
        advanceSerialDivider();
    }

    private void advanceSerialDivider() {
        serialDivider.resolve(true, false);
        serialDivider.commit();
    }

    private void toggleSerialClock() {
        serialClock.resolve(!serialClock.previousLevel());
        boolean falling = serialClock.falling();
        serialClock.commit();
        if (falling) {
            shiftBit(serialEndpoint.sendBit());
        }
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
                serialClock.restore(false);
                if (isColorMode() && (sc & 0x80) != 0 && ((sc ^ value) & 0x02) != 0) {
                    int oldClockMask = (sc & 0x02) != 0 ? 1 << 2 : 1 << 7;
                    int newClockMask = (value & 0x02) != 0 ? 1 << 2 : 1 << 7;
                    int divider = (int) serialDivider.value();
                    if ((divider & oldClockMask) != 0 && (divider & newClockMask) == 0) {
                        serialClock.restore(true);
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
                sb, effectiveSc, receivedBits, (int) serialDivider.value(),
                serialClock.previousLevel(), haltWakeDelay);
    }

    @Override
    public ComponentState<SerialPort> captureState() {
        return new SerialPortState(
                sb, sc, (int) serialDivider.value(), serialClock.previousLevel(), receivedBits,
                haltWakeDelay);
    }

    @Override
    public void restoreState(ComponentState<SerialPort> state) {
        LOG.atDebug().log("[{}] Restore component state", this.hashCode());
        if (!(state instanceof SerialPortState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.sb = mem.sb;
        this.sc = mem.sc;
        this.serialDivider.restore(mem.serialClocks);
        this.serialClock.restore(mem.serialClockSignal);
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
