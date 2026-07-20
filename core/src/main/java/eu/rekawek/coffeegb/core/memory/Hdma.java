package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;

public class Hdma implements AddressSpace, Serializable, Originator<Hdma> {

    private enum HaltHdmaState {
        LOW,
        HIGH,
        REQUESTED
    }

    private enum WakeRequestArbitration {
        NONE,
        REVERSE_PENDING,
        PREEMPT_CPU,
        YIELD_CPU
    }

    private static final int HDMA1 = 0xff51;

    private static final int HDMA2 = 0xff52;

    private static final int HDMA3 = 0xff53;

    private static final int HDMA4 = 0xff54;

    private static final int HDMA5 = 0xff55;

    // VRAM DMA's request hand-off is distinct from the 32-tick data burst. HBlank
    // arbitration takes four scheduler ticks at normal speed and three at double
    // speed; general-purpose DMA has its own startup timing.
    private static final int NORMAL_SPEED_GDMA_STARTUP_TICKS = 6;

    private static final int NORMAL_SPEED_HBLANK_STARTUP_TICKS = 4;

    private static final int DOUBLE_SPEED_GDMA_STARTUP_TICKS = 2;

    private static final int DOUBLE_SPEED_HBLANK_STARTUP_TICKS = 3;

    private final AddressSpace addressSpace;

    private final SpeedMode speedMode;

    private Mode gpuMode;

    private boolean transferInProgress;

    private boolean hblankTransfer;

    private boolean lcdEnabled;

    private int length;

    private int src;

    private int dst;

    private int tick;

    // VRAM remains atomically visible to the rest of Coffee GB at block completion,
    // but the source bus is sampled in sixteen individual two-tick slots. Preserve
    // those samples so a save state in the middle of a burst resumes deterministically.
    private final int[] blockData = new int[0x10];

    /**
     * The pixel-transfer skeleton releases its internal buses four dots before the
     * HBlank DMA request reaches the CPU arbiter. Keeping that hand-off separate from
     * {@link #tick} is important: the CPU may finish one last machine cycle while the
     * request crosses the PPU/CPU boundary, but the 32-dot transfer burst itself still
     * has the normal startup timing below.
     */
    private int hblankRequestTicks = -1;

    private int hblankRequestAge;

    private int nextHblankRequestTicks = -1;

    private int nextHblankRequestAge;

    private int sourceBytesTransferred;

    private int cpuBusValue = 0xff;

    private boolean stopAfterCurrentBlock;

    private boolean preserveLengthAfterCurrentBlock;

    private boolean speedSwitchInProgress;

    private boolean speedSwitchStartedWithoutRequest;

    private boolean pauseOamDmaForSpeedSwitchBurst;

    private WakeRequestArbitration wakeRequestArbitration = WakeRequestArbitration.NONE;

    private int gpuLine;

    private int gpuTicksInLine;

    private int hblankStartTicksInLine;

    private boolean cpuHalted;

    private HaltHdmaState haltHdmaState = HaltHdmaState.LOW;

    private boolean haltEnteredThisTick;

    private boolean requestOverlappedCpuWrite;

    private boolean interruptEntryWonArbitration;

    private boolean haltOpcodeRequestLatched;

    // A source-bus sample is consumed by OAM DMA later in the same scheduler tick.
    // It is deliberately transient and never survives a Gameboy tick boundary.
    private transient SourceBusSample sourceBusSample;

    public Hdma(AddressSpace addressSpace) {
        this(addressSpace, new SpeedMode(false));
    }

    public Hdma(AddressSpace addressSpace, SpeedMode speedMode) {
        this.addressSpace = addressSpace;
        this.speedMode = speedMode;
    }

    @Override
    public boolean accepts(int address) {
        return address >= HDMA1 && address <= HDMA5;
    }

