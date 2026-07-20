package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class InterruptManager implements AddressSpace, Serializable, Originator<InterruptManager> {

    private static final int PPU_INTERRUPT_MASK =
            (1 << InterruptType.VBlank.ordinal()) | (1 << InterruptType.LCDC.ordinal());

    public enum InterruptType {
        VBlank(0x0040),
        LCDC(0x0048),
        Timer(0x0050),
        Serial(0x0058),
        P10_13(0x0060);

        private final int handler;

        InterruptType(int handler) {
            this.handler = handler;
        }

        public int getHandler() {
            return handler;
        }

        public static final InterruptType[] VALUES = InterruptType.values();
    }

    private boolean ime;

    private int interruptFlag = 0xe1;

    private int interruptEnabled;

    // Some peripheral edges set IF shortly before the edge can wake HALT.
    private int haltBlockedInterrupts;

    // Some PPU edges become visible in IF before the CPU interrupt input sees them.
    private int cpuBlockedInterrupts;

    // PPU requests that have already crossed the CPU synchronizer. These
    // remain tagged after their temporary input block is released so the CPU does not
    // apply the direct-edge timing adjustment a second time.
    private int cpuPhasedPpuInterrupts = interruptFlag & PPU_INTERRUPT_MASK;

    private int cpuPhasedMode2Interrupts;

    private int cpuFirstLineMode2Interrupts;

    private int pendingEnableInterrupts = -1;

    // At the first visible-line latch the DMG's retiring VBlank request and a
    // simultaneous STAT write can overlap the IF read gate. The stored IF bit stays
    // asserted, but that one bus read sees VBlank low.
    private boolean maskVBlankOnNextRead;

    // The CPU acknowledges an interrupt near the middle of its final dispatch
    // machine cycle. Serial events in the remaining part of that cycle are
    // evaluated before the acknowledge clears IF.
    private boolean serialInterruptAcknowledge;

    // TIMA events in the peripheral look-ahead portion of the CPU acknowledge
    // cycle are evaluated before the acknowledge clears IF.
    private boolean timerInterruptAcknowledge;

    private boolean lcdcInterruptAcknowledge;

    public InterruptManager(boolean gbc) {
    }

    public void enableInterrupts(boolean withDelay) {
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
        ime = false;
    }

    public void requestInterrupt(InterruptType type) {
        int mask = 1 << type.ordinal();
        boolean newlyAsserted = (interruptFlag & mask) == 0;
        interruptFlag |= mask;
        if (newlyAsserted) {
            cpuPhasedPpuInterrupts &= ~mask;
        }
        if (type == InterruptType.VBlank) {
            maskVBlankOnNextRead = false;
        }
        haltBlockedInterrupts &= ~mask;
        cpuBlockedInterrupts &= ~mask;
    }

    public void requestInterruptBeforeHaltWake(InterruptType type) {
        int mask = 1 << type.ordinal();
        if ((interruptFlag & mask) == 0) {
            haltBlockedInterrupts |= mask;
            cpuPhasedPpuInterrupts &= ~mask;
        }
        interruptFlag |= mask;
    }

    /**
     * Makes an interrupt visible to running code immediately while holding it away from
     * HALT's wake input, without applying the direct-edge PPU timing adjustment. This is
     * used for PPU edges that have already crossed the running CPU synchronizer but reach
     * the HALT wake path one machine cycle later.
     */
    public void requestPhasedInterruptBeforeHaltWake(InterruptType type) {
        int mask = 1 << type.ordinal();
        if ((interruptFlag & mask) == 0) {
            haltBlockedInterrupts |= mask;
            cpuPhasedPpuInterrupts |= mask & PPU_INTERRUPT_MASK;
        }
        interruptFlag |= mask;
    }

    public void requestInterruptBeforeCpuAcceptance(InterruptType type) {
        int mask = 1 << type.ordinal();
        if ((interruptFlag & mask) == 0) {
            cpuBlockedInterrupts |= mask;
            haltBlockedInterrupts |= mask;
            cpuPhasedPpuInterrupts |= mask & PPU_INTERRUPT_MASK;
        }
        interruptFlag |= mask;
    }

    public void requestMode2InterruptBeforeCpuAcceptance(boolean firstLine) {
        int mask = 1 << InterruptType.LCDC.ordinal();
        boolean newlyAsserted = (interruptFlag & mask) == 0;
        requestInterruptBeforeCpuAcceptance(InterruptType.LCDC);
        if (newlyAsserted) {
            cpuPhasedMode2Interrupts |= mask;
            if (firstLine) {
                cpuFirstLineMode2Interrupts |= mask;
            }
        }
    }

    /** Withdraws a mode-2 request that has not yet crossed the CPU synchronizer. */
    public void cancelMode2InterruptBeforeCpuAcceptance() {
        int mask = 1 << InterruptType.LCDC.ordinal();
        if ((cpuPhasedMode2Interrupts & cpuBlockedInterrupts & mask) != 0) {
            clearInterruptState(InterruptType.LCDC);
        }
    }

    /**
     * Exposes an interrupt in IF before either CPU input may accept it, while
     * preserving the direct PPU-edge classification used after the block is released.
     */
    public void requestInterruptBeforeCpuAcceptanceUnphased(InterruptType type) {
        int mask = 1 << type.ordinal();
        if ((interruptFlag & mask) == 0) {
            cpuBlockedInterrupts |= mask;
            haltBlockedInterrupts |= mask;
            cpuPhasedPpuInterrupts &= ~mask;
        }
        interruptFlag |= mask;
        if (type == InterruptType.VBlank) {
            maskVBlankOnNextRead = false;
        }
    }

    public void releaseCpuAcceptance(InterruptType type) {
        int mask = ~(1 << type.ordinal());
        cpuBlockedInterrupts &= mask;
        haltBlockedInterrupts &= mask;
    }

    public void releaseHaltWake(InterruptType type) {
        haltBlockedInterrupts &= ~(1 << type.ordinal());
    }

    public void clearInterrupt(InterruptType type) {
        if (type == InterruptType.Serial) {
            serialInterruptAcknowledge = true;
        } else if (type == InterruptType.Timer) {
            timerInterruptAcknowledge = true;
        } else if (type == InterruptType.LCDC) {
            lcdcInterruptAcknowledge = true;
        }
        clearInterruptState(type);
    }

    private void clearInterruptState(InterruptType type) {
        interruptFlag = interruptFlag & ~(1 << type.ordinal());
        if (type == InterruptType.VBlank) {
            maskVBlankOnNextRead = false;
        }
        haltBlockedInterrupts &= ~(1 << type.ordinal());
        cpuBlockedInterrupts &= ~(1 << type.ordinal());
        cpuPhasedPpuInterrupts &= ~(1 << type.ordinal());
        cpuPhasedMode2Interrupts &= ~(1 << type.ordinal());
        cpuFirstLineMode2Interrupts &= ~(1 << type.ordinal());
    }

    public boolean consumeSerialInterruptAcknowledge() {
        boolean result = serialInterruptAcknowledge;
        serialInterruptAcknowledge = false;
        return result;
    }

    public void finishSerialInterruptAcknowledge() {
        clearInterruptState(InterruptType.Serial);
    }

    public boolean consumeTimerInterruptAcknowledge() {
        boolean result = timerInterruptAcknowledge;
        timerInterruptAcknowledge = false;
        return result;
    }

    public boolean consumeLcdcInterruptAcknowledge() {
        boolean result = lcdcInterruptAcknowledge;
        lcdcInterruptAcknowledge = false;
        return result;
    }

    public void finishTimerInterruptAcknowledge() {
        clearInterruptState(InterruptType.Timer);
    }

    public void onInstructionFinished() {
        if (pendingEnableInterrupts != -1) {
            if (pendingEnableInterrupts-- == 0) {
                enableInterrupts(false);
            }
        }
    }

    public boolean isIme() {
        return ime;
    }

    public boolean isInterruptRequested() {
        return (interruptFlag & interruptEnabled & ~cpuBlockedInterrupts & 0x1f) != 0;
    }

    public boolean isInterruptRequestedForHalt() {
        return (interruptFlag & interruptEnabled & ~cpuBlockedInterrupts
                & ~haltBlockedInterrupts & 0x1f) != 0;
    }

    public boolean isUnphasedPpuInterruptRequested() {
        return (interruptFlag & interruptEnabled & ~cpuBlockedInterrupts
                & ~cpuPhasedPpuInterrupts & PPU_INTERRUPT_MASK) != 0;
    }

    public boolean isPhasedMode2InterruptRequested() {
        return (interruptFlag & interruptEnabled & ~cpuBlockedInterrupts
                & cpuPhasedMode2Interrupts) != 0;
    }

    public boolean isFirstLineMode2InterruptRequested() {
        return (interruptFlag & interruptEnabled & ~cpuBlockedInterrupts
                & cpuFirstLineMode2Interrupts) != 0;
    }

    public boolean isHaltBug() {
        return isInterruptRequestedForHalt() && !ime;
    }

    public boolean isInterruptFlagSet(InterruptType type) {
        return (interruptFlag & (1 << type.ordinal())) != 0;
    }

    public void maskVBlankOnNextRead() {
        maskVBlankOnNextRead = true;
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
                haltBlockedInterrupts = 0;
                cpuBlockedInterrupts = 0;
                cpuPhasedPpuInterrupts = interruptFlag & PPU_INTERRUPT_MASK;
                cpuPhasedMode2Interrupts = 0;
                cpuFirstLineMode2Interrupts = 0;
                maskVBlankOnNextRead = false;
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
                if (maskVBlankOnNextRead) {
                    maskVBlankOnNextRead = false;
                    return interruptFlag & ~(1 << InterruptType.VBlank.ordinal());
                }
                return interruptFlag;

            case 0xffff:
                return interruptEnabled;

            default:
                return 0xff;
        }
    }

    @Override
    public Memento<InterruptManager> saveToMemento() {
        return new InterruptManagerMemento(ime, interruptFlag, interruptEnabled, pendingEnableInterrupts,
                haltBlockedInterrupts, cpuBlockedInterrupts, cpuPhasedPpuInterrupts,
                cpuPhasedMode2Interrupts, cpuFirstLineMode2Interrupts,
                maskVBlankOnNextRead, serialInterruptAcknowledge,
                timerInterruptAcknowledge, lcdcInterruptAcknowledge);
    }

    @Override
    public void restoreFromMemento(Memento<InterruptManager> memento) {
        if (!(memento instanceof InterruptManagerMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.ime = mem.ime;
        this.interruptFlag = mem.interruptFlag;
        this.interruptEnabled = mem.interruptEnabled;
        this.pendingEnableInterrupts = mem.pendingEnableInterrupts;
        this.haltBlockedInterrupts = mem.haltBlockedInterrupts;
        this.cpuBlockedInterrupts = mem.cpuBlockedInterrupts;
        this.cpuPhasedPpuInterrupts = mem.cpuPhasedPpuInterrupts;
        this.cpuPhasedMode2Interrupts = mem.cpuPhasedMode2Interrupts;
        this.cpuFirstLineMode2Interrupts = mem.cpuFirstLineMode2Interrupts;
        this.maskVBlankOnNextRead = mem.maskVBlankOnNextRead;
        this.serialInterruptAcknowledge = mem.serialInterruptAcknowledge;
        this.timerInterruptAcknowledge = mem.timerInterruptAcknowledge;
        this.lcdcInterruptAcknowledge = mem.lcdcInterruptAcknowledge;
    }

    private record InterruptManagerMemento(boolean ime, int interruptFlag, int interruptEnabled,
                                           int pendingEnableInterrupts,
                                           int haltBlockedInterrupts,
                                           int cpuBlockedInterrupts,
                                           int cpuPhasedPpuInterrupts,
                                           int cpuPhasedMode2Interrupts,
                                           int cpuFirstLineMode2Interrupts,
                                           boolean maskVBlankOnNextRead,
                                           boolean serialInterruptAcknowledge,
                                           boolean timerInterruptAcknowledge,
                                           boolean lcdcInterruptAcknowledge) implements Memento<InterruptManager> {
    }

}
