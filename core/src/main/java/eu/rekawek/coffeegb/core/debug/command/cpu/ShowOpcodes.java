package eu.rekawek.coffeegb.core.debug.command.cpu;

import eu.rekawek.coffeegb.core.cpu.Opcodes;
import eu.rekawek.coffeegb.core.cpu.opcode.Opcode;
import eu.rekawek.coffeegb.core.debug.Command;
import eu.rekawek.coffeegb.core.debug.CommandPattern;
import eu.rekawek.coffeegb.core.debug.CommandPattern.ParsedCommandLine;

import java.io.PrintStream;
import java.util.List;
import java.util.Objects;

public class ShowOpcodes implements Command {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("cpu show opcodes")
                    .withDescription("displays all opcodes")
                    .build();

    private final PrintStream output;

    public ShowOpcodes() {
        this(System.out);
    }

    public ShowOpcodes(PrintStream output) {
        this.output = Objects.requireNonNull(output, "output");
    }

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(ParsedCommandLine commandLine) {
        printTable(Opcodes.COMMANDS);
        output.println("\n0xCB");
        printTable(Opcodes.EXT_COMMANDS);
    }

    private void printTable(List<Opcode> opcodes) {
        output.print("   ");
        for (int i = 0; i < 0x10; i++) {
            output.printf("%02X          ", i);
        }
        output.println();

        for (int i = 0; i < 0x100; i += 0x10) {
            output.printf("%02X ", i);
            for (int j = 0; j < 0x10; j++) {
                Opcode opcode = opcodes.get(i + j);
                String label = opcode == null ? "-" : opcode.getLabel();
                output.printf("%-12s", label);
            }
            output.println();
        }
    }
}
