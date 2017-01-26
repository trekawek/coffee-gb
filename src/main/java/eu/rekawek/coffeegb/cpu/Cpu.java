package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.op.Op;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.gpu.Gpu;
import eu.rekawek.coffeegb.gpu.GpuRegister;
import eu.rekawek.coffeegb.gpu.Lcdc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

public class Cpu {

    enum State {
        OPCODE, EXT_OPCODE, OPERAND, RUNNING, IRQ_READ_IF, IRQ_READ_IE, IRQ_PUSH_1, IRQ_PUSH_2_AND_JUMP, STOPPED, HALTED
    }

    private static final Logger LOG = LoggerFactory.getLogger(Cpu.class);

    private final Random random = new Random();

    private final Registers registers;

    private final AddressSpace addressSpace;

    private final InterruptManager interruptManager;

    private final Gpu gpu;

    private int opcode1, opcode2;

    private int[] operand = new int[2];

    private Opcode currentOpcode;

    private List<Op> ops;

    private int commandStart;

    private int operandIndex;

    private int opIndex;

    private State state = State.OPCODE;

    private int opContext;

    private int interruptFlag;

    private int interruptEnabled;

    private InterruptManager.InterruptType requestedIrq;

    private int clockCycle = 0;

    private boolean haltBugMode;

    public Cpu(AddressSpace addressSpace, InterruptManager interruptManager, Gpu gpu) {
        this.registers = new Registers();
        this.addressSpace = addressSpace;
        this.interruptManager = interruptManager;
        this.gpu = gpu;
    }

    public void tick() {
        if (++clockCycle == 4) {
            clockCycle = 0;
        } else {
            return;
        }

        if (state == State.OPCODE || state == State.HALTED || state == State.STOPPED) {
            if (interruptManager.isInterruptRequested()) {
                state = State.IRQ_READ_IF;
            }
        }

        if (state == State.IRQ_READ_IF || state == State.IRQ_READ_IE || state == State.IRQ_PUSH_1 || state == State.IRQ_PUSH_2_AND_JUMP) {
            handleInterrupt();
            return;
        }

        if (state == State.HALTED && interruptManager.isInterruptFlagSet()) {
            state = State.OPCODE;
        }

        if (state == State.HALTED || state == State.STOPPED) {
            return;
        }

        int pc = registers.getPC();
        boolean accessedMemory = false;
        while (true) {
            switch (state) {
                case OPCODE:
                    clearState();
                    commandStart = pc;
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
                        state = State.STOPPED;
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
                        if (op.causesOemBug(registers, opContext)) {
                            handleSpriteBug();
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
                interruptManager.flush();
                if (requestedIrq == null) {
                    state = State.OPCODE;
                } else {
                    state = State.IRQ_PUSH_1;
                    interruptManager.disableInterrupts(false);
                }
                break;

            case IRQ_PUSH_1:
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), (registers.getPC() & 0xff00) >> 8);
                state = State.IRQ_PUSH_2_AND_JUMP;
                break;

            case IRQ_PUSH_2_AND_JUMP:
                registers.decrementSP();
                addressSpace.setByte(registers.getSP(), registers.getPC() & 0x00ff);

                registers.setPC(requestedIrq.getHandler());
                requestedIrq = null;

                state = State.OPCODE;
                break;
        }
    }

    private void trace() {
        String label = currentOpcode.toString();
        label = label.replace("d8", String.format("0x%02x", operand[0]));
        label = label.replace("a8", String.format("0xff00 + 0x%02x", operand[0]));
        label = label.replace("d16", String.format("0x%04x", BitUtils.toWord(operand)));
        label = label.replace("a16", String.format("0x%04x", BitUtils.toWord(operand)));
        label = label.replace("r8", String.format("%s0x%02x", BitUtils.toSigned(operand[0])));
        System.out.println(String.format("%04x %6s %s", commandStart, getDump(commandStart, registers.getPC()), label));
    }

    private void handleSpriteBug() {
        Lcdc lcdc = new Lcdc(addressSpace.getByte(GpuRegister.LCDC.getAddress()));
        if (!lcdc.isLcdEnabled()) {
            return;
        }
        int stat = addressSpace.getByte(GpuRegister.STAT.getAddress());
        if ((stat & 0b11) == Gpu.Mode.OamSearch.ordinal() && gpu.getTicksInLine() < 79) {
            for (int i = 0xfe08; i < 0xff00; i++) {
                addressSpace.setByte(i, random.nextInt(256));
            }
        }
    }

    public Registers getRegisters() {
        return registers;
    }

    private String getDump(int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < to; i++) {
            builder.append(String.format("%02x", addressSpace.getByte(i) & 0xff));
        }
        return builder.toString();
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


    State getState() {
        return state;
    }

    Opcode getCurrentOpcode() {
        return currentOpcode;
    }

}