package eu.rekawek.coffeegb.debug.command.cpu;

import eu.rekawek.coffeegb.cpu.Opcodes;
import eu.rekawek.coffeegb.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandPattern;
import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;

import java.util.List;

public class ShowOpcodes implements Command {

    private static final CommandPattern PATTERN = CommandPattern.Builder
            .create("cpu show opcodes")
            .withDescription("displays all opcodes")
            .build();

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(ParsedCommandLine commandLine) {
        printTable(Opcodes.COMMANDS);
        System.out.println("\n0xCB");
        printTable(Opcodes.EXT_COMMANDS);
    }

    private static void printTable(List<Opcode> opcodes) {
        System.out.print("   ");
        for (int i = 0; i < 0x10; i++) {
            System.out.printf("%02X          ", i);
        }
        System.out.println();

        for (int i = 0; i < 0x100; i += 0x10) {
            System.out.printf("%02X ", i);
            for (int j = 0; j < 0x10; j++) {
                Opcode opcode = opcodes.get(i + j);
                String label = opcode == null ? "-" : opcode.getLabel();
                System.out.printf("%-12s", label);
            }
            System.out.println();
        }
    }
}
