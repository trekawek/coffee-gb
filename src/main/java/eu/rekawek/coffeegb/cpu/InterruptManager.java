package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.op.Op;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.cpu.opcode.OpcodeBuilder;

import java.util.Arrays;
import java.util.List;

public class InterruptManager implements AddressSpace {

    public enum InterruptType {
        VBlank(0x0040), LCDC(0x0048), Timer(0x0050), Serial(0x0058), P10_13(0x0060);

        private final int handler;

        InterruptType(int handler) {
            this.handler = handler;
        }

        public int getHandler() {
            return handler;
        }
    }

    private boolean ime;

    private int interruptFlag;

    private int interruptEnabled;

    private boolean interruptRequested;

    private int pendingEnableInterrupts;

    private int pendingDisableInterrupts;

    public void enableInterrupts(boolean withDelay) {
        if (withDelay) {
            pendingEnableInterrupts = 1;
            pendingDisableInterrupts = -1;
        } else {
            ime = true;
        }
    }

    public void disableInterrupts(boolean withDelay) {
        if (withDelay) {
            pendingEnableInterrupts = -1;
            pendingDisableInterrupts = 1;
        } else {
            ime = false;
        }
    }

    public void requestInterrupt(InterruptType type) {
        if (!ime) {
            return;
        }
        interruptFlag = interruptFlag | (1 << type.ordinal());
        interruptRequested = true;
    }

    public void onInstructionFinished() {
        if (pendingEnableInterrupts != -1) {
            if (pendingEnableInterrupts-- == 0) {
                ime = true;
            }
        }
        if (pendingDisableInterrupts != -1) {
            if (pendingDisableInterrupts-- == 0) {
                ime = false;
            }
        }
    }

    public boolean isInterruptRequested() {
        return interruptRequested;
    }

    public void flush() {
        interruptFlag = 0;
        interruptRequested = false;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff0f:
                interruptFlag = value;
                break;

            case 0xffff:
                interruptEnabled = value;
                break;
        }
    }

    @Override
    public int getByte(int address) {
        switch (address) {
            case 0xff0f:
                return interruptFlag;

            case 0xffff:
                return  interruptEnabled;

            default:
                return 0xff;
        }
    }
}
