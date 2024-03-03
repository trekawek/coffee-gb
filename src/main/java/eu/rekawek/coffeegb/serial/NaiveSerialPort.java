package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.InterruptManager;

import java.io.Serializable;

/**
 * Simplified SerialPort implementation in which the bytes are immediately send to the other side, without any bit-by-bit
 * handling or the timing.
 */
public class NaiveSerialPort implements AddressSpace, Serializable {

    private transient SerialEndpoint serialEndpoint;

    private final InterruptManager interruptManager;

    private int sb;

    private int sc;

    private boolean transferInProgress;

    private ClockType clockType;

    public NaiveSerialPort(InterruptManager interruptManager) {
        this.interruptManager = interruptManager;
    }

    public void init(SerialEndpoint serialEndpoint) {
        this.serialEndpoint = serialEndpoint;
    }

    public void tick() {
        int incomingByte = -1;
        // We're receiving bits even without the transfer in progress.
        if (clockType == ClockType.EXTERNAL) {
            incomingByte = serialEndpoint.recvByte();
        } else if (transferInProgress) {
            incomingByte = serialEndpoint.sendByte();
            transferInProgress = false;
        }

        if (incomingByte != -1) {
            this.sb = incomingByte;
            interruptManager.requestInterrupt(InterruptManager.InterruptType.Serial);
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
        clockType = ClockType.getFromSc(sc);
        serialEndpoint.startSending();
    }
}
