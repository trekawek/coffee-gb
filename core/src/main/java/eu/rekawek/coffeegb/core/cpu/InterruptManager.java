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

    private int pendingEnableInterrupts = -1;

    // At the first visible-line latch the DMG's retiring VBlank request and a
    // simultaneous STAT write can overlap the IF read gate. The stored IF bit stays
    // asserted, but that one bus read sees VBlank low.
    private boolean maskVBlankOnNextRead;

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
        interruptFlag = interruptFlag & ~(1 << type.ordinal());
        if (type == InterruptType.VBlank) {
            maskVBlankOnNextRead = false;
        }
        haltBlockedInterrupts &= ~(1 << type.ordinal());
        cpuBlockedInterrupts &= ~(1 << type.ordinal());
        cpuPhasedPpuInterrupts &= ~(1 << type.ordinal());
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
                maskVBlankOnNextRead);
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
        this.maskVBlankOnNextRead = mem.maskVBlankOnNextRead;
    }

    private record InterruptManagerMemento(boolean ime, int interruptFlag, int interruptEnabled,
                                           int pendingEnableInterrupts,
                                           int haltBlockedInterrupts,
                                           int cpuBlockedInterrupts,
                                           int cpuPhasedPpuInterrupts,
                                           boolean maskVBlankOnNextRead) implements Memento<InterruptManager> {
    }

}
