package eu.rekawek.coffeegb.debug;

import eu.rekawek.coffeegb.Gameboy;
import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;
import eu.rekawek.coffeegb.debug.command.Quit;
import eu.rekawek.coffeegb.debug.command.ShowHelp;
import eu.rekawek.coffeegb.debug.command.apu.Channel;
import eu.rekawek.coffeegb.debug.command.cpu.ShowOpcode;
import eu.rekawek.coffeegb.debug.command.cpu.ShowOpcodes;
import eu.rekawek.coffeegb.debug.command.ppu.ShowBackground;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Semaphore;

public class Console implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Console.class);

    private final Deque<CommandExecution> commandBuffer = new ConcurrentLinkedDeque<>();

    private final Semaphore semaphore = new Semaphore(0);

    private volatile boolean doStop;

    private List<Command> commands;

    public Console() {
        commands = new ArrayList<>();
        commands.add(new ShowHelp(commands));
        commands.add(new ShowOpcode());
        commands.add(new ShowOpcodes());
        commands.add(new Quit());

        commands.sort(Comparator.comparing(c -> c.getPattern().getCommandNames().get(0)));
    }

    public void setGameboy(Gameboy gameboy) {
        // TODO support for commands working in gameboy context
    }

    @Override
    public void run() {
        LineReader lineReader = LineReaderBuilder
                .builder()
                .build();

        while (!doStop) {
            try {
                String line = lineReader.readLine("coffee-gb> ");
                for (Command cmd : commands) {
                    if (cmd.getPattern().matches(line)) {
                        ParsedCommandLine parsed = cmd.getPattern().parse(line);
                        commandBuffer.add(new CommandExecution(cmd, parsed));
                        semaphore.acquire();
                    }
                }
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
            } catch (UserInterruptException e) {
                System.exit(0);
            } catch (InterruptedException e) {
                LOG.error("Interrupted", e);
                break;
            }
        }
    }

    public void tick() {
        while (!commandBuffer.isEmpty()) {
            commandBuffer.poll().run();
            semaphore.release();
        }
    }

    public void stop() {
        doStop = true;
    }

    private static class CommandExecution {

        private final Command command;

        private final ParsedCommandLine arguments;

        public CommandExecution(Command command, ParsedCommandLine arguments) {
            this.command = command;
            this.arguments = arguments;
        }

        public void run() {
            command.run(arguments);
        }
    }
}