    /**
     * @return {@code true} when this tick completed a 16-byte transfer block
     */
    public boolean tick() {
        sourceBusSample = null;
        if (!isTransferInProgress()) {
            return false;
        }
        if (++tick < 0) {
            return false;
        }
        if (tick < 0x20) {
            if ((tick & 1) != 0) {
                int j = tick >> 1;
                int sourceAddress = (src + j) & 0xffff;
                int value = readSourceByte(sourceAddress);
                sourceBusSample = new SourceBusSample(sourceAddress, value);
                blockData[j] = value;
                sourceBytesTransferred++;
            }
            return false;
        }
        // Keep the existing atomic destination commit. Only the source side needs to
        // be visible per byte for HDMA/OAM-DMA bus sharing.
        for (int j = 0; j < 0x10; j++) {
            addressSpace.setByte(0x8000 | ((dst + j) & 0x1fff), blockData[j]);
        }
        pauseOamDmaForSpeedSwitchBurst = false;
        src = (src + 0x10) & 0xffff;
        dst = (dst + 0x10) & 0xffff;
        tick = hblankTransfer ? -startupTicks() : 0;
        // The destination counter keeps all eight high bits internally. Writes wrap
        // through the 8 KiB VRAM aperture, but reaching 10000 terminates the request
        // rather than wrapping the raw counter (dma_dst_wrap_1/2).
        if (stopAfterCurrentBlock || dst == 0 || length-- == 0) {
            transferInProgress = false;
            length = preserveLengthAfterCurrentBlock ? length : 0x7f;
            stopAfterCurrentBlock = false;
            preserveLengthAfterCurrentBlock = false;
            requestOverlappedCpuWrite = false;
            interruptEntryWonArbitration = false;
            wakeRequestArbitration = WakeRequestArbitration.NONE;
            hblankRequestAge = 0;
            nextHblankRequestTicks = -1;
            nextHblankRequestAge = 0;
        } else if (hblankTransfer) {
            // A late burst may still be running when the next scanline reaches
            // HBlank. Preserve that independently synchronized request so the next
            // block is not postponed by an entire line.
            boolean overlappingRequest = nextHblankRequestTicks == 0;
            hblankRequestTicks = overlappingRequest && nextHblankRequestAge == 0 ? 0 : -1;
            hblankRequestAge = 0;
            nextHblankRequestTicks = -1;
            nextHblankRequestAge = 0;
            requestOverlappedCpuWrite = false;
            interruptEntryWonArbitration = false;
            wakeRequestArbitration = WakeRequestArbitration.NONE;
        }
        return true;
    }

    @Override
    public void setByte(int address, int value) {
        switch (address) {
            case HDMA1:
                src = (value << 8) | (src & 0xff);
                sourceBytesTransferred = 0;
                break;
            case HDMA2:
                src = (src & 0xff00) | (value & 0xf0);
                sourceBytesTransferred = 0;
                break;
            case HDMA3:
                dst = (value << 8) | (dst & 0xff);
                break;
            case HDMA4:
                dst = (dst & 0xff00) | (value & 0xf0);
                break;
            case HDMA5:
                if (transferInProgress && hblankTransfer) {
                    length = value & 0x7f;
                    if ((value & (1 << 7)) == 0) {
                        stopTransfer(value);
                    }
                } else {
                    startTransfer(value);
                }
                break;
        }
    }

    private int readSourceByte(int address) {
        if (address >= 0x8000 && address < 0xa000) {
            // This source range is not connected to CGB VRAM DMA. Hardware
            // exposes the instruction-bus residue for its first two byte slots,
            // then the undriven bus settles high.
            return sourceBytesTransferred < 2 ? cpuBusValue : 0xff;
        }
        return addressSpace.getByte(DmaAddressSpace.mapAddress(address, true));
    }

    public void setCpuBusValue(int cpuBusValue) {
        this.cpuBusValue = cpuBusValue & 0xff;
    }

    public SourceBusSample consumeSourceBusSample() {
        SourceBusSample sample = sourceBusSample;
        sourceBusSample = null;
        return sample;
    }

