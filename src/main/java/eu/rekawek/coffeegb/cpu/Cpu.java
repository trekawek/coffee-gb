package eu.rekawek.coffeegb.cpu;

import eu.rekawek.coffeegb.AddressSpace;

import java.util.List;

import static java.util.Arrays.asList;
import static java.util.Collections.unmodifiableList;

public class Cpu {

    private static final List<Command> COMMANDS;

    static {
        Command[] commands = new Command[0xff];

        regCmd(commands, 0x06, 8, 1, "LD B, {}", (r, m, a) -> r.setB(a[0]));
        regCmd(commands, 0x0e, 8, 1, "LD C, {}", (r, m, a) -> r.setC(a[0]));
        regCmd(commands, 0x16, 8, 1, "LD D, {}", (r, m, a) -> r.setD(a[0]));
        regCmd(commands, 0x1e, 8, 1, "LD E, {}", (r, m, a) -> r.setE(a[0]));
        regCmd(commands, 0x26, 8, 1, "LD H, {}", (r, m, a) -> r.setH(a[0]));
        regCmd(commands, 0x2e, 8, 1, "LD L, {}", (r, m, a) -> r.setL(a[0]));

        COMMANDS = unmodifiableList(asList(commands));
    }

    private final Registers registers;

    private final AddressSpace addressSpace;

    public Cpu(AddressSpace addressSpace) {
        this.registers = new Registers();
        this.addressSpace = addressSpace;
    }

    private static void regCmd(Command[] commands, int opcode, int cycles, int argsLength, String label, Command.Operation operation) {
        commands[opcode] = new Command(opcode, cycles, argsLength, label, operation);
    }

}
