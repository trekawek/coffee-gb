package eu.rekawek.coffeegb.debug.command;

import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandPattern;
import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;

public class ShowOpcode implements Command {

    private static final CommandPattern PATTERN = CommandPattern.Builder
            .create("show opcode")
            .withRequiredArgument("opcode")
            .withDescription("displays opcode information for hex (0xFA) or name (LD A,B) identifier")
            .build();

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(ParsedCommandLine commandLine) {
        System.out.println("opcode: " + commandLine.getArgument("opcode"));
        System.out.println("remaining: " + commandLine.getRemainingArguments());
    }
}
