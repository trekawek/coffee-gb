package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.DebugInterruptType;
import eu.rekawek.coffeegb.core.debug.DebugMemoryAccess;
import eu.rekawek.coffeegb.core.cpu.op.Op;
import eu.rekawek.coffeegb.core.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.util.List;

public class Cpu implements StatefulComponent<Cpu> {

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

    private AddressSpace addressSpace;

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
        this.addressSpace = addressSpace;
        this.interruptManager = interruptManager;
        this.gpu = gpu;
        this.speedMode = speedMode;
        this.display = display;
        this.timer = timer;
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

    /** Advances one master tick, retaining the historical scalar entry point. */
    public void tick() {
        if (!tickPhaseOnly()) {
            tickAtMachineCycle();
        }
    }

    private boolean isJoypadLineLow() {
        return (addressSpace.getByte(0xff00) & 0x0f) != 0x0f;
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
