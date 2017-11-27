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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Semaphore;

public class Console implements Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(Console.class);

    private final Deque<CommandExecution> commandBuffer = new ArrayDeque<>();

    private final Semaphore semaphore = new Semaphore(0);

    private volatile boolean isStarted;

    private List<Command> commands;

    public Console() {
    }

    public void init(Gameboy gameboy) {
        commands = new ArrayList<>();
        commands.add(new ShowHelp(commands));
        commands.add(new ShowOpcode());
        commands.add(new ShowOpcodes());
        commands.add(new Quit());

        commands.add(new ShowBackground(gameboy, ShowBackground.Type.WINDOW));
        commands.add(new ShowBackground(gameboy, ShowBackground.Type.BACKGROUND));
        commands.add(new Channel(gameboy.getSound()));

        Collections.sort(commands, Comparator.comparing(c -> c.getPattern().getCommandNames().get(0)));
    }

    @Override
    public void run() {
        isStarted = true;

        LineReader lineReader = LineReaderBuilder
                .builder()
                .build();

        while (true) {
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
        if (!isStarted) {
            return;
        }

        while (!commandBuffer.isEmpty()) {
            commandBuffer.poll().run();
            semaphore.release();
        }
    }

    private class CommandExecution {

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
