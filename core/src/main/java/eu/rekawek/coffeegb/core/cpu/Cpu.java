package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.cpu.op.Op;
import eu.rekawek.coffeegb.core.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccess;
import eu.rekawek.coffeegb.core.memory.PerformanceRomAccessProvider;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.util.List;
import java.util.function.IntConsumer;

public class Cpu implements StatefulComponent<Cpu> {

    /** Maximum bounded PERFORMANCE epoch in master ticks. */
    public static final int PERFORMANCE_EPOCH_MAX_TICKS = 54;

    private static final int FLAG_Z = 0x80;

    private static final int FLAG_N = 0x40;

    private static final int FLAG_H = 0x20;

    private static final int FLAG_C = 0x10;

    public static final int STAT_READ_PHASE_SYNCHRONOUS_HALT_ENTRY = 1;

    public static final int STAT_READ_PHASE_ASYNCHRONOUS_HALT_ENTRY = 1 << 1;

    public static final int STAT_READ_PHASE_ORDINARY_HALT_WAKE = 1 << 2;

    public static final int STAT_READ_PHASE_ONE_CYCLE_ORDINARY_HALT_WAKE = 1 << 3;

    public static final int HDMA_PHASE_IN_FLIGHT_WRITE_CYCLE = 1;

    public static final int HDMA_PHASE_CPU_REQUEST_SLOT_IN_PROGRESS = 1 << 1;

    public static final int HDMA_PHASE_INTERRUPT_CLAIMED = 1 << 2;

    public enum State {
        OPCODE, EXT_OPCODE, OPERAND, RUNNING, IRQ_WAIT_1, IRQ_WAIT_2, IRQ_PUSH_1, IRQ_PUSH_2, IRQ_JUMP, STOPPED, HALTED,
        SPEED_SWITCH,
        // an illegal opcode was executed: the CPU is frozen for good (hardware hangs)
        LOCKED
    }

    // CPU-clock ticks. Hardware's full STOP/speed-switch sequence is $20008
    // clocks; this countdown begins after Coffee GB has already consumed the
    // final 8 STOP-entry clocks. Both directions use the same CPU-clock delay,
    // so single-to-double occupies half as many 4.19 MHz system ticks.
    private static final int SPEED_SWITCH_DELAY = 0x20000;

    private final Registers registers;

    private final AddressSpace baseAddressSpace;

    /** Immutable typed entrance to the optional native-CGB physical ROM mapping chain. */
    private final PerformanceRomAccessProvider performanceRomAccessProvider;

    private AddressSpace addressSpace;

    /** Reused CPU-bus observer for the native-CGB coarse PERFORMANCE epoch. */
    private transient PerformanceEpochBus performanceEpochBus;

    /** Borrowed direct ROM mapping retained only for one bounded native-CGB epoch. */
    private transient PerformanceRomAccess performanceEpochRomAccess;

    private transient AddressSpace performanceEpochTarget;

    private transient boolean performanceEpochActive;

    private transient boolean performanceEpochTerminal;

    private transient boolean performanceEpochJournalValid;

    private transient int performanceEpochJournalAddress;

    private transient int performanceEpochJournalValue;

    private transient long performanceEpochCount;

    private transient long performanceEpochTicks;

    private transient long performanceEpochAccesses;

    private transient long performanceEpochTerminalAccesses;

    private transient IntConsumer performanceEpochPrefixCommitter;

    private transient int performanceEpochElapsed;

    private transient int performanceEpochPrefixTicks;

    /** Epoch-local proof that LCD-off CGB VRAM is an unobserved direct memory plane. */
    private transient boolean performanceEpochLcdOffVramAccess;

    private transient DebugCpuAddressSpace debugAddressSpace;

    private transient DebugHooks debugHooks;

    private transient boolean debugInstructionKnown;

    private transient int debugInstructionPc;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Display display;

    private final SpeedMode speedMode;

    private final Timer timer;

    private int opcode1, opcode2;

    private final int[] operand = new int[2];

    private Opcode currentOpcode;

    private Op[] currentExecutionOps;

    private boolean[] currentOpAccessesMemory;

    private boolean[] currentOpWritesMemory;

    private int currentOpCount;

    private int currentOperandLength;

    private List<Op> ops;

    private int operandIndex;

    private int opIndex;

    private State state = State.OPCODE;

    private int opContext;

    private int interruptFlag;

    private int interruptEnabled;

    private InterruptManager.InterruptType requestedIrq;

    private int clockCycle = 0;

    private boolean haltBugMode;

    private int haltEntrySampleTicks;

    private boolean synchronousHaltEntryStatPhase;

    private boolean asynchronousHaltEntryStatPhase;

    private boolean ordinaryHaltWakeStatPhase;

    // Number of complete idle machine cycles between HALT entry and an ordinary
    // interrupt wake. The first post-entry slot has a distinct DMG STAT bus phase.
    private int haltedCpuCycles;

    private boolean hdmaOpcodePrefetched;

    private int hdmaArbitrationOpcode;

    private boolean hdmaArbitrationOpcodeValid;

    private int haltPrefetchedOpcode;

    private boolean haltOpcodePrefetchValid;

    private int speedSwitchPaddingOpcode;

    private boolean speedSwitchPaddingReplayValid;

    private int speedSwitchTicks;

    private boolean phasedPpuInputHigh;

    private boolean fastPhasedPpuDispatch;

    private boolean stopFrameBlankRequested;

    /**
     * Optional debugger-only retirement sequence. It is deliberately absent from component state:
     * enabling observation must not change the emulated machine or any portable checkpoint.
     */
    private transient DebugRetirementTracker debugRetirementTracker;

    public Cpu(AddressSpace addressSpace, InterruptManager interruptManager, Gpu gpu, SpeedMode speedMode,
               Display display) {
        this(addressSpace, interruptManager, gpu, speedMode, display, null);
    }

    public Cpu(AddressSpace addressSpace, InterruptManager interruptManager, Gpu gpu, SpeedMode speedMode,
               Display display, Timer timer) {
        this.registers = new Registers();
        this.baseAddressSpace = addressSpace;
        this.performanceRomAccessProvider = addressSpace instanceof PerformanceRomAccessProvider provider
                ? provider
                : null;
        this.addressSpace = addressSpace;
        this.interruptManager = interruptManager;
        this.gpu = gpu;
        this.speedMode = speedMode;
        this.display = display;
        this.timer = timer;
        this.performanceEpochBus = new PerformanceEpochBus(this);
    }

    /**
     * Runs a bounded PERFORMANCE epoch with the CPU bus observed by a
     * reusable address-space wrapper.  The wrapper deliberately does not speculate:
     * safe ROM/WRAM/echo/HRAM accesses continue, while the first external access ends
     * the epoch after the real CPU operation has observed that access.  An external
     * write is retained in the one-slot journal and is replayed by the owning Gameboy
     * after its frozen peripheral prefix has been committed.
     *
     * <p>This is an intentionally relaxed coordination boundary.  It may end in the
     * middle of an instruction and therefore must never be used by ACCURACY, debug,
     * history-replay, or stop-aware callers.</p>
     *
     * @return master ticks consumed by the CPU epoch
     */
    public int runPerformanceEpoch(int maxMasterTicks) {
        if (maxMasterTicks <= 0 || !performanceEpochEntryEligible()) {
            return 0;
        }
        int requested = Math.min(maxMasterTicks, PERFORMANCE_EPOCH_MAX_TICKS);
        if (requested <= 0) {
            return 0;
        }
        PerformanceEpochBus bus = performanceEpochBus;
        if (bus == null) {
            bus = new PerformanceEpochBus(this);
            performanceEpochBus = bus;
        }
        AddressSpace target = addressSpace;
        int elapsed = 0;
        performanceEpochRomAccess = acquirePerformanceEpochRomAccess();
        try {
            performanceEpochTarget = target;
            performanceEpochTerminal = false;
            performanceEpochJournalValid = false;
            performanceEpochJournalAddress = 0;
            performanceEpochJournalValue = 0;
            performanceEpochElapsed = 0;
            performanceEpochPrefixTicks = 0;
            performanceEpochActive = true;
            bus.resetForEpoch(target);
            addressSpace = bus;
            while (elapsed < requested) {
                if (clockCycle == 0) {
                    clockCycle = 1;
                    elapsed++;
                    if (elapsed >= requested) {
                        break;
                    }
                }

                // Fence the next fetch/operand window before the native epoch may use its
                // borrowed ROM mapping. Scalar and physical-DMG fetches remain on the base bus.
                if (!performanceEpochPrefetchSafe()) {
                    performanceEpochTerminal = true;
                    break;
                }

                State stateBeforeTick = state;
                performanceEpochElapsed = elapsed;
                clockCycle = 0;
                int directTailTicks = tickPerformanceEpochInstructionPipelineAtMachineCycle(
                        requested - elapsed);
                elapsed += 1 + directTailTicks;
                if (stateBeforeTick == State.OPCODE
                        && (opcode1 == 0x10 || opcode1 == 0x76
                        || opcode1 == 0xf3 || opcode1 == 0xfb || opcode1 == 0xd9)) {
                    performanceEpochTerminal = true;
                }
                if (performanceEpochTerminal || isPerformanceEpochLifecycleState()) {
                    break;
                }
            }
        } finally {
            performanceEpochRomAccess = null;
            addressSpace = target;
            performanceEpochActive = false;
            performanceEpochAccesses += bus.accesses();
            performanceEpochTerminalAccesses += bus.terminalAccesses();
            performanceEpochTicks += elapsed;
            if (elapsed > 0) {
                performanceEpochCount++;
            }
            performanceEpochPrefixCommitter = null;
        }
        return elapsed;
    }

    /**
     * Physical-DMG counterpart to {@link #runPerformanceEpoch(int)}. This path is
     * deliberately separate so native CGB retains its fixed two-dot hot loop.
     *
     * @return master ticks consumed by the CPU epoch
     */
    public int runPhysicalDmgPerformanceEpoch(int maxMasterTicks) {
        return runPerformanceNormalSpeedEpoch(
                maxMasterTicks, false, false, false, false, false);
    }

    /**
     * Fixed-width SGB/SGB2 epoch which leaves observable memory-mapped accesses to the scalar
     * scheduler. SGB JOYP writes and APU/PPU/DMA accesses are visible during their CPU tick, so
     * they cannot use the physical-DMG deferred journal ordering. Safe direct-whole accesses
     * may remain inside the epoch.
     */
    public int runSgbPerformanceEpoch(int maxMasterTicks) {
        return runPerformanceNormalSpeedEpoch(
                maxMasterTicks, false, true, false, false, false);
    }

    /**
     * Fixed four-master-dot epoch for ordinary CGB hardware in DMG compatibility mode.
     * The CPU bus and machine-cycle timing are the same normal-speed width as physical DMG,
     * while the owner supplies the CGB-only peripheral/PPU plane around this transaction.
     */
    public int runCgbCompatibilityPerformanceEpoch(int maxMasterTicks) {
        return speedMode.isDmgCompat()
                ? runPerformanceNormalSpeedEpoch(
                        maxMasterTicks, true, false, false, false, false) : 0;
    }

    /**
     * Fixed four-master-dot epoch for native CGB software which remains at normal speed.
     * Address-known ROM reads and work/high-RAM accesses may remain inside the epoch.
     * CPU-visible peripheral, mapper, cartridge-RAM, and RTC accesses stay scalar so they
     * retain their position before the owner ticks Sound and the remaining peripherals.
     */
    public int runNativeCgbNormalSpeedPerformanceEpoch(int maxMasterTicks) {
        return !speedMode.isDmgCompat()
                ? runPerformanceNormalSpeedEpoch(
                        maxMasterTicks, true, true, true, false, true) : 0;
    }

    /**
     * Native-CGB x1 epoch while the owner has proven that LCD output and both DMA planes are
     * inactive. Decoded memory cycles remain fenced except for direct VRAM reads/writes; LCDC,
     * other IO, OAM, cartridge control, and executable VRAM all retain the scalar boundary.
     */
    public int runNativeCgbNormalSpeedLcdOffPerformanceEpoch(int maxMasterTicks) {
        return !speedMode.isDmgCompat()
                ? runPerformanceNormalSpeedEpoch(
                        maxMasterTicks, true, true, true, true, true) : 0;
    }

    /** Shared fixed-width normal-speed epoch; topology flags are explicit and allocation-free. */
    private int runPerformanceNormalSpeedEpoch(
            int maxMasterTicks, boolean cgbHardware, boolean fenceDecodedMemoryCycles,
            boolean allowResolvedSafeDecodedAccess, boolean allowLcdOffVramAccess,
            boolean allowImeDisabledRawPendingInterrupt) {
        if (maxMasterTicks <= 0 || !performanceNormalSpeedEpochEntryEligible(
                cgbHardware, allowImeDisabledRawPendingInterrupt)) {
            return 0;
        }
        int requested = Math.min(maxMasterTicks, PERFORMANCE_EPOCH_MAX_TICKS);
        if (requested <= 0) {
            return 0;
        }
        PerformanceEpochBus bus = performanceEpochBus;
        AddressSpace target = addressSpace;
        performanceEpochTarget = target;
        performanceEpochTerminal = false;
        performanceEpochJournalValid = false;
        performanceEpochJournalAddress = 0;
        performanceEpochJournalValue = 0;
        performanceEpochElapsed = 0;
        performanceEpochPrefixTicks = 0;
        performanceEpochActive = true;
        performanceEpochLcdOffVramAccess = allowLcdOffVramAccess;
        bus.resetForEpoch(target);
        addressSpace = bus;
        int elapsed = 0;
        try {
            while (elapsed < requested) {
                int phaseDots = 3 - clockCycle;
                if (phaseDots > 0) {
                    int advanced = Math.min(phaseDots, requested - elapsed);
                    clockCycle += advanced;
                    elapsed += advanced;
                    if (elapsed >= requested) {
                        break;
                    }
                }

                if (!performanceEpochPrefetchSafe()
                        || allowImeDisabledRawPendingInterrupt
                        && hasImeDisabledRawPendingInterrupt()
                        && performanceNextBoundaryFetchesHalt()
                        || fenceDecodedMemoryCycles
                        && !performanceDecodedMemoryBoundarySafe(
                        allowResolvedSafeDecodedAccess, allowLcdOffVramAccess)) {
                    performanceEpochTerminal = true;
                    break;
                }

                State stateBeforeTick = state;
                performanceEpochElapsed = elapsed;
                clockCycle = 0;
                int directTailTicks = tickPerformancePhysicalDmgEpochInstructionPipelineAtMachineCycle(
                        requested - elapsed);
                elapsed += 1 + directTailTicks;
                if (stateBeforeTick == State.OPCODE
                        && (opcode1 == 0x10 || opcode1 == 0x76
                        || opcode1 == 0xf3 || opcode1 == 0xfb || opcode1 == 0xd9)) {
                    performanceEpochTerminal = true;
                }
                if (performanceEpochTerminal || isPerformanceEpochLifecycleState()) {
                    break;
                }
            }
        } finally {
            addressSpace = target;
            performanceEpochActive = false;
            performanceEpochLcdOffVramAccess = false;
            performanceEpochAccesses += bus.accesses();
            performanceEpochTerminalAccesses += bus.terminalAccesses();
            performanceEpochTicks += elapsed;
            if (elapsed > 0) {
                performanceEpochCount++;
            }
            performanceEpochPrefixCommitter = null;
        }
        return elapsed;
    }

    /**
     * Rejects a fenced epoch before a CPU boundary which can reach an unsafe data-memory
     * operation. Operations before the next force-finish marker execute in the same
     * machine-cycle tick, so the complete group is classified before any part may run.
     */
    private boolean performanceDecodedMemoryBoundarySafe(
            boolean allowResolvedSafeDecodedAccess, boolean allowLcdOffVramAccess) {
        if (state != State.RUNNING || currentExecutionOps == null) {
            return true;
        }
        for (int i = opIndex; i < currentOpCount; i++) {
            Op op = currentExecutionOps[i];
            if (allowResolvedSafeDecodedAccess
                    && op.causesOemBug(registers, opContext) != null) {
                return false;
            }
            if (currentOpAccessesMemory[i]) {
                if (!allowResolvedSafeDecodedAccess) {
                    return false;
                }
                Integer address = op.resolveMemoryAddress(registers, operand, opContext);
                if (address == null) {
                    if (!op.isInternalMemoryCycle()) {
                        return false;
                    }
                } else if (currentOpWritesMemory[i]
                        ? !isPerformanceEpochSafeWrite(address, allowLcdOffVramAccess)
                        : !isPerformanceEpochSafeRead(address, allowLcdOffVramAccess)) {
                    return false;
                }
            }
            if (currentExecutionOps[i].forceFinishCycle()) {
                break;
            }
        }
        return true;
    }

    private static boolean isPerformanceEpochSafeRead(
            int address, boolean allowLcdOffVramAccess) {
        return PerformanceEpochBus.isSafeRead(address)
                || allowLcdOffVramAccess && PerformanceEpochBus.isVideoRam(address);
    }

    private static boolean isPerformanceEpochSafeWrite(
            int address, boolean allowLcdOffVramAccess) {
        return PerformanceEpochBus.isSafeWrite(address)
                || allowLcdOffVramAccess && PerformanceEpochBus.isVideoRam(address);
    }

    private boolean isPerformanceEpochSafeRead(int address) {
        return isPerformanceEpochSafeRead(address, performanceEpochLcdOffVramAccess);
    }