    private int startupTicks() {
        if (hblankTransfer && lcdEnabled) {
            return speedMode.getSpeedMode() == 2
                    ? DOUBLE_SPEED_HBLANK_STARTUP_TICKS
                    : NORMAL_SPEED_HBLANK_STARTUP_TICKS;
        }
        return speedMode.getSpeedMode() == 2
                ? DOUBLE_SPEED_GDMA_STARTUP_TICKS
                : NORMAL_SPEED_GDMA_STARTUP_TICKS;
    }

    @Override
    public int getByte(int address) {
        if (address == HDMA5) {
            return (transferInProgress ? 0 : (1 << 7)) | length;
        }
        return 0xff;
    }

    public void onGpuUpdate(Mode newGpuMode) {
        this.gpuMode = newGpuMode;
        if (newGpuMode == Mode.HBlank) {
            // Gameboy reports the mode edge before publishing the PPU timing for
            // this scheduler tick, so the first HBlank dot is the following value.
            hblankStartTicksInLine = gpuTicksInLine + 1;
        }
        if (speedSwitchInProgress) {
            return;
        }
        if (newGpuMode == Mode.HBlank && cpuHalted && haltEnteredThisTick
                && transferInProgress && hblankTransfer) {
            // If HALT completes on the very dot that raises the mode-0 request, the
            // arbiter observes an already-flagged request before acknowledging it.
            haltHdmaState = HaltHdmaState.REQUESTED;
            haltOpcodeRequestLatched = true;
        } else if (newGpuMode == Mode.HBlank && transferInProgress && hblankTransfer
                && !cpuHalted && hblankRequestTicks == 0 && length > 0
                && !stopAfterCurrentBlock) {
            nextHblankRequestTicks = 3;
            nextHblankRequestAge = 0;
        } else if (newGpuMode == Mode.HBlank && transferInProgress && hblankTransfer
                && !cpuHalted && hblankRequestTicks != 0) {
            hblankRequestTicks = wakeRequestArbitration == WakeRequestArbitration.REVERSE_PENDING
                    ? Math.max(1, 251 - gpuTicksInLine)
                    : 3;
            hblankRequestAge = 0;
            requestOverlappedCpuWrite = false;
            interruptEntryWonArbitration = false;
        } else if (newGpuMode != Mode.HBlank && hblankTransfer
                && hblankRequestTicks > 0) {
            hblankRequestTicks = -1;
            hblankRequestAge = 0;
            interruptEntryWonArbitration = false;
        }
        if (newGpuMode != Mode.HBlank && nextHblankRequestTicks > 0) {
            nextHblankRequestTicks = -1;
            nextHblankRequestAge = 0;
        }
    }

    public void onGpuTiming(int line, int ticksInLine) {
        this.gpuLine = line;
        this.gpuTicksInLine = ticksInLine;
        haltEnteredThisTick = false;
    }

    /** Advances the PPU-to-CPU HBlank request synchronizer by one master tick. */
    public void advanceHblankRequest() {
        advanceHblankRequest(false);
    }

    public void advanceHblankRequest(boolean cpuWriteCycleInFlight) {
        advanceHblankRequest(cpuWriteCycleInFlight, false);
    }

    public void advanceHblankRequest(boolean cpuWriteCycleInFlight,
                                     boolean cpuInstructionInFlight) {
        advanceHblankRequest(cpuWriteCycleInFlight, cpuInstructionInFlight, false);
    }

