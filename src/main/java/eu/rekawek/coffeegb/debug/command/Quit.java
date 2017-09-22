package eu.rekawek.coffeegb.debug.command;

import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandPattern;

public class Quit implements Command {

    private static final CommandPattern PATTERN = CommandPattern.Builder
            .create("quit", "q")
            .withDescription("quits the emulator")
            .build();

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        System.exit(0);
    }
}