    private boolean isPerformanceEpochSafeWrite(int address) {
        return isPerformanceEpochSafeWrite(address, performanceEpochLcdOffVramAccess);
    }

    private boolean performanceEpochPrefetchSafe() {
        return switch (state) {
            // HALT samples the following opcode in the same fetch boundary, so PC+1
            // remains part of this fence. Ordinary operand bytes are classified at
            // their own later machine-cycle boundaries; PC+2 was redundant.
            case OPCODE -> PerformanceEpochBus.isSafeRead(registers.getPC())
                    && PerformanceEpochBus.isSafeRead(registers.getPC() + 1);
            // An IME=0 HALT wake changes to OPCODE and fetches on this same boundary.
            // Conservatively include IME=1/held-prefetch wakes as well; priority and
            // arbitration are resolved only after this zero-dot fence.
            case HALTED -> !interruptManager.isInterruptRequestedForHalt()
                    || PerformanceEpochBus.isSafeRead(registers.getPC())
                    && PerformanceEpochBus.isSafeRead(registers.getPC() + 1);
            case EXT_OPCODE -> PerformanceEpochBus.isSafeRead(registers.getPC());
            case OPERAND -> currentOpcode == null || operandIndex >= currentOperandLength
                    || PerformanceEpochBus.isSafeRead(registers.getPC());
            default -> true;
        };
    }

    /**
     * HALT with IME clear and an enabled stored request owns the HALT-bug latch race. The
     * native-CGB x1 epoch may run ordinary code under that masked request, but it leaves the
     * fetch boundary itself untouched when the next opcode is HALT.
     */
    private boolean performanceNextBoundaryFetchesHalt() {
        return state == State.OPCODE && readInstructionByte(registers.getPC()) == 0x76;
    }

    private boolean hasImeDisabledRawPendingInterrupt() {
        return !interruptManager.isIme()
                && interruptManager.hasRawPendingEnabledInterrupt();
    }

    /** Cheap state-only entrance check used before the owner walks peripheral horizons. */
    public boolean performanceEpochEntryEligible() {
        if (debugAddressSpace != null || debugHooks != null || debugRetirementTracker != null
                || state == State.HALTED || state == State.STOPPED
                || state == State.SPEED_SWITCH || state == State.LOCKED
                || state == State.IRQ_WAIT_1 || state == State.IRQ_WAIT_2
                || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2
                || state == State.IRQ_JUMP
                || state == State.EXT_OPCODE && opcode1 == 0x10
                || state != State.OPCODE && opcode1 == 0xd9
                || clockCycle < 0 || clockCycle > 1
                || haltBugMode || haltEntrySampleTicks != 0
                || phasedPpuInputHigh || fastPhasedPpuDispatch
                || hdmaOpcodePrefetched || hdmaArbitrationOpcodeValid
                || haltOpcodePrefetchValid || speedSwitchPaddingReplayValid
                || interruptManager.hasPendingCpuReadPhase()
                || interruptManager.hasPpuTickSignals()
                || interruptManager.isInterruptEnablePending()
                || interruptManager.hasRawPendingEnabledInterrupt()) {
            return false;
        }
        return speedMode.getSpeedMode() == 2
                && speedMode.isGbc()
                && !speedMode.isDmgCompat();
    }

    /** Cheap state-only entrance check for the fixed-width physical-DMG epoch. */
    public boolean performancePhysicalDmgEpochEntryEligible() {
        return performanceNormalSpeedEpochEntryEligible(false);
    }

    /** Cheap state-only entrance check for ordinary CGB DMG-compatibility epochs. */
    public boolean performanceCgbCompatibilityEpochEntryEligible() {
        return speedMode.isDmgCompat() && performanceNormalSpeedEpochEntryEligible(true);
    }

    /** Cheap state-only entrance check for native CGB software at the normal clock. */
    public boolean performanceNativeCgbNormalSpeedEpochEntryEligible() {
        return !speedMode.isDmgCompat()
                && performanceNormalSpeedEpochEntryEligible(true, true);
    }

    /** Shared state-only entrance check for the fixed-width normal-speed epoch. */
    public boolean performanceNormalSpeedEpochEntryEligible(boolean cgbHardware) {
        return performanceNormalSpeedEpochEntryEligible(cgbHardware, false);
    }

    private boolean performanceNormalSpeedEpochEntryEligible(
            boolean cgbHardware, boolean allowImeDisabledRawPendingInterrupt) {
        boolean topologyMatches = cgbHardware
                ? speedMode.isGbc()
                : !speedMode.isGbc();
        if (debugAddressSpace != null || debugHooks != null || debugRetirementTracker != null
                || state == State.HALTED || state == State.STOPPED
                || state == State.SPEED_SWITCH || state == State.LOCKED
                || state == State.IRQ_WAIT_1 || state == State.IRQ_WAIT_2
                || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2
                || state == State.IRQ_JUMP
                || state == State.EXT_OPCODE && opcode1 == 0x10
                || performanceInterruptTransitionInFlight()
                || clockCycle < 0 || clockCycle > 3
                || haltBugMode || haltEntrySampleTicks != 0
                || phasedPpuInputHigh || fastPhasedPpuDispatch
                || hdmaOpcodePrefetched || hdmaArbitrationOpcodeValid
                || haltOpcodePrefetchValid || speedSwitchPaddingReplayValid
                || interruptManager.hasPendingCpuReadPhase()
                || interruptManager.hasPpuTickSignals()
                || interruptManager.isInterruptEnablePending()
                || interruptManager.hasRawPendingEnabledInterrupt()
                        && (!allowImeDisabledRawPendingInterrupt
                        || interruptManager.isIme())) {
            return false;
        }
        return speedMode.getSpeedMode() == 1 && topologyMatches;
    }

    /** Keeps delayed-enable and low-power control instructions on their scalar seams. */
    private boolean performanceInterruptTransitionInFlight() {
        if (state == State.OPCODE) {
            return false;
        }
        return opcode1 == 0xfb || opcode1 == 0xd9
                || opcode1 == 0x76 || opcode1 == 0x10;
    }

    private boolean isPerformanceEpochLifecycleState() {
        return state == State.HALTED || state == State.STOPPED || state == State.SPEED_SWITCH
                || state == State.LOCKED;
    }

    /** Replays the single unsafe write retained by the most recent epoch, once. */
    public boolean replayPerformanceEpochJournal() {
        if (!performanceEpochJournalValid || performanceEpochTarget == null) {
            return false;
        }
        int address = performanceEpochJournalAddress;
        int value = performanceEpochJournalValue;
        performanceEpochJournalValid = false;
        performanceEpochTarget.setByte(address, value);
        return true;
    }

    public boolean hasPerformanceEpochJournal() {
        return performanceEpochJournalValid;
    }

    /** Installs the owner callback used to flush the frozen peripheral prefix before an unsafe read. */
    public void setPerformanceEpochPrefixCommitter(IntConsumer committer) {
        performanceEpochPrefixCommitter = committer;
    }

    private void flushPerformanceEpochPrefix() {
        if (performanceEpochElapsed <= performanceEpochPrefixTicks) {
            return;
        }
        performanceEpochPrefixTicks = performanceEpochElapsed;
        IntConsumer committer = performanceEpochPrefixCommitter;
        if (committer != null) {
            committer.accept(performanceEpochElapsed);
        }
    }

    private void journalPerformanceEpochWrite(int address, int value) {
        if (!performanceEpochJournalValid) {
            performanceEpochJournalAddress = address & 0xffff;
            performanceEpochJournalValue = value & 0xff;
            performanceEpochJournalValid = true;
        }
    }

    private void markPerformanceEpochTerminal() {
        performanceEpochTerminal = true;
    }

    public void resetPerformanceEpochTelemetry() {
        performanceEpochCount = 0L;
        performanceEpochTicks = 0L;
        performanceEpochAccesses = 0L;
        performanceEpochTerminalAccesses = 0L;
    }

    public long getPerformanceEpochCount() {
        return performanceEpochCount;
    }

    public long getPerformanceEpochTicks() {
        return performanceEpochTicks;
    }

    public long getPerformanceEpochAccesses() {
        return performanceEpochAccesses;
    }

    public long getPerformanceEpochTerminalAccesses() {
        return performanceEpochTerminalAccesses;
    }

    /**
     * Returns whether a native CGB double-speed HALT is settled enough to own a multi-dot
     * PERFORMANCE packet.  This is intentionally separate from the normal-speed HALT lane:
     * double speed has a two-dot CPU machine-cycle phase and its PPU/interrupt synchronizers
     * have additional residue which must remain on the scalar owner.
     */
    public boolean performanceNativeCgbSettledHaltSpanEligible() {
        return speedMode.getSpeedMode() == 2
                && speedMode.isGbc()
                && !speedMode.isDmgCompat()
                && state == State.HALTED
                && clockCycle >= 0
                && clockCycle < 2
                && haltEntrySampleTicks == 0
                && !synchronousHaltEntryStatPhase
                && !asynchronousHaltEntryStatPhase
                && !ordinaryHaltWakeStatPhase
                && !haltBugMode
                && !phasedPpuInputHigh
                && !fastPhasedPpuDispatch
                && !hdmaOpcodePrefetched
                && !hdmaArbitrationOpcodeValid
                && !haltOpcodePrefetchValid
                && !speedSwitchPaddingReplayValid
                && !interruptManager.hasPendingCpuReadPhase()
                && !interruptManager.hasPpuTickSignals()
                && !interruptManager.isInterruptEnablePending()
                && !interruptManager.hasRawPendingEnabledInterrupt()
                && !interruptManager.isInterruptRequestedForHalt()
                && !interruptManager.isInterruptRequestedWhileHaltWakeBlocked();
    }
    /**
     * Advances the CPU clock phase without entering the instruction sequencer when this
     * master tick is not a CPU machine-cycle boundary.
     *
     * <p>The ordinary emulator used to call {@link #tick()} for every master tick.  In
     * normal speed three of those four calls only update the free-running phase and the
     * synchronizer used by the PPU interrupt path.  PERFORMANCE can use this split to keep
     * those calls on a small, allocation-free path while retaining the complete scalar
     * sequencer at every boundary.  The return value is {@code true} when the caller must
     * not enter the machine-cycle sequencer.</p>
     */
    public boolean tickPhaseOnly() {
        // VRAM DMA performs the next opcode fetch before taking the bus. Once the
        // burst releases the CPU, this ordinary machine cycle consumes that held
        // opcode and resumes the instruction pipeline.
        hdmaOpcodePrefetched = false;

        updatePhasedPpuInput();

        if (state == State.SPEED_SWITCH) {
            if (speedSwitchTicks > 0) {
                speedSwitchTicks -= speedMode.getSpeedMode();
            }
            if (speedSwitchTicks <= 0) {
                speedSwitchTicks = 0;
                state = State.OPCODE;
            }
            return true;
        }

        if (++clockCycle >= (4 / speedMode.getSpeedMode())) {
            clockCycle = 0;
        } else {
            return true;
        }

        return false;
    }

    /**
     * Returns the number of following master ticks that are guaranteed not to reach a CPU
     * machine-cycle boundary. A zero result deliberately selects the scalar scheduler for
     * double-speed, speed-switch, and rephased interrupt paths.
     */
    public int performancePhaseOnlySpanLimit() {
        if (speedMode.getSpeedMode() != 1 || state == State.SPEED_SWITCH || clockCycle < 0) {
            return 0;
        }
        return Math.max(0, 3 - clockCycle);
    }

    /**
     * Returns whether a settled normal-speed HALT can own a multi-machine-cycle PERFORMANCE
     * span.  HALT entry and the first wake sample remain scalar; once both have settled, a
     * halted CPU is only an idle NOP sequencer until the next wake edge.
     */
    public boolean performanceSettledHaltSpanEligible() {
        return speedMode.getSpeedMode() == 1
                && state == State.HALTED
                && haltEntrySampleTicks == 0
                && !synchronousHaltEntryStatPhase
                && !asynchronousHaltEntryStatPhase
                && !ordinaryHaltWakeStatPhase
                && !interruptManager.isInterruptRequestedForHalt()
                && !interruptManager.isInterruptRequestedWhileHaltWakeBlocked();
    }

    /**
     * Whether a PERFORMANCE phase-only span may safely call the peripheral wake callback.
     *
     * <p>HALT entry and wake are deliberately kept on the scalar scheduler. Once HALT's entry
     * sample has settled, however, the peripheral callback is a no-op until an existing quiet
     * horizon reaches the next wake edge, so its non-boundary dots are eligible. The callback can
     * otherwise rephase {@link #clockCycle} for the HALT bug or change the CPU state as an
     * interrupt edge becomes visible. The interrupt sequencer and locked/paused states remain
     * scalar.</p>
     */
    public boolean performancePhaseOnlySpanEligible() {
        return haltEntrySampleTicks == 0
                && (state == State.OPCODE
                || state == State.EXT_OPCODE
                || state == State.OPERAND
                || state == State.RUNNING
                || state == State.HALTED);
    }

    /**
     * Returns false while a CPU-visible STAT/IF read phase still needs the scalar scheduler's
     * begin/finish hooks.  The phase markers are intentionally treated conservatively: unlike a
     * normal opcode boundary they can alter the sampled IF/STAT value on the very first dot after
     * a machine cycle, before StatRegister's bulk preflight observes the edge.
     */
    public boolean performanceNoPendingPpuReadPhase() {
        return getStatReadPhaseFlags() == 0
                && !interruptManager.hasPendingCpuReadPhase();
    }

    /**
     * Native-CGB x1 phase packets may carry only the durable ordinary HALT-wake marker. Its
     * optional one-cycle qualifier is meaningful only together with the ordinary marker;
     * synchronous/asynchronous entry phases and every pending IF/STAT read aperture stay scalar.
     */
    public boolean performanceNativeCgbNormalSpeedNoPendingPpuReadPhase() {
        if (!speedMode.isGbc() || speedMode.isDmgCompat()
                || speedMode.getSpeedMode() != 1) {
            return false;
        }
        int flags = getStatReadPhaseFlags();
        int allowed = STAT_READ_PHASE_ORDINARY_HALT_WAKE
                | STAT_READ_PHASE_ONE_CYCLE_ORDINARY_HALT_WAKE;
        boolean flagsValid = (flags & ~allowed) == 0
                && ((flags & STAT_READ_PHASE_ONE_CYCLE_ORDINARY_HALT_WAKE) == 0
                || (flags & STAT_READ_PHASE_ORDINARY_HALT_WAKE) != 0);
        return flagsValid
                && !interruptManager.hasPendingCpuReadPhase();
    }

    /**
     * Advances a preflighted non-boundary phase without repeatedly entering {@link #tick()}.
     * The caller must keep {@code ticks <= performancePhaseOnlySpanLimit()}.
     */
    public boolean advancePerformancePhaseOnly(int ticks) {
        if (ticks < 0 || ticks > performancePhaseOnlySpanLimit()
                || !performancePhaseOnlySpanEligible()) {
            return false;
        }
        if (ticks == 0) {
            return true;
        }
        hdmaOpcodePrefetched = false;
        updatePhasedPpuInput();
        clockCycle += ticks;
        return true;
    }

