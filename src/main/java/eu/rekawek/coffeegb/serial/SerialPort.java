package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.InterruptManager;
import eu.rekawek.coffeegb.cpu.SpeedMode;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;

import java.io.Serializable;

public class SerialPort implements AddressSpace, Serializable, Originator<SerialPort> {

    private transient SerialEndpoint serialEndpoint;

    private final InterruptManager interruptManager;

    private final boolean gbc;

    private final SpeedMode speedMode;

    private int sb;

    private int sc;

    private boolean transferInProgress;

    private int divider;

    private ClockType clockType;

    private int speed;

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
        int incomingBit = -1;
        // We're receiving bits even without the transfer in progress.
        if (clockType == ClockType.EXTERNAL) {
            incomingBit = serialEndpoint.recvBit();
        } else if (transferInProgress) {
            if (divider++ == Gameboy.TICKS_PER_SEC / speed) {
                divider = 0;
                incomingBit = serialEndpoint.sendBit();
            }
        }

        if (incomingBit != -1) {
            sb = (sb << 1) & 0xff | (incomingBit & 1);
            receivedBits++;
            if (receivedBits == 8) {
                interruptManager.requestInterrupt(InterruptManager.InterruptType.Serial);
                transferInProgress = false;
            }
        }
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
        } else if (address == 0xff02) {
            sc = value;
            if ((sc & (1 << 7)) != 0) {
                startTransfer();
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address == 0xff01) {
            return sb;
        } else if (address == 0xff02) {
            return sc | 0b01111110;
        } else {
            throw new IllegalArgumentException();
        }
    }

    private void startTransfer() {
        transferInProgress = true;
        divider = 0;
        clockType = ClockType.getFromSc(sc);
        receivedBits = 0;
        if (gbc && (sc & (1 << 1)) != 0) {
            speed = 262144;
        } else {
            speed = 8192;
        }
        speed *= speedMode.getSpeedMode();
        serialEndpoint.startSending();
    }

    @Override
    public Memento<SerialPort> saveToMemento() {
        return new SerialPortMemento(sb, sc, transferInProgress, divider, clockType, speed, receivedBits);
    }

    @Override
    public void restoreFromMemento(Memento<SerialPort> memento) {
        if (!(memento instanceof SerialPortMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.sb = mem.sb;
        this.sc = mem.sc;
        this.transferInProgress = mem.transferInProgress;
        this.divider = mem.divider;
        this.clockType = mem.clockType;
        this.speed = mem.speed;
        this.receivedBits = mem.receivedBits;
    }

    private record SerialPortMemento(int sb, int sc, boolean transferInProgress, int divider, ClockType clockType,
                                     int speed, int receivedBits) implements Memento<SerialPort> {
    }
}
