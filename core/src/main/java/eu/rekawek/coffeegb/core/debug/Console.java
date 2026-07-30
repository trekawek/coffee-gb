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
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public class Console implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Console.class);

    private static final long DEFAULT_COMMAND_TIMEOUT_MILLIS = 5_000;

    private volatile boolean doStop;

    private volatile DebugPort debugPort;

    private final List<Command> commands;

    private final PrintStream error;

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

    @Override
    public void run() {
        LineReader lineReader = LineReaderBuilder.builder().build();

        while (!doStop) {
            try {
                String line = lineReader.readLine("coffee-gb> ");
                executeLine(line);
            } catch (IllegalArgumentException e) {
                error.println(e.getMessage());
            } catch (UserInterruptException e) {
                stop();
            } catch (RuntimeException e) {
                LOG.warn("Console command failed", e);
                error.println("Command failed.");
            }
        }
    }

    /** Executes one parsed command on the caller thread. Machine commands still use DebugPort. */
    void executeLine(String line) {
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
        doStop = true;
    }
}
