package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.timer.Timer;
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

    private final Timer timer;

    private int sb;

    // the CGB clock-speed bit (bit 1) reads 1 at power-on (mooneye boot_hwio-C)
    private int sc = 0x02;

    private boolean prevClockBit;

    private int receivedBits;

    public SerialPort(InterruptManager interruptManager, Timer timer, boolean gbc, SpeedMode speedMode) {
        this.interruptManager = interruptManager;
        this.timer = timer;
        this.gbc = gbc;
        this.speedMode = speedMode;
    }

    public void init(SerialEndpoint serialEndpoint) {
        this.serialEndpoint = serialEndpoint;
    }

    public void tick() {
        boolean transferInProgress = (sc & (1 << 7)) != 0;
        int incomingBit = -1;
        // We're receiving bits even without the transfer in progress.
        if (ClockType.getFromSc(sc) == ClockType.EXTERNAL) {
            incomingBit = serialEndpoint.recvBit();
        } else {
            // the serial clock is derived from the DIV counter, so the first bit of
            // a transfer is aligned to the free-running divider; the tap leads the
            // counter by 4 cycles (boot_sclk_align)
            boolean clockBit = ((timer.getDivCounter() + 4) & (1 << getClockBitPos())) != 0;
            if (transferInProgress && prevClockBit && !clockBit) {
                incomingBit = serialEndpoint.sendBit();
            }
            prevClockBit = clockBit;
        }

        if (incomingBit != -1) {
            sb = (sb << 1) & 0xff | (incomingBit & 1);
            receivedBits++;
            if (receivedBits == 8) {
                interruptManager.requestInterrupt(InterruptManager.InterruptType.Serial);
                sc = sc & 0b01111111; // stop transfer
                LOG.atDebug().log("[{}] Received sb = {}", this.hashCode(), Integer.toBinaryString(sb));
            }
        }
    }

    private int getClockBitPos() {
        // 8192 Hz = falling edge of bit 8; CGB fast mode 262144 Hz = bit 3
        return (gbc && (sc & (1 << 1)) != 0) ? 3 : 8;
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
            if ((sc & (1 << 7)) == 0 && (value & (1 << 7)) != 0) {
                receivedBits = 0;
                serialEndpoint.startSending();
                LOG.atDebug().log("[{}] Start transfer", this.hashCode());
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
            int effectiveSc = sc | (gbc ? 0b01111100 : 0b01111110);
            LOG.atDebug().log("[{}] Get SC = {}", this.hashCode(), Integer.toBinaryString(effectiveSc));
            return effectiveSc;
        } else {
            throw new IllegalArgumentException();
        }
    }

    @Override
    public Memento<SerialPort> saveToMemento() {
        return new SerialPortMemento(sb, sc, prevClockBit, receivedBits);
    }

    @Override
    public void restoreFromMemento(Memento<SerialPort> memento) {
        LOG.atDebug().log("[{}] Restore from memento", this.hashCode());
        if (!(memento instanceof SerialPortMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.sb = mem.sb;
        this.sc = mem.sc;
        this.prevClockBit = mem.prevClockBit;
        this.receivedBits = mem.receivedBits;
    }

    private record SerialPortMemento(int sb, int sc, boolean prevClockBit, int receivedBits) implements Memento<SerialPort> {
    }
}
