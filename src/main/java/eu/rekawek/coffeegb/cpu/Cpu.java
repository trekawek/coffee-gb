package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static jdk.nashorn.internal.objects.NativeArray.push;

public class Cpu {

    private static final Logger LOG = LoggerFactory.getLogger(Cpu.class);

    private final Registers registers;

    private final AddressSpace addressSpace;

    public Cpu(AddressSpace addressSpace) {
        this.registers = new Registers();
        this.addressSpace = addressSpace;
    }

    public int runCommand() {
        handleInterrupt();

        int pc = registers.getPC();
        int opcode = addressSpace.getByte(pc++);
        Command cmd;
        if (opcode == 0xcb) {
            opcode = addressSpace.getByte(pc++);
            cmd = Opcodes.EXT_COMMANDS.get(opcode);
        } else {
            cmd = Opcodes.COMMANDS.get(opcode);
        }

        if (cmd == null) {
            LOG.warn(String.format("Invalid instruction %02x @ %04x", opcode, registers.getPC()));
            return 0;
        }

        int[] args = new int[cmd.getArgsLength()];
        for (int i = 0; i < args.length; i++) {
            args[i] = addressSpace.getByte(pc++);
        }

        if (LOG.isTraceEnabled()) {
            LOG.trace(String.format("%04x: %8s\t%s", registers.getPC(), getDump(registers.getPC(), pc), cmd.getLabel()));
        }

        registers.setPC(pc);
        cmd.getOperation().run(registers, addressSpace, args);

        if (LOG.isTraceEnabled()) {
            LOG.trace("Registers: {}", registers);
        }

        return cmd.getCycles();
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
            push(registers.getPC());
            registers.setPC(handler);
            return true;
        } else {
            return false;
        }
    }

    private String getDump(int from, int to) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < to; i++) {
            builder.append(String.format("%02x", addressSpace.getByte(i) & 0xff));
        }
        return builder.toString();
    }

}