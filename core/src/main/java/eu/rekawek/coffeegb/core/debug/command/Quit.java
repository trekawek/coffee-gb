package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.Command;
import eu.rekawek.coffeegb.core.debug.CommandPattern;

public class Quit implements Command {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("quit", "q")
                    .withDescription("closes the debugger console")
                    .build();

    private final Runnable stop;

    public Quit(Runnable stop) {
        this.stop = java.util.Objects.requireNonNull(stop, "stop");
    }

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        stop.run();
    }
}