    public void advanceHblankRequest(boolean cpuWriteCycleInFlight,
                                     boolean cpuInstructionInFlight,
                                     boolean cpuInterruptEntryInFlight) {
        if (!cpuHalted && hblankRequestTicks > 0) {
            if (--hblankRequestTicks == 0) {
                requestOverlappedCpuWrite = cpuWriteCycleInFlight;
                interruptEntryWonArbitration = cpuInterruptEntryInFlight;
                hblankRequestAge = 0;
                if (wakeRequestArbitration == WakeRequestArbitration.REVERSE_PENDING) {
                    // The CPU/HDMA arbiter resumes on the opposite half-cycle after
                    // STOP. An instruction already past its opcode boundary is held;
                    // otherwise the opcode fetch about to start wins this grant.
                    wakeRequestArbitration = cpuInstructionInFlight
                            ? WakeRequestArbitration.PREEMPT_CPU
                            : WakeRequestArbitration.YIELD_CPU;
                }
            }
        } else if (!cpuHalted && hblankRequestTicks == 0) {
            int ticksSinceHblankStart = gpuTicksInLine - hblankStartTicksInLine;
            if (hblankRequestAge == 0 && cpuInterruptEntryInFlight
                    && gpuMode == Mode.HBlank
                    && ticksSinceHblankStart >= 0 && ticksSinceHblankStart <= 2) {
                // HALT restores an unsynchronized request directly on wake. If the
                // wake/interrupt input arrives within the request's two-tick HBlank
                // arbitration window, interrupt entry owns the slot first.
                interruptEntryWonArbitration = true;
            }
            hblankRequestAge++;
        }
        if (nextHblankRequestTicks > 0) {
            nextHblankRequestTicks--;
        } else if (nextHblankRequestTicks == 0) {
            nextHblankRequestAge++;
        }
    }

    /**
     * HALT acknowledges the asynchronous HDMA request and remembers whether the
     * request line was low, high, or already latched. On wake, a low-to-high request
     * may be asserted again; a request that was already latched is always restored.
     * Merely waking during a later HBlank after entering HALT with the line high does
     * not create a second request.
     */
    public void onCpuHaltState(boolean halted) {
        if (halted == cpuHalted) {
            return;
        }
        if (halted) {
            haltEnteredThisTick = true;
            boolean requestWasAlreadyActive = transferInProgress && hblankTransfer
                    && hblankRequestTicks == 0;
            if (transferInProgress && hblankTransfer
                    && hblankRequestTicks >= 0 && hblankRequestTicks <= 1) {
                haltHdmaState = HaltHdmaState.REQUESTED;
            } else if (transferInProgress && hblankTransfer && isCurrentHblankHaltHigh()) {
                haltHdmaState = HaltHdmaState.HIGH;
            } else {
                haltHdmaState = HaltHdmaState.LOW;
            }
            // A request that already owned the bus before HALT completed is still
            // restored on wake, but it did not coincide with HALT's next-opcode
            // sample. Only a request reaching an already-halted CPU (or crossing on
            // the entry edge in onGpuUpdate) turns that sample into a held opcode.
            haltOpcodeRequestLatched = haltHdmaState == HaltHdmaState.REQUESTED
                    && !requestWasAlreadyActive;
            if (hblankTransfer) {
                hblankRequestTicks = -1;
                hblankRequestAge = 0;
                nextHblankRequestTicks = -1;
                nextHblankRequestAge = 0;
                requestOverlappedCpuWrite = false;
                interruptEntryWonArbitration = false;
            }
        } else if (transferInProgress && hblankTransfer
                && (haltHdmaState == HaltHdmaState.REQUESTED
                || (haltHdmaState == HaltHdmaState.LOW && isCurrentHblankWakeRequestable()))) {
            hblankRequestTicks = 0;
            hblankRequestAge = 0;
            nextHblankRequestTicks = -1;
            nextHblankRequestAge = 0;
            requestOverlappedCpuWrite = false;
            interruptEntryWonArbitration = false;
            wakeRequestArbitration = WakeRequestArbitration.NONE;
        }
        if (!halted) {
            haltOpcodeRequestLatched = false;
        }
        cpuHalted = halted;
    }

