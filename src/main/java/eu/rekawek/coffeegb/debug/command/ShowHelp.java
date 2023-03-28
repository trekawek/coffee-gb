package eu.rekawek.coffeegb.debug.command;

import eu.rekawek.coffeegb.debug.Command;
import eu.rekawek.coffeegb.debug.CommandArgument;
import eu.rekawek.coffeegb.debug.CommandPattern;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.google.common.collect.Maps.newHashMap;

public class ShowHelp implements Command {

    private static final CommandPattern PATTERN = CommandPattern.Builder
            .create("help", "?")
            .withDescription("displays supported commands")
            .build();

    private final List<Command> commands;

    public ShowHelp(List<Command> commands) {
        this.commands = commands;
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
            System.out.printf("%-" + max + "s", longName);
            if (pattern.getCommandNames().size() > 1) {
                System.out.printf("   %-5s", pattern.getCommandNames().get(1));
            } else {
                System.out.print("        ");
            }
            command.getPattern()
                    .getDescription()
                    .map(d -> "   " + d)
                    .ifPresent(System.out::print);
            System.out.println();
        }
    }

    private String getCommandWithArgs(String alias, List<CommandArgument> args) {
        StringBuilder builder = new StringBuilder(alias);
        if (!args.isEmpty()) {
            builder.append(' ')
                    .append(args
                            .stream()
                            .map(CommandArgument::toString)
                            .collect(Collectors.joining(" ")));
        }
        return builder.toString();
    }
}
