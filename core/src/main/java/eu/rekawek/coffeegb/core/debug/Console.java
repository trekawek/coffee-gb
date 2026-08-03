package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.CommandPattern.ParsedCommandLine;
import eu.rekawek.coffeegb.core.debug.command.Pause;
import eu.rekawek.coffeegb.core.debug.command.Quit;
import eu.rekawek.coffeegb.core.debug.command.ReadMemory;
import eu.rekawek.coffeegb.core.debug.command.Resume;
import eu.rekawek.coffeegb.core.debug.command.ShowHelp;
import eu.rekawek.coffeegb.core.debug.command.ShowState;
import eu.rekawek.coffeegb.core.debug.command.Step;
import eu.rekawek.coffeegb.core.debug.command.cpu.ShowOpcode;
import eu.rekawek.coffeegb.core.debug.command.cpu.ShowOpcodes;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Platform-neutral command processor for the debugger.
 *
 * <p>Presentation owns line editing and input blocking. Desktop front ends use their JLine
 * adapter, while other hosts may feed complete lines directly through {@link #executeLine}.
 */
public class Console {

    private static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = 5_000;

    private volatile boolean stopped;

    private volatile DebugPort debugPort;

    private final List<Command> commands;

    protected final PrintStream error;

    public Console() {
        this(System.out, System.err, DEFAULT_COMMAND_TIMEOUT_MILLIS);
    }

    Console(PrintStream output, PrintStream error, long commandTimeoutMillis) {
        Objects.requireNonNull(output, "output");
        this.error = Objects.requireNonNull(error, "error");
        if (commandTimeoutMillis <= 0) {
            throw new IllegalArgumentException("Command timeout must be positive");
        }
        commands = new ArrayList<>();
        commands.add(new Pause(this::getDebugPort, commandTimeoutMillis, output, error));
        commands.add(new Quit(this::stop));
        commands.add(new ReadMemory(this::getDebugPort, commandTimeoutMillis, output, error));
        commands.add(new Resume(this::getDebugPort, commandTimeoutMillis, output, error));
        commands.add(new ShowHelp(commands, output));
        commands.add(new ShowOpcode(output));
        commands.add(new ShowOpcodes(output));
        commands.add(new ShowState(this::getDebugPort, commandTimeoutMillis, output, error));
        commands.add(new Step(this::getDebugPort, commandTimeoutMillis, output, error));

        commands.sort(Comparator.comparing(c -> c.getPattern().getCommandNames().get(0)));
    }

    /** Attaches the currently committed session's immutable, queued debug API. */
    public void setDebugPort(DebugPort debugPort) {
        this.debugPort = debugPort;
    }

    /** Returns the session token used by temporary console-command adapters, if any. */
    public DebugPort getDebugPort() {
        return debugPort;
    }

    /** Executes one parsed command on the caller thread. Machine commands still use DebugPort. */
    public void executeLine(String line) {
        Objects.requireNonNull(line, "line");
        for (Command cmd : commands) {
            if (cmd.getPattern().matches(line)) {
                ParsedCommandLine parsed = cmd.getPattern().parse(line);
                cmd.run(parsed);
                return;
            }
        }
        if (!line.isBlank()) {
            error.println("Unknown command. Type 'help' to list available commands.");
        }
    }

    public void stop() {
        stopped = true;
    }

    /** Returns whether the host-facing command loop should stop. */
    protected final boolean isStopped() {
        return stopped;
    }
}
