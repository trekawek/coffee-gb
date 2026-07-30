package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.CommandPattern;
import eu.rekawek.coffeegb.core.debug.DebugPort;
import eu.rekawek.coffeegb.core.debug.DebugResult;
import eu.rekawek.coffeegb.core.debug.DebugSnapshot;

import java.io.PrintStream;
import java.util.function.Supplier;

public final class Pause extends DebugPortCommand {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("pause", "p")
                    .withDescription("pauses at the next documented safe point")
                    .build();

    public Pause(
            Supplier<DebugPort> debugPortSupplier,
            long timeoutMillis,
            PrintStream output,
            PrintStream error) {
        super(debugPortSupplier, timeoutMillis, output, error);
    }

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        requireNoExtraArguments(commandLine);
        DebugPort port = activePort();
        if (port == null) return;
        DebugResult<DebugSnapshot> result = await(port.pause());
        if (result == null) return;
        DebugSnapshot snapshot = result.value();
        output.printf(
                "Paused at tick %d, PC=%04X, frame=%d+%d.%n",
                snapshot.masterTick(),
                snapshot.registers().pc(),
                snapshot.frame(),
                snapshot.framePosition());
    }
}
