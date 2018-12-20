package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.op.Op;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.gpu.Display;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.gpu.GpuRegister;
import eu.rekawek.coffeegb.gpu.Lcdc;
import eu.rekawek.coffeegb.gpu.SpriteBug;

import java.util.List;

public class Cpu {

    public enum State {
        OPCODE, EXT_OPCODE, OPERAND, RUNNING, IRQ_READ_IF, IRQ_READ_IE, IRQ_PUSH_1, IRQ_PUSH_2, IRQ_JUMP, STOPPED, HALTED
    }

    private final Registers registers;

    private final AddressSpace addressSpace;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private final Display display;

    private final SpeedMode speedMode;

    private int opcode1, opcode2;

    private int[] operand = new int[2];

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

    public Cpu(AddressSpace addressSpace, InterruptManager interruptManager, Gpu gpu, Display display, SpeedMode speedMode) {
        this.registers = new Registers();
        this.addressSpace = addressSpace;
        this.interruptManager = interruptManager;
        this.gpu = gpu;
        this.display = display;
        this.speedMode = speedMode;
    }

    public void tick() {
        if (++clockCycle >= (4 / speedMode.getSpeedMode())) {
            clockCycle = 0;
        } else {
            return;
        }

        if (state == State.OPCODE || state == State.HALTED || state == State.STOPPED) {
            if (interruptManager.isIme() && interruptManager.isInterruptRequested()) {
                if (state == State.STOPPED) {
                    display.enableLcd();
                }
                state = State.IRQ_READ_IF;
            }
        }

        if (state == State.IRQ_READ_IF || state == State.IRQ_READ_IE || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2 || state == State.IRQ_JUMP) {
            handleInterrupt();
            return;
        }

        if (state == State.HALTED && interruptManager.isInterruptRequested()) {
            state = State.OPCODE;
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
                            throw new IllegalStateException(String.format("No command for 0x%02x", opcode1));
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
                        throw new IllegalStateException(String.format("No command for %0xcb 0x%02x", opcode2));
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
                            state = State.OPCODE;
                        } else {
                            state = State.STOPPED;
                            display.disableLcd();
                        }
                        return;
                    } else if (opcode1 == 0x76) {
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
                    return;
            }
        }
    }

    private void handleInterrupt() {
        switch(state) {
            case IRQ_READ_IF:
                interruptFlag = addressSpace.getByte(0xff0f);
                state = State.IRQ_READ_IE;
                break;

            case IRQ_READ_IE:
                interruptEnabled = addressSpace.getByte(0xffff);
                requestedIrq = null;
                for (InterruptManager.InterruptType irq : InterruptManager.InterruptType.values()) {
                    if ((interruptFlag & interruptEnabled & (1 << irq.ordinal())) != 0) {
                        requestedIrq = irq;
                        break;
                    }
                }
                if (requestedIrq == null) {
                    state = State.OPCODE;
                } else {
                    state = State.IRQ_PUSH_1;
                    interruptManager.clearInterrupt(requestedIrq);
                    interruptManager.disableInterrupts(false);
                }
                break;

            case IRQ_PUSH_1:
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), (registers.getPC() & 0xff00) >> 8);
                state = State.IRQ_PUSH_2;
                break;

            case IRQ_PUSH_2:
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), registers.getPC() & 0x00ff);
                state = State.IRQ_JUMP;
                break;

            case IRQ_JUMP:
                registers.setPC(requestedIrq.getHandler());
                requestedIrq = null;
                state = State.OPCODE;
                break;

        }
    }

    private void handleSpriteBug(SpriteBug.CorruptionType type) {
        if (!gpu.getLcdc().isLcdEnabled()) {
            return;
        }
        int stat = addressSpace.getByte(GpuRegister.STAT.getAddress());
        if ((stat & 0b11) == Gpu.Mode.OamSearch.ordinal() && gpu.getTicksInLine() < 79) {
            SpriteBug.corruptOam(addressSpace, type, gpu.getTicksInLine());
        }
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

    Opcode getCurrentOpcode() {
        return currentOpcode;
    }

}