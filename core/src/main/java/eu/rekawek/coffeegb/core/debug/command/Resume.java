package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.CommandPattern;
import eu.rekawek.coffeegb.core.debug.DebugPort;
import eu.rekawek.coffeegb.core.debug.DebugResult;
import eu.rekawek.coffeegb.core.debug.DebugSnapshot;

import java.io.PrintStream;
import java.util.function.Supplier;

public final class Resume extends DebugPortCommand {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("resume", "r")
                    .withDescription("releases the debugger-owned pause")
                    .build();

    public Resume(
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
        DebugResult<DebugSnapshot> result = await(port.resume());
        if (result == null) return;
        DebugSnapshot snapshot = result.value();
        output.printf(
                "Resumed at tick %d, PC=%04X, paused=%s.%n",
                snapshot.masterTick(), snapshot.registers().pc(), snapshot.paused());
    }
}
