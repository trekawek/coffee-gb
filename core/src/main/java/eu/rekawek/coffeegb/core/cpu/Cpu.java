package eu.rekawek.coffeegb.core.cpu;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.cpu.op.Op;
import eu.rekawek.coffeegb.core.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.core.gpu.*;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;

import java.io.Serializable;
import java.util.List;

public class Cpu implements Serializable, Originator<Cpu> {

    public enum State {
        OPCODE, EXT_OPCODE, OPERAND, RUNNING, IRQ_WAIT_1, IRQ_WAIT_2, IRQ_PUSH_1, IRQ_PUSH_2, IRQ_JUMP, STOPPED, HALTED,
        SPEED_SWITCH,
        // an illegal opcode was executed: the CPU is frozen for good (hardware hangs)
        LOCKED
    }

    // Fixed 4.19 MHz PPU-clock ticks. The PPU continues throughout the pause.
    private static final int SPEED_SWITCH_DELAY = 65_544;

    private final Registers registers;

    private final AddressSpace addressSpace;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Display display;

    private final SpeedMode speedMode;

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

    private int speedSwitchTicks;

    private boolean stopFrameBlankRequested;

    public Cpu(AddressSpace addressSpace, InterruptManager interruptManager, Gpu gpu, SpeedMode speedMode,
               Display display) {
        this.registers = new Registers();
        this.addressSpace = addressSpace;
        this.interruptManager = interruptManager;
        this.gpu = gpu;
        this.speedMode = speedMode;
        this.display = display;
    }

    public void tick() {
        if (state == State.SPEED_SWITCH) {
            if (speedSwitchTicks > 0) {
                speedSwitchTicks--;
            }
            if (speedSwitchTicks == 0) {
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

        if (state == State.HALTED && interruptManager.isInterruptRequestedForHalt()) {
            // a halted CPU behaves exactly like it was executing NOPs, so the wake-up
            // has the same timing as the running state: the interrupt dispatch starts
            // (IME=1) or the next instruction is fetched (IME=0) at the cycle following
            // the interrupt request (halt_ime1_timing2-GS, halt_ime0_nointr_timing)
            state = State.OPCODE;
        }

        if (state == State.OPCODE || state == State.STOPPED) {
            if (interruptManager.isIme() && interruptManager.isInterruptRequested()) {
                if (state == State.STOPPED) {
                    display.enableLcd();
                }
                state = State.IRQ_WAIT_1;
            }
        }

        if (state == State.IRQ_WAIT_1 || state == State.IRQ_WAIT_2 || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2 || state == State.IRQ_JUMP) {
            handleInterrupt();
            return;
        }

        if (state == State.HALTED || state == State.STOPPED) {
            return;
        }

        boolean accessedMemory = false;
        while (true) {
            int pc = registers.getPC();
            switch (state) {
                case OPCODE:
                    clearState();
                    opcode1 = addressSpace.getByte(pc);
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
                    if (!haltBugMode) {
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
                        if (speedMode.onStop()) {
                            // A CGB speed switch resets and freezes DIV while the CPU clock
                            // is stopped. The PPU remains in its independent clock domain.
                            addressSpace.setByte(0xff04, 0);
                            speedSwitchTicks = SPEED_SWITCH_DELAY;
                            state = State.SPEED_SWITCH;
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
                            state = State.OPCODE;
                            return;
                        }
                        if (interruptManager.isHaltBug()) {
                            state = State.OPCODE;
                            haltBugMode = true;
                            return;
                        } else {
                            state = State.HALTED;
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

    public boolean isSpeedSwitching() {
        return state == State.SPEED_SWITCH;
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
                speedSwitchTicks);
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
        this.speedSwitchTicks = mem.speedSwitchTicks;
        this.stopFrameBlankRequested = false;

        this.currentOpcode = (opcode1 == 0xcb) ? Opcodes.EXT_COMMANDS.get(opcode2) : Opcodes.COMMANDS.get(opcode1);
        this.ops = (currentOpcode == null) ? null : currentOpcode.getOps();
    }

    private record CpuMemento(Memento<Registers> registersMemento, int opcode1, int opcode2, int[] operand,
                              int operandIndex, int opIndex, State state, int opContext, int interruptFlag,
                              int interruptEnabled, InterruptManager.InterruptType requestedIrq, int clockCycle,
                              boolean haltBugMode, int speedSwitchTicks) implements Memento<Cpu> {
    }

}
