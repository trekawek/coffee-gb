package eu.rekawek.coffeegb.debug;

import eu.rekawek.coffeegb.debug.CommandPattern.ParsedCommandLine;
import eu.rekawek.coffeegb.debug.command.Quit;
import eu.rekawek.coffeegb.debug.command.ShowHelp;
import eu.rekawek.coffeegb.debug.command.ShowOpcode;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

public class Console implements Runnable {

    private final BlockingDeque<CommandExecution> commandBuffer = new LinkedBlockingDeque<>(1);

    private volatile boolean isStarted;

    private final List<Command> commands;

    public Console() {
        commands = new ArrayList<>();
        commands.add(new ShowHelp(commands));
        commands.add(new ShowOpcode());
        commands.add(new Quit());
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
                            commandBuffer.offer(new CommandExecution(cmd, parsed));
                    }
                }
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
            } catch (UserInterruptException e) {
                System.exit(0);
            }
        }
    }

    public void tick() {
        if (!isStarted) {
            return;
        }

        while (!commandBuffer.isEmpty()) {
            commandBuffer.poll().run();
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