    /** Advances a span after Gameboy has preflighted the normal-speed CPU phase and state. */
    public void advancePerformancePhaseOnlyTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        hdmaOpcodePrefetched = false;
        updatePhasedPpuInput();
        clockCycle += ticks;
    }

    /**
     * Advances a preflighted settled-HALT span, including complete idle machine cycles.  The
     * next peripheral/wake edge is excluded by the caller's horizon, so no instruction
     * sequencer callback is needed inside the span. The arithmetic consumes complete idle
     * machine-cycle boundaries inside the span; only the excluded next event tick is left to
     * the scalar scheduler.
     */
    public void advancePerformanceSettledHaltSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        hdmaOpcodePrefetched = false;
        updatePhasedPpuInput();
        int distanceToBoundary = 4 - clockCycle;
        int completedBoundaries = ticks >= distanceToBoundary
                ? 1 + (ticks - distanceToBoundary) / 4 : 0;
        clockCycle = (clockCycle + ticks) & 0x03;
        haltedCpuCycles = Math.min(2, haltedCpuCycles + completedBoundaries);
    }

    /** Advances a preflighted native-CGB double-speed settled-HALT span on the two-dot phase. */
    public void advancePerformanceNativeCgbSettledHaltSpanTrusted(int ticks) {
        if (ticks <= 0) {
            return;
        }
        hdmaOpcodePrefetched = false;
        updatePhasedPpuInput();
        int distanceToBoundary = 2 - clockCycle;
        int completedBoundaries = ticks >= distanceToBoundary
                ? 1 + (ticks - distanceToBoundary) / 2 : 0;
        clockCycle = (clockCycle + ticks) % 2;
        haltedCpuCycles = Math.min(2, haltedCpuCycles + completedBoundaries);
    }

    private void updatePhasedPpuInput() {
        boolean phasedPpuInput = interruptManager.isPhasedMode2InterruptRequested();
        if (phasedPpuInput && !phasedPpuInputHigh) {
            int cpuCycleTicks = 4 / speedMode.getSpeedMode();
            fastPhasedPpuDispatch = (cpuCycleTicks == 4 && clockCycle == 1)
                    || interruptManager.isFirstLineMode2InterruptRequested();
        } else if (!phasedPpuInput) {
            fastPhasedPpuDispatch = false;
        }
        phasedPpuInputHigh = phasedPpuInput;
    }

    /**
     * Runs the instruction sequencer after {@link #tickPhaseOnly()} reached a machine-cycle
     * boundary.  This is public for the core scheduler; callers must not invoke it unless the
     * phase method returned {@code false}.
     */
    public void tickAtMachineCycle() {

        if (state == State.LOCKED) {
            return;
        }

        // HALT's asynchronous entry window closes at the next CPU boundary.
        haltEntrySampleTicks = 0;

        boolean wokeFromHalt = false;
        if (state == State.HALTED && interruptManager.isInterruptRequestedForHalt()) {
            // a halted CPU behaves exactly like it was executing NOPs, so the wake-up
            // has the same timing as the running state: the interrupt dispatch starts
            // (IME=1) or the next instruction is fetched (IME=0) at the cycle following
            // the interrupt request (halt_ime1_timing2-GS, halt_ime0_nointr_timing)
            state = State.OPCODE;
            wokeFromHalt = true;
            ordinaryHaltWakeStatPhase = true;
        }

        if (state == State.STOPPED && isJoypadLineLow()) {
            // STOP is released by the physical joypad line, independently of IE and
            // IME. This is also sampled before entering STOP: a button that is already
            // held makes STOP fall through instead of turning the clocks/LCD off.
            state = State.OPCODE;
            display.enableLcd();
        }

        // On DMG the ZUMN STOP latch is reset only by ZWLM/WAKE, whose AWOB
        // source is the physical JOYP input. An enabled IF source therefore
        // cannot release STOP or re-enable the LCD. Keep the existing CGB
        // interrupt-wake path; its STOP/speed-switch machine is separate from
        // the DMG circuit represented by that cone.
        boolean mayAcceptInterrupt = state == State.OPCODE
                || (state == State.STOPPED && speedMode.isGbc());
        if (mayAcceptInterrupt) {
            // A request restored while mode 3 owns the shared VRAM-DMA arbitration
            // slot before interrupt dispatch. Retire HALT's sampled opcode first;
            // mode-2 wake-up still lets interrupt acceptance preempt that sample.
            boolean heldHdmaOpcodeOwnsWakeSlot = wokeFromHalt && haltOpcodePrefetchValid
                    && gpu != null && gpu.getMode() == Mode.PixelTransfer;
            if (interruptManager.isIme() && interruptManager.isInterruptRequested()
                    && !heldHdmaOpcodeOwnsWakeSlot) {
                haltOpcodePrefetchValid = false;
                hdmaArbitrationOpcodeValid = false;
                if (state == State.STOPPED) {
                    display.enableLcd();
                }
                if (speedMode.getSpeedMode() == 2
                        && interruptManager.isPhasedMode2InterruptRequested()
                        && !interruptManager.isFirstLineMode2InterruptRequested()) {
                    gpu.onDoubleSpeedMode2Dispatch();
                }
                boolean fastCgbPpuDispatch = speedMode.isGbc()
                        && (interruptManager.isUnphasedPpuInterruptRequested()
                        || fastPhasedPpuDispatch)
                        && !wokeFromHalt;
                boolean phasedMode2Dispatch = fastCgbPpuDispatch
                        && fastPhasedPpuDispatch;
                boolean firstLineMode2Dispatch = phasedMode2Dispatch
                        && interruptManager.isFirstLineMode2InterruptRequested();
                if (fastCgbPpuDispatch) {
                    // The direct CGB PPU path skips IRQ_WAIT_1 to accept the edge one
                    // machine cycle earlier. IRQ_WAIT_1 normally clears IME, so retain
                    // that acceptance side effect even though its wait cycle is absent.
                    interruptManager.disableInterrupts(false);
                }
                if (firstLineMode2Dispatch && speedMode.getSpeedMode() == 2) {
                    // The LCD-start mode-2 edge reaches both interrupt wait latches
                    // before the next double-speed machine cycle, so neither wait state
                    // is visible to the dispatch micro-sequence.
                    state = State.IRQ_PUSH_1;
                } else {
                    state = fastCgbPpuDispatch ? State.IRQ_WAIT_2 : State.IRQ_WAIT_1;
                }
                if (firstLineMode2Dispatch && speedMode.getSpeedMode() == 1) {
                    // At normal speed the same asynchronous edge lands one dot after
                    // the start of the skipped four-dot wait cycle.
                    clockCycle = -1;
                }
            }
        }

        if (state == State.IRQ_WAIT_1 || state == State.IRQ_WAIT_2 || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2 || state == State.IRQ_JUMP) {
            handleInterrupt();
            return;
        }

        if (state == State.HALTED || state == State.STOPPED) {
            if (state == State.HALTED) {
                haltedCpuCycles = Math.min(2, haltedCpuCycles + 1);
            }
            return;
        }

        boolean accessedMemory = false;
        while (true) {
            int pc = registers.getPC();
            switch (state) {
                case OPCODE:
                    boolean useHdmaHaltPrefetch = haltOpcodePrefetchValid;
                    boolean useSpeedSwitchPadding = speedSwitchPaddingReplayValid;
                    boolean useHdmaArbitrationOpcode = hdmaArbitrationOpcodeValid;
                    haltOpcodePrefetchValid = false;
                    speedSwitchPaddingReplayValid = false;
                    hdmaArbitrationOpcodeValid = false;
                    if (useHdmaHaltPrefetch) {
                        // A request acknowledged by HALT owns the bus before the held
                        // opcode may run, just like the ordinary DMA prefetch below.
                        hdmaOpcodePrefetched = true;
                    }
                    beginDebugInstruction(pc);
                    clearState();
                    opcode1 = useHdmaHaltPrefetch
                            ? haltPrefetchedOpcode
                            : useSpeedSwitchPadding
                            ? speedSwitchPaddingOpcode
                            : useHdmaArbitrationOpcode
                            ? hdmaArbitrationOpcode
                            : readInstructionByte(pc);
                    accessedMemory = true;
                    notifyOpcodeFetched(pc, false, opcode1);
                    if (opcode1 == 0xcb) {
                        state = State.EXT_OPCODE;
                    } else if (opcode1 == 0x10) {
                        setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                        state = State.EXT_OPCODE;
                    } else {
                        state = State.OPERAND;
                        setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                        if (currentOpcode == null) {
                            // an illegal opcode freezes the CPU on hardware; do the
                            // same instead of crashing the emulation thread
                            state = State.LOCKED;
                            markDebugRetirement();
                            return;
                        }
                    }
                    if (useHdmaHaltPrefetch || useSpeedSwitchPadding) {
                        // HALT samples the next opcode without advancing PC. If an
                        // already-latched HDMA request takes the bus, that sampled byte
                        // is consumed after wake while PC still addresses the same byte.
                        // STOP's padding byte has already advanced PC, so its held replay
                        // likewise must not advance the counter a second time.
                    } else if (!haltBugMode) {
                        registers.incrementPC();
                    } else {
                        haltBugMode = false;
                    }
                    break;

                case EXT_OPCODE:
                    if (accessedMemory) {
                        return;
                    }
                    accessedMemory = true;
                    opcode2 = readInstructionByte(pc);
                    if (opcode1 == 0xcb) {
                        notifyOpcodeFetched(debugInstructionPc, true, opcode2);
                    }
                    if (currentOpcode == null) {
                        setCurrentOpcode(Opcodes.EXT_COMMANDS.get(opcode2));
                    }
                    if (currentOpcode == null) {
                        state = State.LOCKED;
                        markDebugRetirement();
                        return;
                    }
                    state = State.OPERAND;
                    registers.incrementPC();
                    break;

                case OPERAND:
                    while (operandIndex < currentOperandLength) {
                        if (accessedMemory) {
                            return;
                        }
                        accessedMemory = true;
                        operand[operandIndex++] = readInstructionByte(pc);
                        registers.incrementPC();
                    }
                    ops = currentOpcode.getOps();
                    state = State.RUNNING;
                    break;

                case RUNNING:
                    if (opcode1 == 0x10) {
                        boolean exitByJoypad = isJoypadLineLow();
                        if (!exitByJoypad && speedMode.onStop()) {
                            if (timer != null) {
                                timer.onSpeedSwitch();
                            }
                            // A CGB speed switch resets and freezes DIV while the CPU clock
                            // is stopped. The PPU remains in its independent clock domain.
                            addressSpace.setByte(0xff04, 0);
                            speedSwitchTicks = SPEED_SWITCH_DELAY;
                            state = State.SPEED_SWITCH;
                        } else if (exitByJoypad) {
                            // A selected, asserted P10-P13 input wins over KEY1 and makes
                            // STOP exit immediately. In particular, do not consume the
                            // pending speed-switch request in this case.
                            state = State.OPCODE;
                        } else {
                            state = State.STOPPED;
                            // A stopped DMG drives color 0 (white). A CGB outside mode 3
                            // loses VRAM access and drives color 0 (black); during mode 3 it
                            // retains the picture that is already being scanned out.
                            if (gpu == null || !gpu.isGbc() || gpu.getMode() != Mode.PixelTransfer) {
                                stopFrameBlankRequested = true;
                            }
                            display.disableLcd();
                        }
                        markDebugRetirement();
                        return;
                    } else if (opcode1 == 0x76) {
                        // HALT always samples the next opcode. It is normally fetched
                        // again on wake, but a simultaneously acknowledged HDMA request
                        // turns this sample into the held pipeline opcode.
                        haltPrefetchedOpcode = readInstructionByte(registers.getPC());
                        haltOpcodePrefetchValid = false;
                        // committing a pending EI happens even when entering halt, so
                        // "ei; halt" halts with IME=1 (no halt bug, wake dispatches)
                        boolean imeBeforeHalt = interruptManager.isIme();
                        boolean interruptPendingBeforeHalt = interruptManager.isInterruptRequestedForHalt();
                        interruptManager.onInstructionFinished();
                        if (!imeBeforeHalt && interruptPendingBeforeHalt && interruptManager.isIme()) {
                            // HALT was fetched while EI's delayed enable was still pending.
                            // The interrupt is accepted at instruction completion, but hardware
                            // pushes HALT's address so RETI executes it again.
                            registers.setPC((registers.getPC() - 1) & 0xffff);
                            synchronousHaltEntryStatPhase = true;
                            asynchronousHaltEntryStatPhase = false;
                            ordinaryHaltWakeStatPhase = false;
                            state = State.OPCODE;
                            markDebugRetirement();
                            return;
                        }
                        if (interruptManager.isHaltBug()) {
                            if (timer != null) {
                                timer.onHaltBug();
                            }
                            state = State.OPCODE;
                            haltBugMode = true;
                            synchronousHaltEntryStatPhase = true;
                            asynchronousHaltEntryStatPhase = false;
                            ordinaryHaltWakeStatPhase = false;
                            markDebugRetirement();
                            return;
                        } else {
                            state = State.HALTED;
                            synchronousHaltEntryStatPhase = false;
                            asynchronousHaltEntryStatPhase = false;
                            ordinaryHaltWakeStatPhase = false;
                            haltedCpuCycles = 0;
                            haltEntrySampleTicks = speedMode.isGbc() ? 2 : 4;
                            markDebugRetirement();
                            return;
                        }
                    }

                    int opCount = currentOpCount;
                    if (opIndex < opCount) {
                        boolean opAccessesMemory = currentOpAccessesMemory[opIndex];
                        if (accessedMemory && opAccessesMemory) {
                            return;
                        }
                        Op op = currentExecutionOps[opIndex];
                        opIndex++;

                        SpriteBug.CorruptionType corruptionType = op.causesOemBug(registers, opContext);
                        if (corruptionType != null) {
                            handleSpriteBug(corruptionType);
                        }
                        opContext = op.execute(registers, addressSpace, operand, opContext);
                        op.switchInterrupts(interruptManager);

                        if (!op.proceed(registers)) {
                            opIndex = opCount;
                            break;
                        }

                        if (op.forceFinishCycle()) {
                            return;
                        }

                        if (opAccessesMemory) {
                            accessedMemory = true;
                        }
                    }

                    if (opIndex >= opCount) {
                        state = State.OPCODE;
                        operandIndex = 0;
                        interruptManager.onInstructionFinished();
                        markDebugRetirement();
                        return;
                    }
                    break;

                case HALTED:
                case STOPPED:
                case SPEED_SWITCH:
                    return;
            }
        }
    }

    /** PERFORMANCE fetch/decode front end for the primitive direct tier. */
    private int tickPerformanceEpochInstructionPipelineAtMachineCycle(int remainingMasterTicks) {
        int pc = registers.getPC();
        switch (state) {
            case OPCODE:
                clearState();
                opcode1 = readPerformanceEpochInstructionByte(pc);
                if (opcode1 == 0xcb) {
                    state = State.EXT_OPCODE;
                } else if (opcode1 == 0x10) {
                    setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                    state = State.EXT_OPCODE;
                } else {
                    state = State.OPERAND;
                    setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                    if (currentOpcode == null) {
                        state = State.LOCKED;
                        markDebugRetirement();
                        return 0;
                    }
                }
                registers.incrementPC();
                if (executePerformanceDirectBaseOpcode(opcode1)) {
                    return 0;
                }
                int directTailTicks = executePerformanceDirectWholeInstruction(
                        opcode1, remainingMasterTicks);
                if (directTailTicks >= 0) {
                    return directTailTicks;
                }
                tickPerformanceEpochContinuationAtMachineCycle(true);
                return 0;

            case EXT_OPCODE:
                opcode2 = readPerformanceEpochInstructionByte(pc);
                if (currentOpcode == null) {
                    setCurrentOpcode(Opcodes.EXT_COMMANDS.get(opcode2));
                }
                if (currentOpcode == null) {
                    state = State.LOCKED;
                    markDebugRetirement();
                    return 0;
                }
                state = State.OPERAND;
                registers.incrementPC();
                if (opcode1 == 0xcb
                        && executePerformanceDirectExtendedOpcode(opcode2)) {
                    return 0;
                }
                tickPerformanceEpochContinuationAtMachineCycle(true);
                return 0;

            case OPERAND:
                boolean fetchedOperand = false;
                if (operandIndex < currentOperandLength) {
                    operand[operandIndex++] = readPerformanceEpochInstructionByte(pc);
                    registers.incrementPC();
                    fetchedOperand = true;
                    if (operandIndex < currentOperandLength) {
                        return 0;
                    }
                }
                if (executePerformanceDirectOperandOpcode(opcode1)) {
                    return 0;
                }
                tickPerformanceEpochContinuationAtMachineCycle(fetchedOperand);
                return 0;

            case RUNNING:
                tickPerformanceEpochContinuationAtMachineCycle(false);
                return 0;

            default:
                markPerformanceEpochTerminal();
                return 0;
        }
    }

    /** Normal-speed fetch/decode front end for the fixed four-dot direct tier. */
    private int tickPerformancePhysicalDmgEpochInstructionPipelineAtMachineCycle(
            int remainingMasterTicks) {
        int pc = registers.getPC();
        switch (state) {
            case OPCODE:
                clearState();
                opcode1 = readInstructionByte(pc);
                if (opcode1 == 0xcb) {
                    state = State.EXT_OPCODE;
                } else if (opcode1 == 0x10) {
                    setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                    state = State.EXT_OPCODE;
                } else {
                    state = State.OPERAND;
                    setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                    if (currentOpcode == null) {
                        state = State.LOCKED;
                        markDebugRetirement();
                        return 0;
                    }
                }
                registers.incrementPC();
                if (executePerformancePhysicalDmgDirectBaseOpcode(opcode1)) {
                    return 0;
                }
                int directTailTicks = executePerformancePhysicalDmgDirectWholeInstruction(
                        opcode1, remainingMasterTicks);
                if (directTailTicks >= 0) {
                    return directTailTicks;
                }
                tickPerformanceEpochContinuationAtMachineCycle(true);
                return 0;

            case EXT_OPCODE:
                opcode2 = readInstructionByte(pc);
                if (currentOpcode == null) {
                    setCurrentOpcode(Opcodes.EXT_COMMANDS.get(opcode2));
                }
                if (currentOpcode == null) {
                    state = State.LOCKED;
                    markDebugRetirement();
                    return 0;
                }
                state = State.OPERAND;
                registers.incrementPC();
                if (opcode1 == 0xcb
                        && executePerformancePhysicalDmgDirectExtendedOpcode(opcode2)) {
                    return 0;
                }
                tickPerformanceEpochContinuationAtMachineCycle(true);
                return 0;

            case OPERAND:
                boolean fetchedOperand = false;
                if (operandIndex < currentOperandLength) {
                    operand[operandIndex++] = readInstructionByte(pc);
                    registers.incrementPC();
                    fetchedOperand = true;
                    if (operandIndex < currentOperandLength) {
                        return 0;
                    }
                }
                if (executePerformancePhysicalDmgDirectOperandOpcode(opcode1)) {
                    return 0;
                }
                tickPerformanceEpochContinuationAtMachineCycle(fetchedOperand);
                return 0;

            case RUNNING:
                tickPerformanceEpochContinuationAtMachineCycle(false);
                return 0;

            default:
                markPerformanceEpochTerminal();
                return 0;
        }
    }

    /**
     * Runs the decoded instruction pipeline for one machine-cycle boundary. The
     * caller supplies whether an authoritative fetch already consumed this
     * boundary's bus slot.
     */
    private void tickPerformanceEpochContinuationAtMachineCycle(boolean accessedMemory) {
        while (true) {
            int pc = registers.getPC();
            switch (state) {
                case OPCODE:
                    boolean useHdmaHaltPrefetch = haltOpcodePrefetchValid;
                    boolean useSpeedSwitchPadding = speedSwitchPaddingReplayValid;
                    boolean useHdmaArbitrationOpcode = hdmaArbitrationOpcodeValid;
                    haltOpcodePrefetchValid = false;
                    speedSwitchPaddingReplayValid = false;
                    hdmaArbitrationOpcodeValid = false;
                    if (useHdmaHaltPrefetch) {
                        // A request acknowledged by HALT owns the bus before the held
                        // opcode may run, just like the ordinary DMA prefetch below.
                        hdmaOpcodePrefetched = true;
                    }
                    beginDebugInstruction(pc);
                    clearState();
                    opcode1 = useHdmaHaltPrefetch
                            ? haltPrefetchedOpcode
                            : useSpeedSwitchPadding
                            ? speedSwitchPaddingOpcode
                            : useHdmaArbitrationOpcode
                            ? hdmaArbitrationOpcode
                            : readInstructionByte(pc);
                    accessedMemory = true;
                    notifyOpcodeFetched(pc, false, opcode1);
                    if (opcode1 == 0xcb) {
                        state = State.EXT_OPCODE;
                    } else if (opcode1 == 0x10) {
                        setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                        state = State.EXT_OPCODE;
                    } else {
                        state = State.OPERAND;
                        setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
                        if (currentOpcode == null) {
                            // an illegal opcode freezes the CPU on hardware; do the
                            // same instead of crashing the emulation thread
                            state = State.LOCKED;
                            markDebugRetirement();
                            return;
                        }
                    }
                    if (useHdmaHaltPrefetch || useSpeedSwitchPadding) {
                        // HALT samples the next opcode without advancing PC. If an
                        // already-latched HDMA request takes the bus, that sampled byte
                        // is consumed after wake while PC still addresses the same byte.
                        // STOP's padding byte has already advanced PC, so its held replay
                        // likewise must not advance the counter a second time.
                    } else if (!haltBugMode) {
                        registers.incrementPC();
                    } else {
                        haltBugMode = false;
                    }
                    break;

                case EXT_OPCODE:
                    if (accessedMemory) {
                        return;
                    }
                    accessedMemory = true;
                    opcode2 = readInstructionByte(pc);
                    if (opcode1 == 0xcb) {
                        notifyOpcodeFetched(debugInstructionPc, true, opcode2);
                    }
                    if (currentOpcode == null) {
                        setCurrentOpcode(Opcodes.EXT_COMMANDS.get(opcode2));
                    }
                    if (currentOpcode == null) {
                        state = State.LOCKED;
                        markDebugRetirement();
                        return;
                    }
                    state = State.OPERAND;
                    registers.incrementPC();
                    break;

                case OPERAND:
                    while (operandIndex < currentOperandLength) {
                        if (accessedMemory) {
                            return;
                        }
                        accessedMemory = true;
                        operand[operandIndex++] = readInstructionByte(pc);
                        registers.incrementPC();
                    }
                    ops = currentOpcode.getOps();
                    state = State.RUNNING;
                    break;

                case RUNNING:
                    if (opcode1 == 0x10) {
                        boolean exitByJoypad = isJoypadLineLow();
                        if (!exitByJoypad && speedMode.onStop()) {
                            if (timer != null) {
                                timer.onSpeedSwitch();
                            }
                            // A CGB speed switch resets and freezes DIV while the CPU clock
                            // is stopped. The PPU remains in its independent clock domain.
                            addressSpace.setByte(0xff04, 0);
                            speedSwitchTicks = SPEED_SWITCH_DELAY;
                            state = State.SPEED_SWITCH;
                        } else if (exitByJoypad) {
                            // A selected, asserted P10-P13 input wins over KEY1 and makes
                            // STOP exit immediately. In particular, do not consume the
                            // pending speed-switch request in this case.
                            state = State.OPCODE;
                        } else {
                            state = State.STOPPED;
                            // A stopped DMG drives color 0 (white). A CGB outside mode 3
                            // loses VRAM access and drives color 0 (black); during mode 3 it
                            // retains the picture that is already being scanned out.
                            if (gpu == null || !gpu.isGbc() || gpu.getMode() != Mode.PixelTransfer) {
                                stopFrameBlankRequested = true;
                            }
                            display.disableLcd();
                        }
                        markDebugRetirement();
                        return;
                    } else if (opcode1 == 0x76) {
                        // HALT always samples the next opcode. It is normally fetched
                        // again on wake, but a simultaneously acknowledged HDMA request
                        // turns this sample into the held pipeline opcode.
                        haltPrefetchedOpcode = readInstructionByte(registers.getPC());
                        haltOpcodePrefetchValid = false;
                        // committing a pending EI happens even when entering halt, so
                        // "ei; halt" halts with IME=1 (no halt bug, wake dispatches)
                        boolean imeBeforeHalt = interruptManager.isIme();
                        boolean interruptPendingBeforeHalt = interruptManager.isInterruptRequestedForHalt();
                        interruptManager.onInstructionFinished();
                        if (!imeBeforeHalt && interruptPendingBeforeHalt && interruptManager.isIme()) {
                            // HALT was fetched while EI's delayed enable was still pending.
                            // The interrupt is accepted at instruction completion, but hardware
                            // pushes HALT's address so RETI executes it again.
                            registers.setPC((registers.getPC() - 1) & 0xffff);
                            synchronousHaltEntryStatPhase = true;
                            asynchronousHaltEntryStatPhase = false;
                            ordinaryHaltWakeStatPhase = false;
                            state = State.OPCODE;
                            markDebugRetirement();
                            return;
                        }
                        if (interruptManager.isHaltBug()) {
                            if (timer != null) {
                                timer.onHaltBug();
                            }
                            state = State.OPCODE;
                            haltBugMode = true;
                            synchronousHaltEntryStatPhase = true;
                            asynchronousHaltEntryStatPhase = false;
                            ordinaryHaltWakeStatPhase = false;
                            markDebugRetirement();
                            return;
                        } else {
                            state = State.HALTED;
                            synchronousHaltEntryStatPhase = false;
                            asynchronousHaltEntryStatPhase = false;
                            ordinaryHaltWakeStatPhase = false;
                            haltedCpuCycles = 0;
                            haltEntrySampleTicks = speedMode.isGbc() ? 2 : 4;
                            markDebugRetirement();
                            return;
                        }
                    }

                    int opCount = currentOpCount;
                    if (opIndex < opCount) {
                        boolean opAccessesMemory = currentOpAccessesMemory[opIndex];
                        if (accessedMemory && opAccessesMemory) {
                            return;
                        }
                        Op op = currentExecutionOps[opIndex];
                        opIndex++;

                        SpriteBug.CorruptionType corruptionType = op.causesOemBug(registers, opContext);
                        if (corruptionType != null) {
                            if (!speedMode.isGbc()) {
                                // Physical DMG OAM corruption observes the live PPU reader at
                                // this CPU boundary. Publish the frozen prefix before mutating
                                // OAM, then end the packet after this exact machine cycle.
                                flushPerformanceEpochPrefix();
                                markPerformanceEpochTerminal();
                            }
                            handleSpriteBug(corruptionType);
                        }
                        opContext = op.execute(registers, addressSpace, operand, opContext);
                        op.switchInterrupts(interruptManager);

                        if (!op.proceed(registers)) {
                            opIndex = opCount;
                            break;
                        }

                        if (op.forceFinishCycle()) {
                            return;
                        }

                        if (opAccessesMemory) {
                            accessedMemory = true;
                        }
                    }

                    if (opIndex >= opCount) {
                        state = State.OPCODE;
                        operandIndex = 0;
                        interruptManager.onInstructionFinished();
                        markDebugRetirement();
                        return;
                    }
                    break;

                case HALTED:
                case STOPPED:
                case SPEED_SWITCH:
                    return;
            }
        }
    }

    /**
     * Executes the high-frequency one-cycle register-only base opcodes without
     * walking their anonymous {@link Op} chains. The authoritative opcode fetch,
     * decode, PC update, and illegal-opcode handling have already happened.
     */
    private boolean executePerformanceDirectBaseOpcode(int opcode) {
        if (opcode == 0x00) {
            finishPerformanceDirectInstruction(0);
            return true;
        }

        int register = (opcode >>> 3) & 0x07;
        if ((opcode & 0xc7) == 0x04 && register != 6) {
            int value = readPerformanceRegister(register);
            int result = (value + 1) & 0xff;
            int flags = registers.getFlags().getFlagsByte() & FLAG_C;
            if (result == 0) {
                flags |= FLAG_Z;
            }
            if ((value & 0x0f) == 0x0f) {
                flags |= FLAG_H;
            }
            registers.getFlags().setFlagsByteTrusted(flags);
            writePerformanceRegister(register, result);
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if ((opcode & 0xc7) == 0x05 && register != 6) {
            int value = readPerformanceRegister(register);
            int result = (value - 1) & 0xff;
            int flags = (registers.getFlags().getFlagsByte() & FLAG_C) | FLAG_N;
            if (result == 0) {
                flags |= FLAG_Z;
            }
            if ((value & 0x0f) == 0) {
                flags |= FLAG_H;
            }
            registers.getFlags().setFlagsByteTrusted(flags);
            writePerformanceRegister(register, result);
            finishPerformanceDirectInstruction(result);
            return true;
        }

        if (opcode == 0x07 || opcode == 0x0f || opcode == 0x17 || opcode == 0x1f) {
            int value = registers.getA();
            int oldFlags = registers.getFlags().getFlagsByte();
            int result;
            int carry;
            switch (opcode) {
                case 0x07 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | carry) & 0xff;
                }
                case 0x0f -> {
                    carry = value & 1;
                    result = (value >>> 1) | (carry << 7);
                }
                case 0x17 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | ((oldFlags & FLAG_C) >>> 4)) & 0xff;
                }
                default -> {
                    carry = value & 1;
                    result = (value >>> 1) | ((oldFlags & FLAG_C) << 3);
                }
            }
            registers.setATrusted(result);
            registers.getFlags().setFlagsByteTrusted(carry == 0 ? 0 : FLAG_C);
            finishPerformanceDirectInstruction(result);
            return true;
        }

        if (opcode == 0x27) {
            int value = registers.getA();
            int oldFlags = registers.getFlags().getFlagsByte();
            boolean subtract = (oldFlags & FLAG_N) != 0;
            boolean carry = (oldFlags & FLAG_C) != 0;
            int result = value;
            if (subtract) {
                if ((oldFlags & FLAG_H) != 0) {
                    result = (result - 0x06) & 0xff;
                }
                if (carry) {
                    result = (result - 0x60) & 0xff;
                }
            } else {
                if ((oldFlags & FLAG_H) != 0 || (result & 0x0f) > 9) {
                    result += 0x06;
                }
                if (carry || result > 0x9f) {
                    result += 0x60;
                    carry = true;
                }
            }
            result &= 0xff;
            int flags = subtract ? FLAG_N : 0;
            if (result == 0) {
                flags |= FLAG_Z;
            }
            if (carry) {
                flags |= FLAG_C;
            }
            registers.setATrusted(result);
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if (opcode == 0x2f) {
            int result = (~registers.getA()) & 0xff;
            int flags = (registers.getFlags().getFlagsByte() & (FLAG_Z | FLAG_C))
                    | FLAG_N | FLAG_H;
            registers.setATrusted(result);
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if (opcode == 0x37) {
            int flags = (registers.getFlags().getFlagsByte() & FLAG_Z) | FLAG_C;
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(registers.getA());
            return true;
        }
        if (opcode == 0x3f) {
            int oldFlags = registers.getFlags().getFlagsByte();
            int flags = oldFlags & FLAG_Z;
            if ((oldFlags & FLAG_C) == 0) {
                flags |= FLAG_C;
            }
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(registers.getA());
            return true;
        }

        if (opcode >= 0x40 && opcode <= 0x7f && opcode != 0x76) {
            int destination = (opcode >>> 3) & 0x07;
            int source = opcode & 0x07;
            if (destination != 6 && source != 6) {
                int value = readPerformanceRegister(source);
                writePerformanceRegister(destination, value);
                finishPerformanceDirectInstruction(value);
                return true;
            }
        }
        if (opcode >= 0x80 && opcode <= 0xbf && (opcode & 0x07) != 6) {
            int result = executePerformanceAlu((opcode >>> 3) & 0x07,
                    readPerformanceRegister(opcode & 0x07));
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if (opcode == 0xe9) {
            int target = registers.getHL();
            registers.setPCTrusted(target);
            finishPerformanceDirectInstruction(target);
            return true;
        }
        return false;
    }

    /** Physical-DMG copy keeps the native-CGB direct helper single-caller and inlineable. */
    private boolean executePerformancePhysicalDmgDirectBaseOpcode(int opcode) {
        if (opcode == 0x00) {
            finishPerformanceDirectInstruction(0);
            return true;
        }

        int register = (opcode >>> 3) & 0x07;
        if ((opcode & 0xc7) == 0x04 && register != 6) {
            int value = readPerformanceRegister(register);
            int result = (value + 1) & 0xff;
            int flags = registers.getFlags().getFlagsByte() & FLAG_C;
            if (result == 0) {
                flags |= FLAG_Z;
            }
            if ((value & 0x0f) == 0x0f) {
                flags |= FLAG_H;
            }
            registers.getFlags().setFlagsByteTrusted(flags);
            writePerformanceRegister(register, result);
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if ((opcode & 0xc7) == 0x05 && register != 6) {
            int value = readPerformanceRegister(register);
            int result = (value - 1) & 0xff;
            int flags = (registers.getFlags().getFlagsByte() & FLAG_C) | FLAG_N;
            if (result == 0) {
                flags |= FLAG_Z;
            }
            if ((value & 0x0f) == 0) {
                flags |= FLAG_H;
            }
            registers.getFlags().setFlagsByteTrusted(flags);
            writePerformanceRegister(register, result);
            finishPerformanceDirectInstruction(result);
            return true;
        }

        if (opcode == 0x07 || opcode == 0x0f || opcode == 0x17 || opcode == 0x1f) {
            int value = registers.getA();
            int oldFlags = registers.getFlags().getFlagsByte();
            int result;
            int carry;
            switch (opcode) {
                case 0x07 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | carry) & 0xff;
                }
                case 0x0f -> {
                    carry = value & 1;
                    result = (value >>> 1) | (carry << 7);
                }
                case 0x17 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | ((oldFlags & FLAG_C) >>> 4)) & 0xff;
                }
                default -> {
                    carry = value & 1;
                    result = (value >>> 1) | ((oldFlags & FLAG_C) << 3);
                }
            }
            registers.setATrusted(result);
            registers.getFlags().setFlagsByteTrusted(carry == 0 ? 0 : FLAG_C);
            finishPerformanceDirectInstruction(result);
            return true;
        }

        if (opcode == 0x27) {
            int value = registers.getA();
            int oldFlags = registers.getFlags().getFlagsByte();
            boolean subtract = (oldFlags & FLAG_N) != 0;
            boolean carry = (oldFlags & FLAG_C) != 0;
            int result = value;
            if (subtract) {
                if ((oldFlags & FLAG_H) != 0) {
                    result = (result - 0x06) & 0xff;
                }
                if (carry) {
                    result = (result - 0x60) & 0xff;
                }
            } else {
                if ((oldFlags & FLAG_H) != 0 || (result & 0x0f) > 9) {
                    result += 0x06;
                }
                if (carry || result > 0x9f) {
                    result += 0x60;
                    carry = true;
                }
            }
            result &= 0xff;
            int flags = subtract ? FLAG_N : 0;
            if (result == 0) {
                flags |= FLAG_Z;
            }
            if (carry) {
                flags |= FLAG_C;
            }
            registers.setATrusted(result);
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if (opcode == 0x2f) {
            int result = (~registers.getA()) & 0xff;
            int flags = (registers.getFlags().getFlagsByte() & (FLAG_Z | FLAG_C))
                    | FLAG_N | FLAG_H;
            registers.setATrusted(result);
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if (opcode == 0x37) {
            int flags = (registers.getFlags().getFlagsByte() & FLAG_Z) | FLAG_C;
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(registers.getA());
            return true;
        }
        if (opcode == 0x3f) {
            int oldFlags = registers.getFlags().getFlagsByte();
            int flags = oldFlags & FLAG_Z;
            if ((oldFlags & FLAG_C) == 0) {
                flags |= FLAG_C;
            }
            registers.getFlags().setFlagsByteTrusted(flags);
            finishPerformanceDirectInstruction(registers.getA());
            return true;
        }

        if (opcode >= 0x40 && opcode <= 0x7f && opcode != 0x76) {
            int destination = (opcode >>> 3) & 0x07;
            int source = opcode & 0x07;
            if (destination != 6 && source != 6) {
                int value = readPerformanceRegister(source);
                writePerformanceRegister(destination, value);
                finishPerformanceDirectInstruction(value);
                return true;
            }
        }
        if (opcode >= 0x80 && opcode <= 0xbf && (opcode & 0x07) != 6) {
            int result = executePerformanceAlu((opcode >>> 3) & 0x07,
                    readPerformanceRegister(opcode & 0x07));
            finishPerformanceDirectInstruction(result);
            return true;
        }
        if (opcode == 0xe9) {
            int target = registers.getHL();
            registers.setPCTrusted(target);
            finishPerformanceDirectInstruction(target);
            return true;
        }
        return false;
    }

    /** Direct final-operand suffix for register/immediate and untaken branch forms. */
    private boolean executePerformanceDirectOperandOpcode(int opcode) {
        if ((opcode & 0xcf) == 0x01) {
            int value = operand[0] | operand[1] << 8;
            switch ((opcode >>> 4) & 0x03) {
                case 0 -> registers.setBCTrusted(value);
                case 1 -> registers.setDETrusted(value);
                case 2 -> registers.setHLTrusted(value);
                default -> registers.setSPTrusted(value);
            }
            finishPerformanceDirectInstruction(value);
            return true;
        }

        int register = (opcode >>> 3) & 0x07;
        if ((opcode & 0xc7) == 0x06 && register != 6) {
            int value = operand[0];
            writePerformanceRegister(register, value);
            finishPerformanceDirectInstruction(value);
            return true;
        }
        if ((opcode & 0xc7) == 0xc6) {
            int result = executePerformanceAlu((opcode >>> 3) & 0x07, operand[0]);
            finishPerformanceDirectInstruction(result);
            return true;
        }

        if ((opcode & 0xe7) == 0x20) { // JR cc,r8
            int condition = (opcode >>> 3) & 0x03;
            if (!performanceConditionHolds(condition)) {
                finishPerformanceDirectInstruction(registers.getPC());
                return true;
            }
        } else if ((opcode & 0xe7) == 0xc2) { // JP cc,a16
            int condition = (opcode >>> 3) & 0x03;
            if (!performanceConditionHolds(condition)) {
                finishPerformanceDirectInstruction(operand[0] | operand[1] << 8);
                return true;
            }
        } else if ((opcode & 0xe7) == 0xc4) { // CALL cc,a16
            int condition = (opcode >>> 3) & 0x03;
            if (!performanceConditionHolds(condition)) {
                finishPerformanceDirectInstruction(0);
                return true;
            }
        }
        return false;
    }

    /** Physical-DMG copy keeps the native-CGB operand helper single-caller and inlineable. */
    private boolean executePerformancePhysicalDmgDirectOperandOpcode(int opcode) {
        if ((opcode & 0xcf) == 0x01) {
            int value = operand[0] | operand[1] << 8;
            switch ((opcode >>> 4) & 0x03) {
                case 0 -> registers.setBCTrusted(value);
                case 1 -> registers.setDETrusted(value);
                case 2 -> registers.setHLTrusted(value);
                default -> registers.setSPTrusted(value);
            }
            finishPerformanceDirectInstruction(value);
            return true;
        }

        int register = (opcode >>> 3) & 0x07;
        if ((opcode & 0xc7) == 0x06 && register != 6) {
            int value = operand[0];
            writePerformanceRegister(register, value);
            finishPerformanceDirectInstruction(value);
            return true;
        }
        if ((opcode & 0xc7) == 0xc6) {
            int result = executePerformanceAlu((opcode >>> 3) & 0x07, operand[0]);
            finishPerformanceDirectInstruction(result);
            return true;
        }

        if ((opcode & 0xe7) == 0x20) { // JR cc,r8
            int condition = (opcode >>> 3) & 0x03;
            if (!performanceConditionHolds(condition)) {
                finishPerformanceDirectInstruction(registers.getPC());
                return true;
            }
        } else if ((opcode & 0xe7) == 0xc2) { // JP cc,a16
            int condition = (opcode >>> 3) & 0x03;
            if (!performanceConditionHolds(condition)) {
                finishPerformanceDirectInstruction(operand[0] | operand[1] << 8);
                return true;
            }
        } else if ((opcode & 0xe7) == 0xc4) { // CALL cc,a16
            int condition = (opcode >>> 3) & 0x03;
            if (!performanceConditionHolds(condition)) {
                finishPerformanceDirectInstruction(0);
                return true;
            }
        }
        return false;
    }

    /**
     * Executes a complete high-frequency instruction transaction after its real
     * opcode fetch. Each additional machine-cycle boundary is charged at native
     * CGB's fixed two-master-dot width. Instruction bytes are fetched in program
     * order through the epoch's borrowed ROM mapping when available; an unsafe data
     * access is left in the canonical RUNNING state for the ordinary epoch sequencer.
     *
     * @return additional master ticks, or {@code -1} when this opcode/budget is not
     *         admitted by the complete-instruction tier
     */
    private int executePerformanceDirectWholeInstruction(
            int opcode, int remainingMasterTicks) {
        int machineCycles = performanceDirectWholeMachineCycles(opcode);
        if (machineCycles == 0
                || remainingMasterTicks < 1 + 2 * (machineCycles - 1)) {
            return -1;
        }
        int operandLength = currentOperandLength;
        int operandPc = registers.getPC();
        for (int i = 0; i < operandLength; i++) {
            if (!PerformanceEpochBus.isSafeRead(operandPc + i)) {
                return -1;
            }
        }

        int extraTicks = 0;
        while (operandIndex < operandLength) {
            performanceEpochElapsed += 2;
            extraTicks += 2;
            operand[operandIndex++] = readPerformanceEpochInstructionByte(registers.getPC());
            registers.incrementPC();
        }
        ops = currentOpcode.getOps();
        state = State.RUNNING;

        if (opcode == 0x18 || (opcode & 0xe7) == 0x20) {
            int pc = registers.getPC();
            if (opcode != 0x18
                    && !performanceConditionHolds((opcode >>> 3) & 0x03)) {
                finishPerformanceDirectInstruction(pc);
                return extraTicks;
            }
            int target = (pc + (byte) operand[0]) & 0xffff;
            opContext = target;
            opIndex = opcode == 0x18 ? 2 : 3;
            performanceEpochElapsed += 2;
            extraTicks += 2;
            registers.setPCTrusted(target);
            finishPerformanceDirectInstruction(target);
            return extraTicks;
        }

        if (opcode == 0xc3 || (opcode & 0xe7) == 0xc2) {
            int target = operand[0] | operand[1] << 8;
            if (opcode != 0xc3
                    && !performanceConditionHolds((opcode >>> 3) & 0x03)) {
                finishPerformanceDirectInstruction(target);
                return extraTicks;
            }
            registers.setPCTrusted(target);
            opContext = target;
            opIndex = opcode == 0xc3 ? 2 : 3;
            performanceEpochElapsed += 2;
            extraTicks += 2;
            finishPerformanceDirectInstruction(target);
            return extraTicks;
        }

        if ((opcode & 0xcf) == 0x03 || (opcode & 0xcf) == 0x0b) {
            int pair = (opcode >>> 4) & 0x03;
            int value = readPerformanceRegisterPair(pair);
            if (isPerformanceOamArea(value)) {
                return -1;
            }
            int result = (value + ((opcode & 0x08) == 0 ? 1 : -1)) & 0xffff;
            opContext = result;
            opIndex = 2;
            performanceEpochElapsed += 2;
            extraTicks += 2;
            writePerformanceRegisterPair(pair, result);
            finishPerformanceDirectInstruction(result);
            return extraTicks;
        }

        if ((opcode & 0xcf) == 0x09) {
            int left = registers.getHL();
            int right = readPerformanceRegisterPair((opcode >>> 4) & 0x03);
            int sum = left + right;
            int flags = registers.getFlags().getFlagsByte() & FLAG_Z;
            if ((left & 0x0fff) + (right & 0x0fff) > 0x0fff) {
                flags |= FLAG_H;
            }
            if (sum > 0xffff) {
                flags |= FLAG_C;
            }
            int result = sum & 0xffff;
            registers.getFlags().setFlagsByteTrusted(flags);
            opContext = result;
            opIndex = 2;
            performanceEpochElapsed += 2;
            extraTicks += 2;
            registers.setHLTrusted(result);
            finishPerformanceDirectInstruction(result);
            return extraTicks;
        }

        if (opcode == 0xf9) {
            int value = registers.getHL();
            registers.setSPTrusted(value);
            opContext = value;
            opIndex = 2;
            performanceEpochElapsed += 2;
            extraTicks += 2;
            finishPerformanceDirectInstruction(value);
            return extraTicks;
        }

        int address;
        boolean read;
        int context;
        int runningOp;
        if (opcode == 0x02 || opcode == 0x12) {
            address = opcode == 0x02 ? registers.getBC() : registers.getDE();
            read = false;
            context = registers.getA();
            runningOp = 1;
        } else if (opcode == 0x0a || opcode == 0x1a) {
            address = opcode == 0x0a ? registers.getBC() : registers.getDE();
            read = true;
            context = 0;
            runningOp = 0;
        } else if (opcode == 0x22 || opcode == 0x32) {
            address = registers.getHL();
            if (isPerformanceOamArea(address)) {
                return -1;
            }
            read = false;
            context = registers.getA();
            runningOp = 1;
        } else if (opcode == 0x2a || opcode == 0x3a) {
            address = registers.getHL();
            if (isPerformanceOamArea(address)) {
                return -1;
            }
            read = true;
            context = 0;
            runningOp = 0;
        } else if (opcode == 0x34 || opcode == 0x35) {
            address = registers.getHL();
            read = true;
            context = 0;
            runningOp = 0;
        } else if (opcode >= 0x40 && opcode <= 0x7f && opcode != 0x76) {
            int destination = (opcode >>> 3) & 0x07;
            int source = opcode & 0x07;
            address = registers.getHL();
            read = source == 6;
            context = read ? 0 : readPerformanceRegister(source);
            runningOp = read ? 0 : 1;
        } else if (opcode >= 0x80 && opcode <= 0xbf && (opcode & 0x07) == 6) {
            address = registers.getHL();
            read = true;
            context = registers.getA();
            runningOp = 1;
        } else if (opcode == 0x36) {
            address = registers.getHL();
            read = false;
            context = operand[0];
            runningOp = 1;
        } else if (opcode == 0xe0 || opcode == 0xf0) {
            address = 0xff00 | operand[0];
            read = opcode == 0xf0;
            context = read ? 0 : registers.getA();
            runningOp = read ? 0 : 1;
        } else if (opcode == 0xe2 || opcode == 0xf2) {
            address = 0xff00 | registers.getC();
            read = opcode == 0xf2;
            context = read ? 0 : registers.getA();
            runningOp = read ? 0 : 1;
        } else if (opcode == 0xea || opcode == 0xfa) {
            address = operand[0] | operand[1] << 8;
            read = opcode == 0xfa;
            context = read ? 0 : registers.getA();
            runningOp = read ? 0 : 1;
        } else {
            return -1;
        }

        opContext = context;
        opIndex = runningOp;
        if (read ? !PerformanceEpochBus.isSafeRead(address)
                : !PerformanceEpochBus.isSafeWrite(address)) {
            return extraTicks;
        }
        performanceEpochElapsed += 2;
        extraTicks += 2;

        if (!read) {
            addressSpace.setByte(address, context);
            if (opcode == 0x22 || opcode == 0x32) {
                int result = (registers.getHL() + (opcode == 0x22 ? 1 : -1)) & 0xffff;
                registers.setHLTrusted(result);
                finishPerformanceDirectInstruction(result);
            } else {
                finishPerformanceDirectInstruction(context);
            }
            return extraTicks;
        }

        int value = addressSpace.getByte(address);
        if (opcode == 0x0a || opcode == 0x1a || opcode == 0xf0
                || opcode == 0xf2 || opcode == 0xfa) {
            registers.setATrusted(value);
            finishPerformanceDirectInstruction(value);
        } else if (opcode == 0x2a || opcode == 0x3a) {
            registers.setATrusted(value);
            int result = (registers.getHL() + (opcode == 0x2a ? 1 : -1)) & 0xffff;
            registers.setHLTrusted(result);
            finishPerformanceDirectInstruction(result);
        } else if (opcode == 0x34 || opcode == 0x35) {
            int result = executePerformanceIncDec(value, opcode == 0x35);
            opContext = result;
            opIndex = 2;
            performanceEpochElapsed += 2;
            extraTicks += 2;
            addressSpace.setByte(address, result);
            finishPerformanceDirectInstruction(result);
        } else if (opcode >= 0x40 && opcode <= 0x7f) {
            writePerformanceRegister((opcode >>> 3) & 0x07, value);
            finishPerformanceDirectInstruction(value);
        } else {
            int result = executePerformanceAlu((opcode >>> 3) & 0x07, value);
            finishPerformanceDirectInstruction(result);
        }
        return extraTicks;
    }

    /** Fixed four-master-dot physical-DMG counterpart to the native-CGB direct tier. */
    private int executePerformancePhysicalDmgDirectWholeInstruction(
            int opcode, int remainingMasterTicks) {
        int machineCycles = performancePhysicalDmgDirectWholeMachineCycles(opcode);
        if (machineCycles == 0
                || remainingMasterTicks < 1 + 4 * (machineCycles - 1)) {
            return -1;
        }
        int operandLength = currentOperandLength;
        int operandPc = registers.getPC();
        for (int i = 0; i < operandLength; i++) {
            if (!PerformanceEpochBus.isSafeRead(operandPc + i)) {
                return -1;
            }
        }

        int extraTicks = 0;
        while (operandIndex < operandLength) {
            performanceEpochElapsed += 4;
            extraTicks += 4;
            operand[operandIndex++] = readInstructionByte(registers.getPC());
            registers.incrementPC();
        }
        ops = currentOpcode.getOps();
        state = State.RUNNING;

        if (opcode == 0x18 || (opcode & 0xe7) == 0x20) {
            int pc = registers.getPC();
            if (opcode != 0x18
                    && !performanceConditionHolds((opcode >>> 3) & 0x03)) {
                finishPerformanceDirectInstruction(pc);
                return extraTicks;
            }
            int target = (pc + (byte) operand[0]) & 0xffff;
            opContext = target;
            opIndex = opcode == 0x18 ? 2 : 3;
            performanceEpochElapsed += 4;
            extraTicks += 4;
            registers.setPCTrusted(target);
            finishPerformanceDirectInstruction(target);
            return extraTicks;
        }

        if (opcode == 0xc3 || (opcode & 0xe7) == 0xc2) {
            int target = operand[0] | operand[1] << 8;
            if (opcode != 0xc3
                    && !performanceConditionHolds((opcode >>> 3) & 0x03)) {
                finishPerformanceDirectInstruction(target);
                return extraTicks;
            }
            registers.setPCTrusted(target);
            opContext = target;
            opIndex = opcode == 0xc3 ? 2 : 3;
            performanceEpochElapsed += 4;
            extraTicks += 4;
            finishPerformanceDirectInstruction(target);
            return extraTicks;
        }

        if ((opcode & 0xcf) == 0x03 || (opcode & 0xcf) == 0x0b) {
            int pair = (opcode >>> 4) & 0x03;
            int value = readPerformancePhysicalDmgRegisterPair(pair);
            if (isPerformanceOamArea(value)) {
                return -1;
            }
            int result = (value + ((opcode & 0x08) == 0 ? 1 : -1)) & 0xffff;
            opContext = result;
            opIndex = 2;
            performanceEpochElapsed += 4;
            extraTicks += 4;
            writePerformanceRegisterPair(pair, result);
            finishPerformanceDirectInstruction(result);
            return extraTicks;
        }

        if ((opcode & 0xcf) == 0x09) {
            int left = registers.getHL();
            int right = readPerformancePhysicalDmgRegisterPair((opcode >>> 4) & 0x03);
            int sum = left + right;
            int flags = registers.getFlags().getFlagsByte() & FLAG_Z;
            if ((left & 0x0fff) + (right & 0x0fff) > 0x0fff) {
                flags |= FLAG_H;
            }
            if (sum > 0xffff) {
                flags |= FLAG_C;
            }
            int result = sum & 0xffff;
            registers.getFlags().setFlagsByteTrusted(flags);
            opContext = result;
            opIndex = 2;
            performanceEpochElapsed += 4;
            extraTicks += 4;
            registers.setHLTrusted(result);
            finishPerformanceDirectInstruction(result);
            return extraTicks;
        }

        if (opcode == 0xf9) {
            int value = registers.getHL();
            registers.setSPTrusted(value);
            opContext = value;
            opIndex = 2;
            performanceEpochElapsed += 4;
            extraTicks += 4;
            finishPerformanceDirectInstruction(value);
            return extraTicks;
        }

        int address;
        boolean read;
        int context;
        int runningOp;
        if (opcode == 0x02 || opcode == 0x12) {
            address = opcode == 0x02 ? registers.getBC() : registers.getDE();
            read = false;
            context = registers.getA();
            runningOp = 1;
        } else if (opcode == 0x0a || opcode == 0x1a) {
            address = opcode == 0x0a ? registers.getBC() : registers.getDE();
            read = true;
            context = 0;
            runningOp = 0;
        } else if (opcode == 0x22 || opcode == 0x32) {
            address = registers.getHL();
            if (isPerformanceOamArea(address)) {
                return -1;
            }
            read = false;
            context = registers.getA();
            runningOp = 1;
        } else if (opcode == 0x2a || opcode == 0x3a) {
            address = registers.getHL();
            if (isPerformanceOamArea(address)) {
                return -1;
            }
            read = true;
            context = 0;
            runningOp = 0;
        } else if (opcode == 0x34 || opcode == 0x35) {
            address = registers.getHL();
            read = true;
            context = 0;
            runningOp = 0;
        } else if (opcode >= 0x40 && opcode <= 0x7f && opcode != 0x76) {
            int destination = (opcode >>> 3) & 0x07;
            int source = opcode & 0x07;
            address = registers.getHL();
            read = source == 6;
            context = read ? 0 : readPerformanceRegister(source);
            runningOp = read ? 0 : 1;
        } else if (opcode >= 0x80 && opcode <= 0xbf && (opcode & 0x07) == 6) {
            address = registers.getHL();
            read = true;
            context = registers.getA();
            runningOp = 1;
        } else if (opcode == 0x36) {
            address = registers.getHL();
            read = false;
            context = operand[0];
            runningOp = 1;
        } else if (opcode == 0xe0 || opcode == 0xf0) {
            address = 0xff00 | operand[0];
            read = opcode == 0xf0;
            context = read ? 0 : registers.getA();
            runningOp = read ? 0 : 1;
        } else if (opcode == 0xe2 || opcode == 0xf2) {
            address = 0xff00 | registers.getC();
            read = opcode == 0xf2;
            context = read ? 0 : registers.getA();
            runningOp = read ? 0 : 1;
        } else if (opcode == 0xea || opcode == 0xfa) {
            address = operand[0] | operand[1] << 8;
            read = opcode == 0xfa;
            context = read ? 0 : registers.getA();
            runningOp = read ? 0 : 1;
        } else {
            return -1;
        }

        opContext = context;
        opIndex = runningOp;
        if (read ? !PerformanceEpochBus.isSafeRead(address)
                : !PerformanceEpochBus.isSafeWrite(address)) {
            return extraTicks;
        }
        performanceEpochElapsed += 4;
        extraTicks += 4;

        if (!read) {
            addressSpace.setByte(address, context);
            if (opcode == 0x22 || opcode == 0x32) {
                int result = (registers.getHL() + (opcode == 0x22 ? 1 : -1)) & 0xffff;
                registers.setHLTrusted(result);
                finishPerformanceDirectInstruction(result);
            } else {
                finishPerformanceDirectInstruction(context);
            }
            return extraTicks;
        }

        int value = addressSpace.getByte(address);
        if (opcode == 0x0a || opcode == 0x1a || opcode == 0xf0
                || opcode == 0xf2 || opcode == 0xfa) {
            registers.setATrusted(value);
            finishPerformanceDirectInstruction(value);
        } else if (opcode == 0x2a || opcode == 0x3a) {
            registers.setATrusted(value);
            int result = (registers.getHL() + (opcode == 0x2a ? 1 : -1)) & 0xffff;
            registers.setHLTrusted(result);
            finishPerformanceDirectInstruction(result);
        } else if (opcode == 0x34 || opcode == 0x35) {
            int result = executePerformancePhysicalDmgIncDec(value, opcode == 0x35);
            opContext = result;
            opIndex = 2;
            performanceEpochElapsed += 4;
            extraTicks += 4;
            addressSpace.setByte(address, result);
            finishPerformanceDirectInstruction(result);
        } else if (opcode >= 0x40 && opcode <= 0x7f) {
            writePerformanceRegister((opcode >>> 3) & 0x07, value);
            finishPerformanceDirectInstruction(value);
        } else {
            int result = executePerformanceAlu((opcode >>> 3) & 0x07, value);
            finishPerformanceDirectInstruction(result);
        }
        return extraTicks;
    }

    private int performanceDirectWholeMachineCycles(int opcode) {
        if (opcode == 0x18) {
            return 3;
        }
        if ((opcode & 0xe7) == 0x20) {
            return performanceConditionHolds((opcode >>> 3) & 0x03) ? 3 : 2;
        }
        if (opcode == 0xc3) {
            return 4;
        }
        if ((opcode & 0xe7) == 0xc2) {
            return performanceConditionHolds((opcode >>> 3) & 0x03) ? 4 : 3;
        }
        if ((opcode & 0xcf) == 0x03 || (opcode & 0xcf) == 0x0b
                || (opcode & 0xcf) == 0x09 || opcode == 0xf9
                || opcode == 0x02 || opcode == 0x12 || opcode == 0x0a || opcode == 0x1a
                || opcode == 0x22 || opcode == 0x2a || opcode == 0x32 || opcode == 0x3a
                || opcode >= 0x40 && opcode <= 0x7f && opcode != 0x76
                        && (((opcode >>> 3) & 0x07) == 6 || (opcode & 0x07) == 6)
                || opcode >= 0x80 && opcode <= 0xbf && (opcode & 0x07) == 6
                || opcode == 0xe2 || opcode == 0xf2) {
            return 2;
        }
        if (opcode == 0x34 || opcode == 0x35 || opcode == 0x36
                || opcode == 0xe0 || opcode == 0xf0) {
            return 3;
        }
        if (opcode == 0xea || opcode == 0xfa) {
            return 4;
        }
        return 0;
    }

    private int performancePhysicalDmgDirectWholeMachineCycles(int opcode) {
        if (opcode == 0x18) {
            return 3;
        }
        if ((opcode & 0xe7) == 0x20) {
            return performanceConditionHolds((opcode >>> 3) & 0x03) ? 3 : 2;
        }
        if (opcode == 0xc3) {
            return 4;
        }
        if ((opcode & 0xe7) == 0xc2) {
            return performanceConditionHolds((opcode >>> 3) & 0x03) ? 4 : 3;
        }
        if ((opcode & 0xcf) == 0x03 || (opcode & 0xcf) == 0x0b
                || (opcode & 0xcf) == 0x09 || opcode == 0xf9
                || opcode == 0x02 || opcode == 0x12 || opcode == 0x0a || opcode == 0x1a
                || opcode == 0x22 || opcode == 0x2a || opcode == 0x32 || opcode == 0x3a
                || opcode >= 0x40 && opcode <= 0x7f && opcode != 0x76
                        && (((opcode >>> 3) & 0x07) == 6 || (opcode & 0x07) == 6)
                || opcode >= 0x80 && opcode <= 0xbf && (opcode & 0x07) == 6
                || opcode == 0xe2 || opcode == 0xf2) {
            return 2;
        }
        if (opcode == 0x34 || opcode == 0x35 || opcode == 0x36
                || opcode == 0xe0 || opcode == 0xf0) {
            return 3;
        }
        if (opcode == 0xea || opcode == 0xfa) {
            return 4;
        }
        return 0;
    }

    private int executePerformanceIncDec(int value, boolean decrement) {
        int result = (value + (decrement ? -1 : 1)) & 0xff;
        int flags = registers.getFlags().getFlagsByte() & FLAG_C;
        if (decrement) {
            flags |= FLAG_N;
        }
        if (result == 0) {
            flags |= FLAG_Z;
        }
        if (decrement ? (value & 0x0f) == 0 : (value & 0x0f) == 0x0f) {
            flags |= FLAG_H;
        }
        registers.getFlags().setFlagsByteTrusted(flags);
        return result;
    }

    private int executePerformancePhysicalDmgIncDec(int value, boolean decrement) {
        int result = (value + (decrement ? -1 : 1)) & 0xff;
        int flags = registers.getFlags().getFlagsByte() & FLAG_C;
        if (decrement) {
            flags |= FLAG_N;
        }
        if (result == 0) {
            flags |= FLAG_Z;
        }
        if (decrement ? (value & 0x0f) == 0 : (value & 0x0f) == 0x0f) {
            flags |= FLAG_H;
        }
        registers.getFlags().setFlagsByteTrusted(flags);
        return result;
    }

    private int readPerformanceRegisterPair(int pair) {
        return switch (pair) {
            case 0 -> registers.getBC();
            case 1 -> registers.getDE();
            case 2 -> registers.getHL();
            default -> registers.getSP();
        };
    }

    private int readPerformancePhysicalDmgRegisterPair(int pair) {
        return switch (pair) {
            case 0 -> registers.getBC();
            case 1 -> registers.getDE();
            case 2 -> registers.getHL();
            default -> registers.getSP();
        };
    }

    private void writePerformanceRegisterPair(int pair, int value) {
        switch (pair) {
            case 0 -> registers.setBCTrusted(value);
            case 1 -> registers.setDETrusted(value);
            case 2 -> registers.setHLTrusted(value);
            default -> registers.setSPTrusted(value);
        }
    }

    private static boolean isPerformanceOamArea(int address) {
        int a = address & 0xffff;
        return a >= 0xfe00 && a <= 0xfeff;
    }

    /** Executes every CB-prefixed register target; (HL) remains on the observed bus path. */
    private boolean executePerformanceDirectExtendedOpcode(int opcode) {
        int target = opcode & 0x07;
        if (target == 6) {
            return false;
        }
        int value = readPerformanceRegister(target);
        int operationClass = opcode >>> 6;
        int operation = (opcode >>> 3) & 0x07;
        int result = value;
        int flags = registers.getFlags().getFlagsByte();
        if (operationClass == 0) {
            int carry;
            switch (operation) {
                case 0 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | carry) & 0xff;
                }
                case 1 -> {
                    carry = value & 1;
                    result = (value >>> 1) | (carry << 7);
                }
                case 2 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | ((flags & FLAG_C) >>> 4)) & 0xff;
                }
                case 3 -> {
                    carry = value & 1;
                    result = (value >>> 1) | ((flags & FLAG_C) << 3);
                }
                case 4 -> {
                    carry = value >>> 7;
                    result = (value << 1) & 0xff;
                }
                case 5 -> {
                    carry = value & 1;
                    result = (value >>> 1) | (value & 0x80);
                }
                case 6 -> {
                    carry = 0;
                    result = (value << 4 | value >>> 4) & 0xff;
                }
                default -> {
                    carry = value & 1;
                    result = value >>> 1;
                }
            }
            flags = result == 0 ? FLAG_Z : 0;
            if (carry != 0) {
                flags |= FLAG_C;
            }
            writePerformanceRegister(target, result);
        } else if (operationClass == 1) {
            flags = (flags & FLAG_C) | FLAG_H;
            if ((value & (1 << operation)) == 0) {
                flags |= FLAG_Z;
            }
        } else if (operationClass == 2) {
            result = value & ~(1 << operation);
            writePerformanceRegister(target, result);
        } else {
            result = value | 1 << operation;
            writePerformanceRegister(target, result);
        }
        registers.getFlags().setFlagsByteTrusted(flags);
        finishPerformanceDirectInstruction(result);
        return true;
    }

    /** Physical-DMG copy keeps the native-CGB CB helper single-caller and inlineable. */
    private boolean executePerformancePhysicalDmgDirectExtendedOpcode(int opcode) {
        int target = opcode & 0x07;
        if (target == 6) {
            return false;
        }
        int value = readPerformanceRegister(target);
        int operationClass = opcode >>> 6;
        int operation = (opcode >>> 3) & 0x07;
        int result = value;
        int flags = registers.getFlags().getFlagsByte();
        if (operationClass == 0) {
            int carry;
            switch (operation) {
                case 0 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | carry) & 0xff;
                }
                case 1 -> {
                    carry = value & 1;
                    result = (value >>> 1) | (carry << 7);
                }
                case 2 -> {
                    carry = value >>> 7;
                    result = ((value << 1) | ((flags & FLAG_C) >>> 4)) & 0xff;
                }
                case 3 -> {
                    carry = value & 1;
                    result = (value >>> 1) | ((flags & FLAG_C) << 3);
                }
                case 4 -> {
                    carry = value >>> 7;
                    result = (value << 1) & 0xff;
                }
                case 5 -> {
                    carry = value & 1;
                    result = (value >>> 1) | (value & 0x80);
                }
                case 6 -> {
                    carry = 0;
                    result = (value << 4 | value >>> 4) & 0xff;
                }
                default -> {
                    carry = value & 1;
                    result = value >>> 1;
                }
            }
            flags = result == 0 ? FLAG_Z : 0;
            if (carry != 0) {
                flags |= FLAG_C;
            }
            writePerformanceRegister(target, result);
        } else if (operationClass == 1) {
            flags = (flags & FLAG_C) | FLAG_H;
            if ((value & (1 << operation)) == 0) {
                flags |= FLAG_Z;
            }
        } else if (operationClass == 2) {
            result = value & ~(1 << operation);
            writePerformanceRegister(target, result);
        } else {
            result = value | 1 << operation;
            writePerformanceRegister(target, result);
        }
        registers.getFlags().setFlagsByteTrusted(flags);
        finishPerformanceDirectInstruction(result);
        return true;
    }

    /** Returns the canonical Op-chain context result and updates A/flags. */
    private int executePerformanceAlu(int operation, int value) {
        int accumulator = registers.getA();
        int oldFlags = registers.getFlags().getFlagsByte();
        int carry = (oldFlags & FLAG_C) >>> 4;
        int result;
        int flags;
        switch (operation) {
            case 0 -> {
                int sum = accumulator + value;
                result = sum & 0xff;
                flags = result == 0 ? FLAG_Z : 0;
                if ((accumulator & 0x0f) + (value & 0x0f) > 0x0f) {
                    flags |= FLAG_H;
                }
                if (sum > 0xff) {
                    flags |= FLAG_C;
                }
            }
            case 1 -> {
                int sum = accumulator + value + carry;
                result = sum & 0xff;
                flags = result == 0 ? FLAG_Z : 0;
                if ((accumulator & 0x0f) + (value & 0x0f) + carry > 0x0f) {
                    flags |= FLAG_H;
                }
                if (sum > 0xff) {
                    flags |= FLAG_C;
                }
            }
            case 2 -> {
                result = (accumulator - value) & 0xff;
                flags = FLAG_N | (result == 0 ? FLAG_Z : 0);
                if ((value & 0x0f) > (accumulator & 0x0f)) {
                    flags |= FLAG_H;
                }
                if (value > accumulator) {
                    flags |= FLAG_C;
                }
            }
            case 3 -> {
                int difference = accumulator - value - carry;
                result = difference & 0xff;
                flags = FLAG_N | (result == 0 ? FLAG_Z : 0);
                if (((accumulator ^ value ^ result) & 0x10) != 0) {
                    flags |= FLAG_H;
                }
                if (difference < 0) {
                    flags |= FLAG_C;
                }
            }
            case 4 -> {
                result = accumulator & value;
                flags = FLAG_H | (result == 0 ? FLAG_Z : 0);
            }
            case 5 -> {
                result = (accumulator ^ value) & 0xff;
                flags = result == 0 ? FLAG_Z : 0;
            }
            case 6 -> {
                result = accumulator | value;
                flags = result == 0 ? FLAG_Z : 0;
            }
            default -> {
                int difference = (accumulator - value) & 0xff;
                flags = FLAG_N | (difference == 0 ? FLAG_Z : 0);
                if ((value & 0x0f) > (accumulator & 0x0f)) {
                    flags |= FLAG_H;
                }
                if (value > accumulator) {
                    flags |= FLAG_C;
                }
                result = accumulator;
            }
        }
        if (operation != 7) {
            registers.setATrusted(result);
        }
        registers.getFlags().setFlagsByteTrusted(flags);
        return result;
    }

    private boolean performanceConditionHolds(int condition) {
        int flags = registers.getFlags().getFlagsByte();
        return switch (condition) {
            case 0 -> (flags & FLAG_Z) == 0;
            case 1 -> (flags & FLAG_Z) != 0;
            case 2 -> (flags & FLAG_C) == 0;
            default -> (flags & FLAG_C) != 0;
        };
    }

    private int readPerformanceRegister(int register) {
        return switch (register) {
            case 0 -> registers.getB();
            case 1 -> registers.getC();
            case 2 -> registers.getD();
            case 3 -> registers.getE();
            case 4 -> registers.getH();
            case 5 -> registers.getL();
            case 7 -> registers.getA();
            default -> throw new IllegalArgumentException("(HL) is not a direct register target");
        };
    }

    private void writePerformanceRegister(int register, int value) {
        switch (register) {
            case 0 -> registers.setBTrusted(value);
            case 1 -> registers.setCTrusted(value);
            case 2 -> registers.setDTrusted(value);
            case 3 -> registers.setETrusted(value);
            case 4 -> registers.setHTrusted(value);
            case 5 -> registers.setLTrusted(value);
            case 7 -> registers.setATrusted(value);
            default -> throw new IllegalArgumentException("(HL) is not a direct register target");
        }
    }

    private void finishPerformanceDirectInstruction(int context) {
        opContext = context;
        ops = currentOpcode.getOps();
        opIndex = currentOpCount;
        state = State.OPCODE;
        operandIndex = 0;
        interruptManager.onInstructionFinished();
    }

    /** Advances one master tick, retaining the historical scalar entry point. */
    public void tick() {
        if (!tickPhaseOnly()) {
            tickAtMachineCycle();
        }
    }

    private boolean isJoypadLineLow() {
        return (addressSpace.getByte(0xff00) & 0x0f) != 0x0f;
    }

    /** Cheap guard for owners that can skip the normally dormant peripheral callback. */
    public boolean hasPendingPeripheralSample() {
        return haltEntrySampleTicks > 0;
    }

    /** Completes HALT's entry sample after this tick's peripheral edges settle. */
    public void onPeripheralsTicked() {
        if (haltEntrySampleTicks <= 0) {
            return;
        }
        boolean ime = interruptManager.isIme();
        boolean asynchronousRequest = ime
                ? interruptManager.isInterruptRequestedWhileHaltWakeBlocked()
                : interruptManager.isInterruptRequested();
        if (state == State.HALTED && asynchronousRequest) {
            haltEntrySampleTicks = 0;
            state = State.OPCODE;
            asynchronousHaltEntryStatPhase = true;
            ordinaryHaltWakeStatPhase = false;
            if (ime) {
                // The enabled request is accepted, but the asynchronous edge makes
                // interrupt entry push HALT's address so RETI executes it again.
                registers.setPC((registers.getPC() - 1) & 0xffff);
            } else {
                haltBugMode = true;
                // The disabled request resumes opcode fetch on the asynchronous
                // edge, producing the ordinary halt-bug bus rephase.
                clockCycle++;
                if (timer != null) {
                    timer.onHaltBug();
                }
            }
        } else {
            haltEntrySampleTicks--;
        }
    }

    private void handleInterrupt() {
        switch (state) {
            case IRQ_WAIT_1:
                interruptManager.disableInterrupts(false);
                state = State.IRQ_WAIT_2;
                break;

            case IRQ_WAIT_2:
                state = State.IRQ_PUSH_1;
                break;

            case IRQ_PUSH_1:
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), (registers.getPC() & 0xff00) >> 8);
                state = State.IRQ_PUSH_2;
                break;

            case IRQ_PUSH_2:
                interruptFlag = addressSpace.getByte(0xff0f);
                interruptEnabled = addressSpace.getByte(0xffff);
                requestedIrq = null;
                for (InterruptManager.InterruptType irq : InterruptManager.InterruptType.VALUES) {
                    if ((interruptFlag & interruptEnabled & (1 << irq.ordinal()) & 0x1f) != 0) {
                        requestedIrq = irq;
                        break;
                    }
                }
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), registers.getPC() & 0x00ff);
                if (requestedIrq != null) {
                    interruptManager.clearInterrupt(requestedIrq);
                }
                state = State.IRQ_JUMP;
                break;

            case IRQ_JUMP:
                applyLateInterruptPriority();
                if (requestedIrq != null) {
                    notifyInterruptAccepted(requestedIrq);
                    registers.setPC(requestedIrq.getHandler());
                } else {
                    registers.setPC(0x0000);
                }
                requestedIrq = null;
                state = State.OPCODE;
                markDebugRetirement();
                break;
        }
    }

    /**
     * The interrupt priority gate remains live through the final vector cycle. If a
     * higher-priority source arrives after the stack pushes selected and acknowledged
     * a lower source, vector to the new source and leave the old one pending.
     */
    private void applyLateInterruptPriority() {
        if (requestedIrq == null) {
            return;
        }
        for (InterruptManager.InterruptType irq : InterruptManager.InterruptType.VALUES) {
            if (irq.ordinal() >= requestedIrq.ordinal()) {
                return;
            }
            int mask = 1 << irq.ordinal();
            if ((interruptEnabled & mask) != 0
                    && interruptManager.isInterruptFlagSet(irq)) {
                interruptManager.requestInterrupt(requestedIrq);
                interruptManager.clearInterrupt(irq);
                requestedIrq = irq;
                return;
            }
        }
    }

    private void handleSpriteBug(SpriteBug.CorruptionType type) {
        gpu.corruptOam(type);
    }

    public Registers getRegisters() {
        return registers;
    }

    void clearState() {
        opcode1 = 0;
        opcode2 = 0;
        currentOpcode = null;
        currentExecutionOps = null;
        currentOpAccessesMemory = null;
        currentOpWritesMemory = null;
        currentOpCount = 0;
        currentOperandLength = 0;
        ops = null;

        operand[0] = 0x00;
        operand[1] = 0x00;
        operandIndex = 0;

        opIndex = 0;
        opContext = 0;

        interruptFlag = 0;
        interruptEnabled = 0;
        requestedIrq = null;
    }

    public State getState() {
        return state;
    }

    /** Enables allocation-free instruction-retirement observation for an attached debugger. */
    public void enableDebugRetirementTracking() {
        if (debugRetirementTracker == null) {
            debugRetirementTracker = new DebugRetirementTracker();
        }
    }

    /** Disables the optional retirement hook without changing emulated CPU state. */
    public void disableDebugRetirementTracking() {
        debugRetirementTracker = null;
    }

    /** Installs or removes the active-only CPU bus/instruction observer. */
    public void setDebugHooks(DebugHooks hooks) {
        boolean observeMemory = hooks != null && hooks.requiresMemoryAccessHooks();
        if (debugHooks == hooks && (debugAddressSpace != null) == observeMemory) {
            return;
        }
        boolean observerChanged = debugHooks != hooks;
        debugHooks = hooks;
        if (observerChanged) {
            debugInstructionKnown = false;
            debugInstructionPc = 0;
        }
        if (!observeMemory) {
            debugAddressSpace = null;
            addressSpace = baseAddressSpace;
        } else {
            DebugCpuAddressSpace observed = new DebugCpuAddressSpace(baseAddressSpace, hooks);
            debugAddressSpace = observed;
            addressSpace = observed;
        }
    }

    /** Sequence local to the current debugger attachment; zero means no retirement was observed. */
    public long getDebugRetirementSequence() {
        DebugRetirementTracker tracker = debugRetirementTracker;
        return tracker == null ? 0 : tracker.sequence;
    }

    public int getDebugOpcode() {
        return opcode1;
    }

    public int getDebugExtendedOpcode() {
        return opcode1 == 0xcb ? opcode2 : -1;
    }

    /** Current CPU sub-cycle within the active machine-cycle slot. */
    public int getDebugMachineCycle() {
        return Math.max(0, clockCycle);
    }

    public boolean isDebugHaltBugActive() {
        return haltBugMode;
    }

    private void markDebugRetirement() {
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onInstructionRetired(
                    debugInstructionKnown,
                    debugInstructionPc,
                    opcode1,
                    opcode1 == 0xcb ? opcode2 : -1);
        }
        debugInstructionKnown = false;
        DebugRetirementTracker tracker = debugRetirementTracker;
        if (tracker != null) {
            tracker.sequence++;
        }
    }

    private void beginDebugInstruction(int programCounter) {
        DebugHooks hooks = debugHooks;
        if (hooks == null) {
            return;
        }
        debugInstructionKnown = true;
        debugInstructionPc = programCounter;
        hooks.onInstructionFetch(programCounter);
    }

    private void notifyOpcodeFetched(int programCounter, boolean cbPrefixed, int opcode) {
        DebugHooks hooks = debugHooks;
        if (hooks != null && debugInstructionKnown) {
            hooks.onOpcodeFetched(programCounter, cbPrefixed, opcode);
        }
    }

    private int readInstructionByte(int address) {
        DebugCpuAddressSpace observed = debugAddressSpace;
        return observed == null
                ? baseAddressSpace.getByte(address)
                : observed.getByte(address, DebugMemoryAccess.EXECUTE);
    }

    /** Acquires one borrowed mapping after the native epoch entrance proof has passed. */
    private PerformanceRomAccess acquirePerformanceEpochRomAccess() {
        PerformanceRomAccessProvider provider = performanceRomAccessProvider;
        return provider == null ? null : provider.acquirePerformanceRomAccess();
    }

    /** Native-CGB epoch-only instruction fetch through its borrowed physical ROM mapping. */
    private int readPerformanceEpochInstructionByte(int address) {
        PerformanceRomAccess romAccess = performanceEpochRomAccess;
        if (romAccess != null && address >= 0 && address < 0x8000) {
            int value = romAccess.readCpuByte(address);
            if (value >= 0) {
                return value;
            }
        }
        return readInstructionByte(address);
    }

    private void notifyInterruptAccepted(InterruptManager.InterruptType interrupt) {
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onInterruptAccepted(toDebugInterruptType(interrupt));
        }
    }

    private static DebugInterruptType toDebugInterruptType(
            InterruptManager.InterruptType interrupt) {
        return switch (interrupt) {
            case VBlank -> DebugInterruptType.VBLANK;
            case LCDC -> DebugInterruptType.LCD_STATUS;
            case Timer -> DebugInterruptType.TIMER;
            case Serial -> DebugInterruptType.SERIAL;
            case P10_13 -> DebugInterruptType.JOYPAD;
        };
    }

    private static final class DebugRetirementTracker {

        private long sequence;
    }

    public boolean isSynchronousHaltEntryStatPhase() {
        return synchronousHaltEntryStatPhase;
    }

    public boolean isAsynchronousHaltEntryStatPhase() {
        return asynchronousHaltEntryStatPhase;
    }

    public boolean isOrdinaryHaltWakeStatPhase() {
        return ordinaryHaltWakeStatPhase;
    }

    public boolean isOneCycleOrdinaryHaltWakeStatPhase() {
        return ordinaryHaltWakeStatPhase && haltedCpuCycles == 1;
    }

    /** Returns all STAT CPU-read phase flags sampled at this point in one call. */
    public int getStatReadPhaseFlags() {
        int flags = 0;
        if (synchronousHaltEntryStatPhase) {
            flags |= STAT_READ_PHASE_SYNCHRONOUS_HALT_ENTRY;
        }
        if (asynchronousHaltEntryStatPhase) {
            flags |= STAT_READ_PHASE_ASYNCHRONOUS_HALT_ENTRY;
        }
        if (ordinaryHaltWakeStatPhase) {
            flags |= STAT_READ_PHASE_ORDINARY_HALT_WAKE;
            if (haltedCpuCycles == 1) {
                flags |= STAT_READ_PHASE_ONE_CYCLE_ORDINARY_HALT_WAKE;
            }
        }
        return flags;
    }

    /**
     * Returns whether an FF0F read has already sampled the LCDC request input but
     * has not yet completed its data phase. Mode-0 can set the stored IF latch in
     * that interval without changing the value returned by this one bus cycle.
     */
    public int getInterruptFlagReadMaskTicks(boolean mode0EdgeNextTick) {
        if (!mode0EdgeNextTick) {
            return 0;
        }
        DecodedMemoryAccess pendingAccess = state == State.RUNNING
                ? resolveFirstMemoryAccess(ops, opIndex, operand, opContext)
                : null;
        if (isReadFrom(pendingAccess, 0xff0f)) {
            if (speedMode.getSpeedMode() == 1) {
                return clockCycle == 1 ? 2 : clockCycle == 2 ? 1 : 0;
            }
            // At double speed, a read which will retire after this tick has
            // already sampled the peripheral request gate. A read retiring in
            // this tick completes before the later PPU edge and needs no mask.
            return clockCycle == 0 ? 1 : 0;
        }
        if (speedMode.getSpeedMode() != 2 || state != State.OPCODE
                || gpu == null || !gpu.isGbc() || gpu.isDmgCompatMode()) {
            return 0;
        }
        int pc = registers.getPC();
        DecodedInstruction nextInstruction = decodeInstructionAt(pc);
        if (readsAddressFirst(nextInstruction, 0xff0f)) {
            return clockCycle == 0 ? 3 : 2;
        }
        int followingPc = (pc + 1) & 0xffff;
        if (clockCycle == 1 && nextInstruction != null
                && nextInstruction.opcode.getOpcode() == 0x00
                && readsAddressFirst(decodeInstructionAt(followingPc), 0xff0f)) {
            // The current one-cycle NOP retires before the PPU edge. The next
            // FF0F read still belongs to the four-dot synchronizer window.
            return 4;
        }
        return 0;
    }

    private static boolean isSafeInstructionAddress(int address) {
        return address < 0x8000
                || address >= 0xc000 && address < 0xfe00
                || address >= 0xff80 && address < 0xffff;
    }

    /** A request crossing EI/RETI's enable window uses ordinary IRQ entry. */
    public boolean isMode0InterruptDispatchPhased(boolean mode0EdgeNextTick) {
        return mode0EdgeNextTick && speedMode.isGbc()
                && speedMode.getSpeedMode() == 1
                && clockCycle == 0
                && (interruptManager.isInterruptEnablePending()
                || state == State.RUNNING && opcode1 == 0xd9);
    }

    /**
     * The opcode fetch at this normal-speed phase has priority over a later
     * mode-0 edge. DI or an IE write can therefore withdraw acceptance before
     * the stored request reaches the CPU input.
     */
    public boolean doesMode0InstructionWinInterruptAcceptance(
            boolean mode0EdgeNextTick) {
        if (!mode0EdgeNextTick || !speedMode.isGbc()
                || speedMode.getSpeedMode() != 1
                || state != State.OPCODE || clockCycle != 1) {
            return false;
        }
        int pc = registers.getPC();
        DecodedInstruction nextInstruction = decodeInstructionAt(pc);
        if (nextInstruction == null) {
            return false;
        }
        if (nextInstruction.opcode.getOpcode() == 0xf3) {
            return true;
        }
        return writesDisabledInterruptEnable(nextInstruction);
    }

    private DecodedInstruction decodeInstructionAt(int pc) {
        if (!isSafeInstructionAddress(pc)) {
            return null;
        }
        Opcode decodedOpcode = Opcodes.COMMANDS.get(baseAddressSpace.getByte(pc));
        if (decodedOpcode == null) {
            return null;
        }
        int[] decodedOperand = new int[2];
        for (int i = 0; i < decodedOpcode.getOperandLength(); i++) {
            int operandAddress = (pc + i + 1) & 0xffff;
            // Speculation is limited to executable ROM/RAM. In particular, do
            // not inspect cartridge RAM, VRAM, OAM, or memory-mapped I/O while
            // classifying an upcoming CPU bus access.
            if (!isSafeInstructionAddress(operandAddress)) {
                return null;
            }
            decodedOperand[i] = baseAddressSpace.getByte(operandAddress);
        }
        return new DecodedInstruction(decodedOpcode, decodedOperand);
    }

    private DecodedMemoryAccess resolveFirstMemoryAccess(
            List<Op> decodedOps, int firstOp, int[] decodedOperand, int context) {
        if (decodedOps == null) {
            return null;
        }
        for (int i = firstOp; i < decodedOps.size(); i++) {
            Op op = decodedOps.get(i);
            if (!op.readsMemory() && !op.writesMemory()) {
                continue;
            }
            Integer address = op.resolveMemoryAddress(registers, decodedOperand, context);
            if (address == null) {
                // Internal wait cycles deliberately have no effective address.
                return null;
            }
            return new DecodedMemoryAccess(address, op.readsMemory());
        }
        return null;
    }

    private boolean readsAddressFirst(DecodedInstruction instruction, int address) {
        if (instruction == null || instruction.opcode.getOperandLength() != 0) {
            return false;
        }
        return isReadFrom(resolveFirstMemoryAccess(
                instruction.opcode.getOps(), 0, instruction.operand, 0), address);
    }

    private static boolean isReadFrom(DecodedMemoryAccess access, int address) {
        return access != null && access.read && access.address == address;
    }

    private boolean writesDisabledInterruptEnable(DecodedInstruction instruction) {
        int context = 0;
        for (Op op : instruction.opcode.getOps()) {
            if (op.readsMemory()) {
                // Determining a later write would require speculatively reading
                // data memory. Leave read-modify-write instructions alone.
                return false;
            }
            if (op.writesMemory()) {
                Integer address = op.resolveMemoryAddress(
                        registers, instruction.operand, context);
                Integer value = op.resolveMemoryWriteValue(context);
                if (address == null || value == null) {
                    return false;
                }
                if (address == 0xffff
                        && (value & (1 << InterruptManager.InterruptType.LCDC.ordinal())) == 0) {
                    return true;
                }
                continue;
            }
            Integer previewContext = op.previewContext(
                    registers, instruction.operand, context);
            if (previewContext == null) {
                return false;
            }
            context = previewContext;
        }
        return false;
    }

    private static class DecodedInstruction {

        private final Opcode opcode;

        private final int[] operand;

        private DecodedInstruction(Opcode opcode, int[] operand) {
            this.opcode = opcode;
            this.operand = operand;
        }
    }

    private static class DecodedMemoryAccess {

        private final int address;

        private final boolean read;

        private DecodedMemoryAccess(int address, boolean read) {
            this.address = address;
            this.read = read;
        }
    }

    /**
     * Returns the instruction-bus residue observed when VRAM DMA takes the bus.
     * If the next opcode was already fetched in the scheduler tick that wrote
     * HDMA5, its opcode byte remains on the bus even though PC now addresses an
     * operand. Otherwise the grant samples the byte at the next PC.
     */
    public int getBusValueForHdma() {
        if (state == State.EXT_OPCODE || state == State.OPERAND || state == State.RUNNING) {
            return opcode1;
        }
        if (hdmaArbitrationOpcodeValid) {
            return hdmaArbitrationOpcode;
        }
        return addressSpace.getByte(registers.getPC());
    }

    /**
     * Hardware fetches the opcode at the next PC immediately before a VRAM-DMA
     * burst. Decode that byte and hold the resulting pipeline state until the DMA
     * releases the CPU; operands and operations must not run during the burst.
     */
    public void prefetchOpcodeForHdma() {
        if (state != State.OPCODE || hdmaOpcodePrefetched
                || (interruptManager.isIme() && interruptManager.isInterruptRequested())) {
            return;
        }
        int pc = registers.getPC();
        if (isHdmaOpcodeFetchBlockedByPpu(pc)) {
            // At the closing mode-2 arbitration slot, the PPU has already claimed
            // VRAM for mode 3. A simultaneously started VRAM DMA wins before the
            // CPU can perform its speculative opcode fetch.
            return;
        }

        boolean useSpeedSwitchPadding = speedSwitchPaddingReplayValid;
        boolean useHdmaArbitrationOpcode = hdmaArbitrationOpcodeValid;
        speedSwitchPaddingReplayValid = false;
        hdmaArbitrationOpcodeValid = false;
        beginDebugInstruction(pc);
        clearState();
        opcode1 = useSpeedSwitchPadding
                ? speedSwitchPaddingOpcode
                : useHdmaArbitrationOpcode
                ? hdmaArbitrationOpcode
                : readInstructionByte(pc);
        notifyOpcodeFetched(pc, false, opcode1);
        if (opcode1 == 0xcb || opcode1 == 0x10) {
            state = State.EXT_OPCODE;
            if (opcode1 == 0x10) {
                setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
            }
        } else {
            setCurrentOpcode(Opcodes.COMMANDS.get(opcode1));
            state = currentOpcode == null ? State.LOCKED : State.OPERAND;
        }
        if (useSpeedSwitchPadding) {
            // STOP already advanced PC past this held byte before the clock switch.
        } else if (!haltBugMode) {
            registers.incrementPC();
        } else {
            haltBugMode = false;
        }
        hdmaOpcodePrefetched = true;
    }

    private boolean isHdmaOpcodeFetchBlockedByPpu(int pc) {
        return gpu != null && pc >= 0x8000 && pc < 0xa000
                && gpu.getMode() == Mode.OamSearch && gpu.getTicksInLine() >= 79;
    }

    /** Preserve HALT's sampled next opcode when HALT acknowledges an HDMA request. */
    public void latchHdmaHaltOpcode(boolean requestLatched) {
        if (requestLatched && state == State.HALTED && !haltOpcodePrefetchValid) {
            // A mode-3-to-HBlank acknowledge owns VRAM's internal bus even while the
            // ordinary CPU-facing gate is still returning ff. Preserve the byte HALT
            // sampled from that bus, not the gated CPU read value.
            int pc = registers.getPC();
            if (gpu != null && pc >= 0x8000 && pc < 0xa000) {
                haltPrefetchedOpcode = gpu.readSelectedVideoRamForCore(pc);
            }
            haltOpcodePrefetchValid = true;
        }
    }

    /** Release the held opcode when one HDMA block yields to a queued block. */
    public void releaseHdmaPrefetchedOpcode() {
        hdmaOpcodePrefetched = false;
    }

    /**
     * Whether the CPU side of an HDMA request slot is still in progress. Besides an
     * already decoded instruction, the latter half of an opcode cycle owns the slot
     * until the arbiter performs its one authoritative opcode fetch.
     */
    public boolean isCpuRequestSlotInProgressForHdma() {
        return isInstructionRetiringForHdma()
                || (!hdmaOpcodePrefetched && state == State.OPCODE && clockCycle >= 2);
    }

    /** Whether an ordinary CPU-fetched instruction is currently retiring. */
    public boolean isInstructionRetiringForHdma() {
        return !hdmaOpcodePrefetched
                && (state == State.EXT_OPCODE || state == State.OPERAND
                || state == State.RUNNING);
    }

    /**
     * Resolves the CPU side of a newly synchronized HDMA request. If the request
     * reaches the latter half of an opcode cycle, this method samples that byte once
     * without advancing PC or decoding it. The ordinary opcode boundary or DMA
     * prefetch later consumes the same latch. HALT yields the slot; every other opcode
     * keeps it. Interrupt ownership sampled before this point is resolved separately.
     */
    public boolean claimCpuRequestSlotForHdma() {
        if (isInstructionRetiringForHdma()) {
            return true;
        }
        if (hdmaOpcodePrefetched || state != State.OPCODE || clockCycle < 2) {
            return false;
        }
        if (haltOpcodePrefetchValid) {
            return haltPrefetchedOpcode != 0x76;
        }
        if (speedSwitchPaddingReplayValid) {
            return speedSwitchPaddingOpcode != 0x76;
        }
        if (!hdmaArbitrationOpcodeValid) {
            int pc = registers.getPC();
            if (isHdmaOpcodeFetchBlockedByPpu(pc)) {
                return false;
            }
            hdmaArbitrationOpcode = readInstructionByte(pc);
            hdmaArbitrationOpcodeValid = true;
        }
        return hdmaArbitrationOpcode != 0x76;
    }

    /** Whether IE and IF already asserted a request when the HDMA slot was resolved. */
    public boolean hasPendingInterruptForHdmaArbitration() {
        return interruptManager.isInterruptRequested();
    }

    public boolean isInterruptEntryBusSequenceActiveForHdma() {
        return state == State.IRQ_WAIT_1 || state == State.IRQ_WAIT_2
                || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2;
    }

    /**
     * Whether interrupt acceptance has won the current CPU/HDMA arbitration slot.
     * At the second tick of an opcode-boundary cycle, an enabled pending interrupt
     * has already been sampled even though the dispatch state is entered only when
     * that machine cycle completes.
     */
    public boolean isInterruptClaimedAtHdmaSample() {
        return isInterruptEntryBusSequenceActiveForHdma()
                || ((state == State.OPCODE || state == State.RUNNING) && clockCycle == 2
                && interruptManager.isIme() && interruptManager.isInterruptRequested());
    }

    /** Continue the CPU side of a previously sampled interrupt entry. */
    public boolean canAdvanceInterruptEntryForHdma() {
        return isInterruptEntryBusSequenceActiveForHdma()
                || ((state == State.OPCODE || state == State.RUNNING)
                && interruptManager.isIme() && interruptManager.isInterruptRequested());
    }

    /** Whether an already-started CPU write cycle overlaps the HDMA request edge. */
    public boolean hasInFlightWriteCycleForHdma() {
        return clockCycle >= 2 && state == State.RUNNING && currentOpcode != null
                && opIndex < currentOpCount
                && currentOpWritesMemory[opIndex];
    }

    /** Returns the post-CPU HDMA arbitration flags sampled at this point in one call. */
    public int getHdmaPhaseFlags() {
        int flags = 0;
        boolean instructionRetiring = !hdmaOpcodePrefetched
                && (state == State.EXT_OPCODE || state == State.OPERAND
                || state == State.RUNNING);
        if (clockCycle >= 2 && state == State.RUNNING && currentOpcode != null
                && opIndex < currentOpCount && currentOpWritesMemory[opIndex]) {
            flags |= HDMA_PHASE_IN_FLIGHT_WRITE_CYCLE;
        }
        if (instructionRetiring
                || (!hdmaOpcodePrefetched && state == State.OPCODE && clockCycle >= 2)) {
            flags |= HDMA_PHASE_CPU_REQUEST_SLOT_IN_PROGRESS;
        }
        boolean interruptEntry = state == State.IRQ_WAIT_1 || state == State.IRQ_WAIT_2
                || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2
                || ((state == State.OPCODE || state == State.RUNNING) && clockCycle == 2
                && interruptManager.isIme() && interruptManager.isInterruptRequested());
        if (interruptEntry) {
            flags |= HDMA_PHASE_INTERRUPT_CLAIMED;
        }
        return flags;
    }

    public boolean isSpeedSwitching() {
        return state == State.SPEED_SWITCH;
    }

    /**
     * A VRAM-DMA grant overlapping STOP retains the fetched padding byte as the
     * first opcode after the speed-switch clock resumes.
     */
    public void replaySpeedSwitchPaddingByte() {
        if (state == State.SPEED_SWITCH) {
            speedSwitchPaddingOpcode = opcode2;
            speedSwitchPaddingReplayValid = true;
        }
    }

    public boolean consumeStopFrameBlankRequest() {
        boolean requested = stopFrameBlankRequested;
        stopFrameBlankRequested = false;
        return requested;
    }

    Opcode getCurrentOpcode() {
        return currentOpcode;
    }

    @Override
    public ComponentState<Cpu> captureState() {
        int[] operand = new int[2];
        operand[0] = this.operand[0];
        operand[1] = this.operand[1];
        return new CpuState(registers.captureState(), opcode1, opcode2, operand, operandIndex, opIndex,
                state, opContext, interruptFlag, interruptEnabled, requestedIrq, clockCycle, haltBugMode,
                haltEntrySampleTicks, synchronousHaltEntryStatPhase, asynchronousHaltEntryStatPhase,
                ordinaryHaltWakeStatPhase, haltedCpuCycles,
                hdmaOpcodePrefetched, hdmaArbitrationOpcode, hdmaArbitrationOpcodeValid,
                haltPrefetchedOpcode, haltOpcodePrefetchValid,
                speedSwitchPaddingOpcode, speedSwitchPaddingReplayValid,
                speedSwitchTicks, phasedPpuInputHigh, fastPhasedPpuDispatch);
    }

    @Override
    public ComponentState<Cpu> captureState(MachineStateCapture capture) {
        return new CpuState(registers.captureState(), opcode1, opcode2, capture.ints(operand), operandIndex, opIndex,
                state, opContext, interruptFlag, interruptEnabled, requestedIrq, clockCycle, haltBugMode,
                haltEntrySampleTicks, synchronousHaltEntryStatPhase, asynchronousHaltEntryStatPhase,
                ordinaryHaltWakeStatPhase, haltedCpuCycles,
                hdmaOpcodePrefetched, hdmaArbitrationOpcode, hdmaArbitrationOpcodeValid,
                haltPrefetchedOpcode, haltOpcodePrefetchValid,
                speedSwitchPaddingOpcode, speedSwitchPaddingReplayValid,
                speedSwitchTicks, phasedPpuInputHigh, fastPhasedPpuDispatch);
    }

    @Override
    public void restoreState(ComponentState<Cpu> state) {
        if (!(state instanceof CpuState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.registers.restoreState(mem.registersMemento);
        this.opcode1 = mem.opcode1;
        this.opcode2 = mem.opcode2;
        this.operand[0] = mem.operand[0];
        this.operand[1] = mem.operand[1];
        this.operandIndex = mem.operandIndex;
        this.opIndex = mem.opIndex;
        this.state = mem.state;
        this.opContext = mem.opContext;
        this.interruptFlag = mem.interruptFlag;
        this.interruptEnabled = mem.interruptEnabled;
        this.requestedIrq = mem.requestedIrq;
        this.clockCycle = mem.clockCycle;
        this.haltBugMode = mem.haltBugMode;
        this.haltEntrySampleTicks = mem.haltEntrySampleTicks;
        this.synchronousHaltEntryStatPhase = mem.synchronousHaltEntryStatPhase;
        this.asynchronousHaltEntryStatPhase = mem.asynchronousHaltEntryStatPhase;
        this.ordinaryHaltWakeStatPhase = mem.ordinaryHaltWakeStatPhase;
        this.haltedCpuCycles = mem.haltedCpuCycles;
        this.hdmaOpcodePrefetched = mem.hdmaOpcodePrefetched;
        this.hdmaArbitrationOpcode = mem.hdmaArbitrationOpcode;
        this.hdmaArbitrationOpcodeValid = mem.hdmaArbitrationOpcodeValid;
        this.haltPrefetchedOpcode = mem.haltPrefetchedOpcode;
        this.haltOpcodePrefetchValid = mem.haltOpcodePrefetchValid;
        this.speedSwitchPaddingOpcode = mem.speedSwitchPaddingOpcode;
        this.speedSwitchPaddingReplayValid = mem.speedSwitchPaddingReplayValid;
        this.speedSwitchTicks = mem.speedSwitchTicks;
        this.phasedPpuInputHigh = mem.phasedPpuInputHigh;
        this.fastPhasedPpuDispatch = mem.fastPhasedPpuDispatch;
        this.stopFrameBlankRequested = false;
        this.debugInstructionKnown = false;

        // EXT_OPCODE after CB means the second byte has not reached the decoder yet.
        boolean extendedOpcodePending = opcode1 == 0xcb && this.state == State.EXT_OPCODE;
        setCurrentOpcode(extendedOpcodePending ? null : (opcode1 == 0xcb
                ? Opcodes.EXT_COMMANDS.get(opcode2) : Opcodes.COMMANDS.get(opcode1)));
        this.ops = (currentOpcode == null) ? null : currentOpcode.getOps();
    }

    private void setCurrentOpcode(Opcode opcode) {
        currentOpcode = opcode;
        if (opcode == null) {
            currentExecutionOps = null;
            currentOpAccessesMemory = null;
            currentOpWritesMemory = null;
            currentOpCount = 0;
            currentOperandLength = 0;
            return;
        }
        currentExecutionOps = opcode.getExecutionOps();
        currentOpAccessesMemory = opcode.getAccessesMemory();
        currentOpWritesMemory = opcode.getWritesMemory();
        currentOpCount = currentExecutionOps.length;
        currentOperandLength = opcode.getOperandLength();
    }

    /**
     * Allocation-free transient bus fence used only while a PERFORMANCE epoch owns the
     * CPU. Safe accesses continue through the real bus. An external read is delegated once
     * after the prefix callback; an external write is deferred to the one-slot journal.
     */
    private static final class PerformanceEpochBus implements AddressSpace {

        private final Cpu owner;

        private AddressSpace target;

        private int accesses;

        private int terminalAccesses;

        private PerformanceEpochBus(Cpu owner) {
            this.owner = owner;
        }

        private void resetForEpoch(AddressSpace target) {
            this.target = target;
            accesses = 0;
            terminalAccesses = 0;
        }

        private int accesses() {
            return accesses;
        }

        private int terminalAccesses() {
            return terminalAccesses;
        }

        @Override
        public boolean accepts(int address) {
            return target.accepts(address);
        }

        @Override
        public int getByte(int address) {
            accesses++;
            if (!owner.isPerformanceEpochSafeRead(address)) {
                terminalAccesses++;
                owner.markPerformanceEpochTerminal();
                owner.flushPerformanceEpochPrefix();
            }
            return target.getByte(address);
        }

        @Override
        public void setByte(int address, int value) {
            accesses++;
            if (!owner.isPerformanceEpochSafeWrite(address)) {
                int a = address & 0xffff;
                if (!owner.speedMode.isGbc() && a >= 0xfe00 && a <= 0xfeff) {
                    // DMG OAM writes must observe the corruption-suppression latch and access
                    // lock at this exact CPU boundary. The preceding corruption callback has
                    // already flushed the prefix for PUSH-class writes; plain LD writes flush
                    // here. Never defer either through the post-PPU journal.
                    owner.markPerformanceEpochTerminal();
                    terminalAccesses++;
                    owner.flushPerformanceEpochPrefix();
                    target.setByte(address, value);
                    return;
                }
                // Record before dispatching.  The CPU operation itself is allowed to finish,
                // but the external write is deferred so the owner can replay it exactly once
                // after committing the frozen peripheral prefix.
                owner.journalPerformanceEpochWrite(address, value);
                owner.markPerformanceEpochTerminal();
                terminalAccesses++;
                owner.flushPerformanceEpochPrefix();
                return;
            }
            target.setByte(address, value);
        }

        private static boolean isSafeRead(int address) {
            int a = address & 0xffff;
            return a < 0x8000
                    || a >= 0xc000 && a <= 0xfdff
                    || a >= 0xff80 && a <= 0xfffd;
        }

        private static boolean isSafeWrite(int address) {
            int a = address & 0xffff;
            return a >= 0xc000 && a <= 0xfdff
                    || a >= 0xff80 && a <= 0xfffd;
        }

        private static boolean isVideoRam(int address) {
            int a = address & 0xffff;
            return a >= 0x8000 && a <= 0x9fff;
        }
    }

    private record CpuState(ComponentState<Registers> registersMemento, int opcode1, int opcode2, int[] operand,
                              int operandIndex, int opIndex, State state, int opContext, int interruptFlag,
                              int interruptEnabled, InterruptManager.InterruptType requestedIrq, int clockCycle,
                              boolean haltBugMode, int haltEntrySampleTicks,
                              boolean synchronousHaltEntryStatPhase,
                              boolean asynchronousHaltEntryStatPhase,
                              boolean ordinaryHaltWakeStatPhase,
                              int haltedCpuCycles,
                              boolean hdmaOpcodePrefetched,
                              int hdmaArbitrationOpcode, boolean hdmaArbitrationOpcodeValid,
                              int haltPrefetchedOpcode, boolean haltOpcodePrefetchValid,
                              int speedSwitchPaddingOpcode, boolean speedSwitchPaddingReplayValid,
                              int speedSwitchTicks, boolean phasedPpuInputHigh,
                              boolean fastPhasedPpuDispatch) implements ComponentState<Cpu> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record CpuMemento(Memento<Registers> registersMemento, int opcode1, int opcode2, int[] operand,
                              int operandIndex, int opIndex, State state, int opContext, int interruptFlag,
                              int interruptEnabled, InterruptManager.InterruptType requestedIrq, int clockCycle,
                              boolean haltBugMode, int haltEntrySampleTicks,
                              boolean synchronousHaltEntryStatPhase,
                              boolean asynchronousHaltEntryStatPhase,
                              boolean ordinaryHaltWakeStatPhase,
                              int haltedCpuCycles,
                              boolean hdmaOpcodePrefetched,
                              int hdmaArbitrationOpcode, boolean hdmaArbitrationOpcodeValid,
                              int haltPrefetchedOpcode, boolean haltOpcodePrefetchValid,
                              int speedSwitchPaddingOpcode, boolean speedSwitchPaddingReplayValid,
                              int speedSwitchTicks, boolean phasedPpuInputHigh,
                              boolean fastPhasedPpuDispatch) implements Memento<Cpu> {
    }

}
