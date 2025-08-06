package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

public class SerialPort implements AddressSpace, Serializable, Originator<SerialPort> {

    private static final Logger LOG = LoggerFactory.getLogger(SerialPort.class);

    private transient SerialEndpoint serialEndpoint = SerialEndpoint.NULL_ENDPOINT;

    private final InterruptManager interruptManager;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private int sb = 0;

    private int sc = 0b01111110;

    private int divider;

    private int receivedBits;

    public SerialPort(InterruptManager interruptManager, boolean gbc, SpeedMode speedMode) {
        this.interruptManager = interruptManager;
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
        } else if (transferInProgress) {
            if (divider++ == Gameboy.TICKS_PER_SEC / getSpeed()) {
                divider = 0;
                incomingBit = serialEndpoint.sendBit();
            }
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

    private int getSpeed() {
        int speed;
        if (gbc && (sc & (1 << 1)) != 0) {
            speed = 262144;
        } else {
            speed = 8192;
        }
        speed *= speedMode.getSpeedMode();
        return speed;
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
                divider = 0;
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
        return new SerialPortMemento(sb, sc, divider, receivedBits);
    }

    @Override
    public void restoreFromMemento(Memento<SerialPort> memento) {
        LOG.atDebug().log("[{}] Restore from memento", this.hashCode());
        if (!(memento instanceof SerialPortMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.sb = mem.sb;
        this.sc = mem.sc;
        this.divider = mem.divider;
        this.receivedBits = mem.receivedBits;
    }

    private record SerialPortMemento(int sb, int sc, int divider, int receivedBits) implements Memento<SerialPort> {
    }
}
