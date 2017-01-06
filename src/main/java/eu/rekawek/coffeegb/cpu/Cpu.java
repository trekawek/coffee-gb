package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.cpu.op.Op;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Cpu {

    private enum State {
        OPCODE, EXT_OPCODE, OPERAND, RUNNING
    }

    private static final Logger LOG = LoggerFactory.getLogger(Cpu.class);

    private final Registers registers;

    private final AddressSpace addressSpace;

    private int opcode1, opcode2;

    private int[] operand = new int[2];

    private Opcode currentOpcode;

    private int commandStart;

    private int operandIndex;

    private int opIndex;

    private State state = State.OPCODE;

    private int opContext;

    private int trace;

    public Cpu(AddressSpace addressSpace) {
        this.registers = new Registers();
        this.addressSpace = addressSpace;
    }

    public void tick() {
        int pc = registers.getPC();

        switch (state) {
            case OPCODE:
                commandStart = pc;
                opcode1 = addressSpace.getByte(pc);
                if (opcode1 == 0xcb) {
                    state = State.EXT_OPCODE;
                } else {
                    state = State.OPERAND;
                    currentOpcode = Opcodes.COMMANDS.get(opcode1);
                    if (currentOpcode == null) {
                        throw new IllegalStateException(String.format("No command for 0x%02x", opcode1));
                    }
                }
                registers.incrementPC();
                break;

            case EXT_OPCODE:
                opcode2 = addressSpace.getByte(pc);
                currentOpcode = Opcodes.EXT_COMMANDS.get(opcode2);
                if (currentOpcode == null) {
                    throw new IllegalStateException(String.format("No command for %0xcb 0x%02x", opcode2));
                }
                state = State.OPERAND;
                registers.incrementPC();
                break;

            case OPERAND:
                if (operandIndex == 0) {
                    operand[0] = 0x00;
                    operand[1] = 0x00;
                }
                if (operandIndex < currentOpcode.getOperandLength()) {
                    operand[operandIndex++] = addressSpace.getByte(pc);
                    registers.incrementPC();
                    break;
                }
                state = State.RUNNING;
                opIndex = 0;
                opContext = 0;
                // fall through

            case RUNNING:
                while (opIndex < currentOpcode.getOps().size()) {
                    Op op = currentOpcode.getOps().get(opIndex++);
                    opContext = op.execute(registers, addressSpace, operand, opContext);
                    if (!op.proceed(registers)) {
                        opIndex = currentOpcode.getOps().size();
                        break;
                    }
                    if (op.readsMemory() || op.writesMemory()) {
                        break;
                    }
                }
                if (opIndex >= currentOpcode.getOps().size()) {
                    state = State.OPCODE;
                    operandIndex = 0;
                }
                break;
        }
    }

    private void trace() {
        if (++trace % 1000 != 0) {
            return;
        }
        trace = 0;
        String label = currentOpcode.toString();
        label = label.replace("d8", String.format("0x%02x", operand[0]));
        label = label.replace("a8", String.format("0xff00 + 0x%02x", operand[0]));
        label = label.replace("d16", String.format("0x%04x", BitUtils.toWord(operand)));
        label = label.replace("a16", String.format("0x%04x", BitUtils.toWord(operand)));
        label = label.replace("r8", String.format("%s0x%02x", BitUtils.isNegative(operand[0]) ? "-" : "", BitUtils.abs(operand[0])));
        LOG.trace("{} {}", String.format("%04x %6s", commandStart, getDump(commandStart, registers.getPC())), label);
    }

    private boolean handleInterrupt() {
        if (!registers.isIME()) {
            return false;
        }

        int interruptFlag = addressSpace.getByte(0xff0f);
        int interruptEnable = addressSpace.getByte(0xffff);

        int handler = 0;
        if ((interruptEnable & interruptFlag & (1 << 0)) != 0) {
            handler = 0x0040; // V-Blank
        }
        if ((interruptEnable & interruptFlag & (1 << 1)) != 0) {
            handler = 0x0048; // LCDC Status
        }
        if ((interruptEnable & interruptFlag & (1 << 2)) != 0) {
            handler = 0x0050; // Timer Overflow
        }
        if ((interruptEnable & interruptFlag & (1 << 3)) != 0) {
            handler = 0x0058; // Serial Transfer
        }
        if ((interruptEnable & interruptFlag & (1 << 4)) != 0) {
            handler = 0x0060; // Hi-Lo of P10-P13
        }

        if (handler > 0) {
            registers.setIME(false);
            addressSpace.setByte(0xff0f, 0);
            //push(registers.getPC());
            registers.setPC(handler);
            return true;
        } else {
            return false;
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

}