    public void onLcdSwitch(boolean lcdEnabled) {
        this.lcdEnabled = lcdEnabled;
        if (!lcdEnabled && transferInProgress && hblankTransfer) {
            // Disabling the LCD releases the current HDMA request as one final
            // burst. With no following HBlanks, the remaining blocks stay
            // paused until the display is enabled again.
            gpuMode = Mode.HBlank;
            hblankRequestTicks = 0;
            hblankRequestAge = 0;
            nextHblankRequestTicks = -1;
            nextHblankRequestAge = 0;
            requestOverlappedCpuWrite = false;
            interruptEntryWonArbitration = false;
            wakeRequestArbitration = WakeRequestArbitration.NONE;
        }
    }

    /**
     * A normal-to-double STOP switch lets an HBlank grant that already reached the
     * CPU finish one data burst, then drops the transfer while retaining FF55's
     * programmed remaining-length bits. A transfer merely armed in mode 2/3 is not
     * affected here; a later HBlank may still request it while the clock is stopped.
     */
    public boolean onSpeedSwitch() {
        speedSwitchInProgress = true;
        speedSwitchStartedWithoutRequest = hblankRequestTicks < 0;
        pauseOamDmaForSpeedSwitchBurst = false;
        wakeRequestArbitration = WakeRequestArbitration.NONE;
        boolean replayStopPaddingByte = transferInProgress && hblankTransfer
                && hblankRequestTicks == 0
                && (speedMode.getSpeedMode() != 2 || hblankRequestAge > 1);
        if (!transferInProgress || !hblankTransfer) {
            return false;
        }
        if (hblankRequestTicks > 0 || (speedMode.getSpeedMode() == 2
                && hblankRequestTicks == 0 && hblankRequestAge <= 1)) {
            // STOP won before the synchronized request was stable. Lose this
            // scanline's grant but leave the programmed HBlank transfer armed.
            hblankRequestTicks = -1;
            hblankRequestAge = 0;
            requestOverlappedCpuWrite = false;
            interruptEntryWonArbitration = false;
        } else if (speedMode.getSpeedMode() == 2 && hblankRequestTicks == 0) {
            stopAfterCurrentBlock = true;
            preserveLengthAfterCurrentBlock = true;
            pauseOamDmaForSpeedSwitchBurst = true;
        }
        return replayStopPaddingByte;
    }

    public void onSpeedSwitchComplete() {
        speedSwitchInProgress = false;
        if (speedSwitchStartedWithoutRequest && transferInProgress && hblankTransfer
                && hblankRequestTicks < 0) {
            if (isCurrentHblankRequestable()) {
                // An early mode-0 wake has already passed the synchronizer by the
                // first resumed CPU edge. A wake near the end of HBlank still has
                // to cross the ordinary three-tick hand-off.
                hblankRequestTicks = gpuTicksInLine <= 446 ? 0 : 3;
                hblankRequestAge = 0;
                requestOverlappedCpuWrite = false;
                interruptEntryWonArbitration = false;
            } else {
                // If STOP releases during mode 3, remember that the next HBlank
                // arrives on the rephased CPU/HDMA arbitration half-cycle.
                wakeRequestArbitration = WakeRequestArbitration.REVERSE_PENDING;
            }
        }
        speedSwitchStartedWithoutRequest = false;
    }

    /** Whether the retained HDMA5 mode latch selects the HBlank STOP tail. */
    public boolean holdsHblankSpeedSwitchTail() {
        return speedSwitchInProgress && hblankTransfer;
    }

    public boolean preemptsCpuInstructionForSpeedSwitchWake() {
        return wakeRequestArbitration == WakeRequestArbitration.PREEMPT_CPU;
    }

    public boolean pausesOamDmaForSpeedSwitchBurst() {
        return pauseOamDmaForSpeedSwitchBurst;
    }

    public boolean yieldsSpeedSwitchWakeRequestToCpu() {
        return wakeRequestArbitration == WakeRequestArbitration.YIELD_CPU;
    }

