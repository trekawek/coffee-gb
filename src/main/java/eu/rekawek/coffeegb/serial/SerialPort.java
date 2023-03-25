package eu.rekawek.coffeegb.serial;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.cpu.InterruptManager;

public class SerialPort implements AddressSpace {

    private final SerialEndpoint serialEndpoint;

    private final InterruptManager interruptManager;

    private final boolean gbc;

    private int sb;

    private int sc;

    private boolean transferInProgress;

    private int divider;

    private ClockType clockType;

    private int speed;

    private int receivedBits;

    public SerialPort(InterruptManager interruptManager, SerialEndpoint serialEndpoint, boolean gbc) {
        this.interruptManager = interruptManager;
        this.serialEndpoint = serialEndpoint;
        this.gbc = gbc;
    }

    public void tick() {
        if (!transferInProgress) {
            return;
        }

        int bitToTransfer = (sb & (1 << 7)) != 0 ? 1 : 0;
        int incomingBit = -1;
        if (clockType == ClockType.EXTERNAL) {
            incomingBit = serialEndpoint.receive(bitToTransfer);
        } else {
            if (divider++ == Gameboy.TICKS_PER_SEC / speed) {
                divider = 0;
                incomingBit = serialEndpoint.send(bitToTransfer);
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
        if (gbc) {
            if ((sc & (1 << 1)) == 0) {
                speed = 8192;
            } else {
                speed = 262144;
            }
        } else {
            speed = 8192;
        }
    }
}
