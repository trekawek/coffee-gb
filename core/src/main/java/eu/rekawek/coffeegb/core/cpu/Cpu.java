package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.op.Op;
import eu.rekawek.coffeegb.core.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.timer.Timer;

import java.io.Serializable;
import java.util.List;

public class Cpu implements Serializable, Originator<Cpu> {

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

    private final AddressSpace addressSpace;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Display display;

    private final SpeedMode speedMode;

    private final Timer timer;

    private int opcode1, opcode2;

    private final int[] operand = new int[2];

    private Opcode currentOpcode;

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

    public Cpu(AddressSpace addressSpace, InterruptManager interruptManager, Gpu gpu, SpeedMode speedMode,
               Display display) {
        this(addressSpace, interruptManager, gpu, speedMode, display, null);
    }

    public Cpu(AddressSpace addressSpace, InterruptManager interruptManager, Gpu gpu, SpeedMode speedMode,
               Display display, Timer timer) {
        this.registers = new Registers();
        this.addressSpace = addressSpace;
        this.interruptManager = interruptManager;
        this.gpu = gpu;
        this.speedMode = speedMode;
        this.display = display;
        this.timer = timer;
    }

    public void tick() {
        // VRAM DMA performs the next opcode fetch before taking the bus. Once the
        // burst releases the CPU, this ordinary machine cycle consumes that held
        // opcode and resumes the instruction pipeline.
        hdmaOpcodePrefetched = false;

        boolean phasedPpuInput = interruptManager.isPhasedMode2InterruptRequested();
        if (phasedPpuInput && !phasedPpuInputHigh) {
            int cpuCycleTicks = 4 / speedMode.getSpeedMode();
            fastPhasedPpuDispatch = (cpuCycleTicks == 4 && clockCycle == 1)
                    || interruptManager.isFirstLineMode2InterruptRequested();
        } else if (!phasedPpuInput) {
            fastPhasedPpuDispatch = false;
        }
        phasedPpuInputHigh = phasedPpuInput;

        if (state == State.SPEED_SWITCH) {
            if (speedSwitchTicks > 0) {
                speedSwitchTicks -= speedMode.getSpeedMode();
            }
            if (speedSwitchTicks <= 0) {
                speedSwitchTicks = 0;
                state = State.OPCODE;
            }
            return;
        }

        if (++clockCycle >= (4 / speedMode.getSpeedMode())) {
            clockCycle = 0;
        } else {
            return;
        }

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

        if (state == State.OPCODE || state == State.STOPPED) {
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
                    clearState();
                    opcode1 = useHdmaHaltPrefetch
                            ? haltPrefetchedOpcode
                            : useSpeedSwitchPadding
                            ? speedSwitchPaddingOpcode
                            : useHdmaArbitrationOpcode
                            ? hdmaArbitrationOpcode
                            : addressSpace.getByte(pc);
                    accessedMemory = true;
                    if (opcode1 == 0xcb) {
                        state = State.EXT_OPCODE;
                    } else if (opcode1 == 0x10) {
                        currentOpcode = Opcodes.COMMANDS.get(opcode1);
                        state = State.EXT_OPCODE;
                    } else {
                        state = State.OPERAND;
                        currentOpcode = Opcodes.COMMANDS.get(opcode1);
                        if (currentOpcode == null) {
                            // an illegal opcode freezes the CPU on hardware; do the
                            // same instead of crashing the emulation thread
                            state = State.LOCKED;
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
                    opcode2 = addressSpace.getByte(pc);
                    if (currentOpcode == null) {
                        currentOpcode = Opcodes.EXT_COMMANDS.get(opcode2);
                    }
                    if (currentOpcode == null) {
                        state = State.LOCKED;
                        return;
                    }
                    state = State.OPERAND;
                    registers.incrementPC();
                    break;

                case OPERAND:
                    while (operandIndex < currentOpcode.getOperandLength()) {
                        if (accessedMemory) {
                            return;
                        }
                        accessedMemory = true;
                        operand[operandIndex++] = addressSpace.getByte(pc);
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
                        return;
                    } else if (opcode1 == 0x76) {
                        // HALT always samples the next opcode. It is normally fetched
                        // again on wake, but a simultaneously acknowledged HDMA request
                        // turns this sample into the held pipeline opcode.
                        haltPrefetchedOpcode = addressSpace.getByte(registers.getPC());
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
                            return;
                        } else {
                            state = State.HALTED;
                            synchronousHaltEntryStatPhase = false;
                            asynchronousHaltEntryStatPhase = false;
                            ordinaryHaltWakeStatPhase = false;
                            haltedCpuCycles = 0;
                            haltEntrySampleTicks = speedMode.isGbc() ? 2 : 4;
                            return;
                        }
                    }

                    if (opIndex < ops.size()) {
                        Op op = ops.get(opIndex);
                        boolean opAccessesMemory = op.readsMemory() || op.writesMemory();
                        if (accessedMemory && opAccessesMemory) {
                            return;
                        }
                        opIndex++;

                        SpriteBug.CorruptionType corruptionType = op.causesOemBug(registers, opContext);
                        if (corruptionType != null) {
                            handleSpriteBug(corruptionType);
                        }
                        opContext = op.execute(registers, addressSpace, operand, opContext);
                        op.switchInterrupts(interruptManager);

                        if (!op.proceed(registers)) {
                            opIndex = ops.size();
                            break;
                        }

                        if (op.forceFinishCycle()) {
                            return;
                        }

                        if (opAccessesMemory) {
                            accessedMemory = true;
                        }
                    }

                    if (opIndex >= ops.size()) {
                        state = State.OPCODE;
                        operandIndex = 0;
                        interruptManager.onInstructionFinished();
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
                    registers.setPC(requestedIrq.getHandler());
                } else {
                    registers.setPC(0x0000);
                }
                requestedIrq = null;
                state = State.OPCODE;
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
        Opcode decodedOpcode = Opcodes.COMMANDS.get(addressSpace.getByte(pc));
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
            decodedOperand[i] = addressSpace.getByte(operandAddress);
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
        clearState();
        opcode1 = useSpeedSwitchPadding
                ? speedSwitchPaddingOpcode
                : useHdmaArbitrationOpcode
                ? hdmaArbitrationOpcode
                : addressSpace.getByte(pc);
        if (opcode1 == 0xcb || opcode1 == 0x10) {
            state = State.EXT_OPCODE;
            if (opcode1 == 0x10) {
                currentOpcode = Opcodes.COMMANDS.get(opcode1);
            }
        } else {
            currentOpcode = Opcodes.COMMANDS.get(opcode1);
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
                haltPrefetchedOpcode = gpu.getVideoRam().getByte(pc);
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
            hdmaArbitrationOpcode = addressSpace.getByte(pc);
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
        return clockCycle >= 2 && state == State.RUNNING && ops != null
                && opIndex < ops.size() && ops.get(opIndex).writesMemory();
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
    public Memento<Cpu> saveToMemento() {
        int[] operand = new int[2];
        operand[0] = this.operand[0];
        operand[1] = this.operand[1];
        return new CpuMemento(registers.saveToMemento(), opcode1, opcode2, operand, operandIndex, opIndex,
                state, opContext, interruptFlag, interruptEnabled, requestedIrq, clockCycle, haltBugMode,
                haltEntrySampleTicks, synchronousHaltEntryStatPhase, asynchronousHaltEntryStatPhase,
                ordinaryHaltWakeStatPhase, haltedCpuCycles,
                hdmaOpcodePrefetched, hdmaArbitrationOpcode, hdmaArbitrationOpcodeValid,
                haltPrefetchedOpcode, haltOpcodePrefetchValid,
                speedSwitchPaddingOpcode, speedSwitchPaddingReplayValid,
                speedSwitchTicks, phasedPpuInputHigh, fastPhasedPpuDispatch);
    }

    @Override
    public void restoreFromMemento(Memento<Cpu> memento) {
        if (!(memento instanceof CpuMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.registers.restoreFromMemento(mem.registersMemento);
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

        this.currentOpcode = (opcode1 == 0xcb) ? Opcodes.EXT_COMMANDS.get(opcode2) : Opcodes.COMMANDS.get(opcode1);
        this.ops = (currentOpcode == null) ? null : currentOpcode.getOps();
    }

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
