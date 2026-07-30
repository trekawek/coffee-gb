package eu.rekawek.coffeegb.core.debug.command;

import eu.rekawek.coffeegb.core.debug.Command;
import eu.rekawek.coffeegb.core.debug.CommandArgument;
import eu.rekawek.coffeegb.core.debug.CommandPattern;

import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.collect.Maps.newHashMap;

public class ShowHelp implements Command {

    private static final CommandPattern PATTERN =
            CommandPattern.Builder.create("help", "?")
                    .withDescription("displays supported commands")
                    .build();

    private final List<Command> commands;

    private final PrintStream output;

    public ShowHelp(List<Command> commands) {
        this(commands, System.out);
    }

    public ShowHelp(List<Command> commands, PrintStream output) {
        this.commands = commands;
        this.output = java.util.Objects.requireNonNull(output, "output");
    }

    @Override
    public CommandPattern getPattern() {
        return PATTERN;
    }

    @Override
    public void run(CommandPattern.ParsedCommandLine commandLine) {
        int max = 0;
        Map<Command, String> commandMap = newHashMap();
        for (Command command : commands) {
            CommandPattern pattern = command.getPattern();
            String alias = pattern.getCommandNames().get(0);
            String commandWithArgs = getCommandWithArgs(alias, pattern.getArguments());
            if (commandWithArgs.length() > max) {
                max = commandWithArgs.length();
            }
            commandMap.put(command, commandWithArgs);
        }

        for (Command command : commands) {
            CommandPattern pattern = command.getPattern();
            String longName = commandMap.get(command);
            output.printf("%-" + max + "s", longName);
            if (pattern.getCommandNames().size() > 1) {
                output.printf("   %-5s", pattern.getCommandNames().get(1));
            } else {
                output.print("        ");
            }
            command.getPattern().getDescription().map(d -> "   " + d).ifPresent(output::print);
            output.println();
        }
    }

    private String getCommandWithArgs(String alias, List<CommandArgument> args) {
        StringBuilder builder = new StringBuilder(alias);
        if (!args.isEmpty()) {
            builder
                    .append(' ')
                    .append(args.stream().map(CommandArgument::toString).collect(Collectors.joining(" ")));
        }
        return builder.toString();
    }
}
