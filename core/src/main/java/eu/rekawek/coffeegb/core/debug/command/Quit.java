package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.Command;
import eu.rekawek.coffeegb.core.debug.CommandPattern;

public class Quit implements Command {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("quit", "q").withDescription("quits the emulator").build();

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        System.exit(0);
    }
}
