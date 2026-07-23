package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

public class SerialPort implements AddressSpace, Serializable, Originator<SerialPort> {

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

    public SerialPort(InterruptManager interruptManager, boolean gbc, SpeedMode speedMode) {
        this.interruptManager = interruptManager;
        this.gbc = gbc;
        this.speedMode = speedMode;
        // The oscillator is already eight clocks into its phase when a DMG is
        // released from reset; CGB starts at zero. Authentic boot execution is
        // then captured in the integration runner's boot memento.
        this.serialClocks = gbc ? 0 : 8;
    }

    public void init(SerialEndpoint serialEndpoint) {
        this.serialEndpoint = serialEndpoint;
    }

    public void tick() {
        // Link-port peripherals such as GPS receivers have their own wall clock and keep
        // driving the input pin even when no hardware serial transfer is armed.
        serialEndpoint.tick();
        acknowledgeInterruptIfNeeded();
        for (int i = 0; i < speedMode.getSpeedMode(); i++) {
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
     * Rephases the internal serial clock after a write to DIV. If a transfer is
     * already running, hardware rounds its next falling edge around the reset
     * point. Depending on the old phase, that edge is either immediate or one
     * or two half-periods after the reset.
     */
    public void onDivReset() {
        boolean internalTransfer = (sc & 0x81) == 0x81;
        if (!internalTransfer) {
            serialClocks = 0;
            serialClockSignal = false;
            return;
        }

        int halfPeriod = getInternalClockHalfPeriod();
        int clocksToNextToggle = halfPeriod - (serialClocks & (halfPeriod - 1));
        int clocksToNextBit = clocksToNextToggle + (serialClockSignal ? 0 : halfPeriod);

        // Gambatte's event-time formulation, applied to the next bit rather
        // than the transfer-completion event (all later bits are a whole
        // period apart, so they receive the same adjustment).
        int phaseAdjustment = Math.floorMod(-clocksToNextBit, halfPeriod);
        int adjustedClocksToNextBit = clocksToNextBit + phaseAdjustment
                - 2 * (phaseAdjustment & (halfPeriod >> 1));

        serialClocks = 0;
        if (adjustedClocksToNextBit == 0) {
            // The reset itself supplies the falling edge.
            serialClockSignal = false;
            shiftBit(serialEndpoint.sendBit());
        } else {
            serialClockSignal = adjustedClocksToNextBit == halfPeriod;
        }
    }

    private void tickCpuClock() {
        if (haltWakeDelay > 0 && --haltWakeDelay == 0) {
            interruptManager.releaseHaltWake(InterruptManager.InterruptType.Serial);
        }
        boolean transferInProgress = (sc & (1 << 7)) != 0;
        if (ClockType.getFromSc(sc) == ClockType.EXTERNAL) {
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
        if (receivedBits == 8) {
            interruptManager.requestInterruptBeforeHaltWake(InterruptManager.InterruptType.Serial);
            haltWakeDelay = 4;
            sc = sc & 0b01111111; // stop transfer
            receivedBits = 0;
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
            if ((value & (1 << 7)) != 0) {
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
            LOG.atDebug().log("[{}] Set SC = {}", this.hashCode(), Integer.toBinaryString(sc));
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

    @Override
    public Memento<SerialPort> saveToMemento() {
        return new SerialPortMemento(sb, sc, serialClocks, serialClockSignal, receivedBits, haltWakeDelay);
    }

    @Override
    public void restoreFromMemento(Memento<SerialPort> memento) {
        LOG.atDebug().log("[{}] Restore from memento", this.hashCode());
        if (!(memento instanceof SerialPortMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.sb = mem.sb;
        this.sc = mem.sc;
        this.serialClocks = mem.serialClocks;
        this.serialClockSignal = mem.serialClockSignal;
        this.receivedBits = mem.receivedBits;
        this.haltWakeDelay = mem.haltWakeDelay;
    }

    private record SerialPortMemento(int sb, int sc, int serialClocks, boolean serialClockSignal,
                                     int receivedBits, int haltWakeDelay) implements Memento<SerialPort> {
    }
}