    public boolean yieldsToInterruptEntry() {
        return interruptEntryWonArbitration;
    }

    public void onSpeedSwitchWakeCpuInstructionFinished() {
        if (wakeRequestArbitration == WakeRequestArbitration.YIELD_CPU) {
            wakeRequestArbitration = WakeRequestArbitration.NONE;
        }
    }

    public boolean isTransferInProgress() {
        if (!transferInProgress) {
            return false;
        } else if (hblankTransfer && hblankRequestTicks == 0) {
            return true;
        } else return !hblankTransfer;
    }

    /** Whether an HBlank transfer is still armed, including between HBlank bursts. */
    public boolean hasPendingHblankTransfer() {
        return transferInProgress && hblankTransfer;
    }

    public boolean yieldsCpuAfterBlock() {
        return hblankTransfer;
    }

    /** Whether HALT acknowledged a request that had already reached the CPU latch. */
    public boolean isHaltRequestLatched() {
        return cpuHalted && haltOpcodeRequestLatched;
    }

    private void startTransfer(int reg) {
        hblankTransfer = (reg & (1 << 7)) != 0;
        length = reg & 0x7f;
        transferInProgress = true;
        stopAfterCurrentBlock = false;
        preserveLengthAfterCurrentBlock = false;
        speedSwitchStartedWithoutRequest = false;
        wakeRequestArbitration = WakeRequestArbitration.NONE;
        requestOverlappedCpuWrite = false;
        interruptEntryWonArbitration = false;
        nextHblankRequestTicks = -1;
        nextHblankRequestAge = 0;
        tick = -startupTicks();
        hblankRequestTicks = hblankTransfer && (!isCurrentHblankRequestable()) ? -1 : 0;
        hblankRequestAge = 0;
        if (hblankTransfer && !lcdEnabled) {
            // With the LCD off, starting HDMA copies one block immediately. There are
            // no subsequent HBlanks, so the transfer then remains paused.
            gpuMode = Mode.HBlank;
            hblankRequestTicks = 0;
            hblankRequestAge = 0;
        }
    }

    private boolean isCurrentHblankRequestable() {
        return !lcdEnabled || (gpuMode == Mode.HBlank
                && gpuLine < 144 && gpuTicksInLine <= 450);
    }

    private boolean isCurrentHblankWakeRequestable() {
        return !lcdEnabled || (gpuMode == Mode.HBlank
                && gpuLine < 144 && gpuTicksInLine <= 452);
    }

    private boolean isCurrentHblankHaltHigh() {
        int cutoff = speedMode.getSpeedMode() == 2 ? 451 : 448;
        return !lcdEnabled || (gpuMode == Mode.HBlank
                && gpuLine < 144 && gpuTicksInLine <= cutoff);
    }

    private void stopTransfer(int reg) {
        length = reg & 0x7f;
        preserveLengthAfterCurrentBlock = false;
        if (hblankRequestTicks == 0 && !requestOverlappedCpuWrite) {
            // A request that has crossed the PPU/CPU boundary cannot be retracted.
            // Disabling HDMA prevents later blocks, while this latched block still
            // observes the newly written length.
            stopAfterCurrentBlock = true;
        } else {
            transferInProgress = false;
            hblankRequestTicks = -1;
            hblankRequestAge = 0;
            nextHblankRequestTicks = -1;
            nextHblankRequestAge = 0;
            requestOverlappedCpuWrite = false;
            interruptEntryWonArbitration = false;
            wakeRequestArbitration = WakeRequestArbitration.NONE;
        }
    }

