package eu.rekawek.coffeegb.core.debug;

import eu.rekawek.coffeegb.core.debug.CommandPattern.ParsedCommandLine;
import eu.rekawek.coffeegb.core.debug.command.Quit;
import eu.rekawek.coffeegb.core.debug.command.ShowHelp;
import eu.rekawek.coffeegb.core.debug.command.cpu.ShowOpcode;
import eu.rekawek.coffeegb.core.debug.command.cpu.ShowOpcodes;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Console implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Console.class);

    private volatile boolean doStop;

    private volatile DebugPort debugPort;

    private List<Command> commands;

    public Console() {
        commands = new ArrayList<>();
        commands.add(new ShowHelp(commands));
        commands.add(new ShowOpcode());
        commands.add(new ShowOpcodes());
        commands.add(new Quit(this::stop));

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
                boolean matched = false;
                for (Command cmd : commands) {
                    if (cmd.getPattern().matches(line)) {
                        matched = true;
                        ParsedCommandLine parsed = cmd.getPattern().parse(line);
                        // Static commands execute on the console thread. Future machine-context
                        // commands use debugPort and wait here for their queued result; the core
                        // no longer polls a console buffer from Gameboy.tick().
                        cmd.run(parsed);
                        break;
                    }
                }
                if (!matched && !line.isBlank()) {
                    System.err.println("Unknown command. Type 'help' to list available commands.");
                }
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
            } catch (UserInterruptException e) {
                stop();
            } catch (RuntimeException e) {
                LOG.warn("Console command failed", e);
                System.err.println("Command failed.");
            }
        }
    }

    public void stop() {
        doStop = true;
    }
}
