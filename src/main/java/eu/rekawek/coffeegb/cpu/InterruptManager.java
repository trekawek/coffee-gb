package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;

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

    private final boolean gbc;

    private boolean ime;

    private int interruptFlag = 0xe1;

    private int interruptEnabled;

    private int pendingEnableInterrupts = -1;

    private int pendingDisableInterrupts = -1;

    public InterruptManager(boolean gbc) {
        this.gbc = gbc;
    }

    public void enableInterrupts(boolean withDelay) {
        pendingDisableInterrupts = -1;
        if (withDelay) {
            if (pendingEnableInterrupts == -1) {
                pendingEnableInterrupts = 1;
            }
        } else {
            pendingEnableInterrupts = -1;
            ime = true;
        }
    }

    public void disableInterrupts(boolean withDelay) {
        pendingEnableInterrupts = -1;
        if (withDelay && gbc) {
            if (pendingDisableInterrupts == -1) {
                pendingDisableInterrupts = 1;
            }
        } else {
            pendingDisableInterrupts = -1;
            ime = false;
        }
    }

    public void requestInterrupt(InterruptType type) {
        interruptFlag = interruptFlag | (1 << type.ordinal());
    }

    public void clearInterrupt(InterruptType type) {
        interruptFlag = interruptFlag & ~(1 << type.ordinal());
    }

    public void onInstructionFinished() {
        if (pendingEnableInterrupts != -1) {
            if (pendingEnableInterrupts-- == 0) {
                enableInterrupts(false);
            }
        }
        if (pendingDisableInterrupts != -1) {
            if (pendingDisableInterrupts-- == 0) {
                disableInterrupts(false);
            }
        }
    }

    public boolean isIme() {
        return ime;
    }

    public boolean isInterruptRequested() {
        return (interruptFlag & interruptEnabled) != 0;
    }

    public boolean isHaltBug() {
        return (interruptFlag & interruptEnabled & 0x1f) != 0 && !ime;
    }

    @Override
    public boolean accepts(int address) {
        return address == 0xff0f || address == 0xffff;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case 0xff0f:
                interruptFlag = value | 0xe0;
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