    @Override
    public Memento<Hdma> saveToMemento() {
        return new HdmaMemento(gpuMode, transferInProgress, hblankTransfer, lcdEnabled, length,
                src, dst, tick, blockData.clone(), hblankRequestTicks, hblankRequestAge, nextHblankRequestTicks,
                nextHblankRequestAge,
                sourceBytesTransferred, cpuBusValue,
                stopAfterCurrentBlock, preserveLengthAfterCurrentBlock, speedSwitchInProgress,
                speedSwitchStartedWithoutRequest, pauseOamDmaForSpeedSwitchBurst,
                wakeRequestArbitration,
                gpuLine, gpuTicksInLine, hblankStartTicksInLine, cpuHalted, haltHdmaState,
                haltEnteredThisTick, requestOverlappedCpuWrite,
                interruptEntryWonArbitration, haltOpcodeRequestLatched);
    }

    @Override
    public void restoreFromMemento(Memento<Hdma> memento) {
        if (!(memento instanceof HdmaMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.gpuMode = mem.gpuMode;
        this.transferInProgress = mem.transferInProgress;
        this.hblankTransfer = mem.hblankTransfer;
        this.lcdEnabled = mem.lcdEnabled;
        this.length = mem.length;
        this.src = mem.src;
        this.dst = mem.dst;
        this.tick = mem.tick;
        System.arraycopy(mem.blockData, 0, this.blockData, 0, this.blockData.length);
        this.sourceBusSample = null;
        this.hblankRequestTicks = mem.hblankRequestTicks;
        this.hblankRequestAge = mem.hblankRequestAge;
        this.nextHblankRequestTicks = mem.nextHblankRequestTicks;
        this.nextHblankRequestAge = mem.nextHblankRequestAge;
        this.sourceBytesTransferred = mem.sourceBytesTransferred;
        this.cpuBusValue = mem.cpuBusValue;
        this.stopAfterCurrentBlock = mem.stopAfterCurrentBlock;
        this.preserveLengthAfterCurrentBlock = mem.preserveLengthAfterCurrentBlock;
        this.speedSwitchInProgress = mem.speedSwitchInProgress;
        this.speedSwitchStartedWithoutRequest = mem.speedSwitchStartedWithoutRequest;
        this.pauseOamDmaForSpeedSwitchBurst = mem.pauseOamDmaForSpeedSwitchBurst;
        this.wakeRequestArbitration = mem.wakeRequestArbitration;
        this.gpuLine = mem.gpuLine;
        this.gpuTicksInLine = mem.gpuTicksInLine;
        this.hblankStartTicksInLine = mem.hblankStartTicksInLine;
        this.cpuHalted = mem.cpuHalted;
        this.haltHdmaState = mem.haltHdmaState;
        this.haltEnteredThisTick = mem.haltEnteredThisTick;
        this.requestOverlappedCpuWrite = mem.requestOverlappedCpuWrite;
        this.interruptEntryWonArbitration = mem.interruptEntryWonArbitration;
        this.haltOpcodeRequestLatched = mem.haltOpcodeRequestLatched;
    }

    public record HdmaMemento(Mode gpuMode, boolean transferInProgress, boolean hblankTransfer, boolean lcdEnabled,
                              int length, int src, int dst, int tick,
                              int[] blockData,
                              int hblankRequestTicks, int hblankRequestAge,
                              int nextHblankRequestTicks,
                              int nextHblankRequestAge, int sourceBytesTransferred,
                              int cpuBusValue, boolean stopAfterCurrentBlock,
                              boolean preserveLengthAfterCurrentBlock,
                              boolean speedSwitchInProgress,
                              boolean speedSwitchStartedWithoutRequest,
                              boolean pauseOamDmaForSpeedSwitchBurst,
                              WakeRequestArbitration wakeRequestArbitration,
                              int gpuLine, int gpuTicksInLine,
                              int hblankStartTicksInLine, boolean cpuHalted,
                              HaltHdmaState haltHdmaState,
                              boolean haltEnteredThisTick,
                              boolean requestOverlappedCpuWrite,
                              boolean interruptEntryWonArbitration,
                              boolean haltOpcodeRequestLatched) implements Memento<Hdma> {
    }

    public record SourceBusSample(int address, int value) {
    }

}
