package eu.rekawek.coffeegb.debug;

import com.google.common.collect.ImmutableList;

import java.util.*;

import static com.google.common.collect.Maps.newLinkedHashMap;

public class CommandPattern {

    private final List<String> commandNames;

    private final List<CommandArgument> arguments;

    private final String description;

    private CommandPattern(Builder builder) {
        this.commandNames = builder.commandNames;
        this.arguments = builder.arguments;
        this.description = builder.description;
    }

    public boolean matches(String commandLine) {
        return commandNames
                .stream()
                .filter(commandLine::startsWith)
                .map(String::length)
                .map(commandLine::substring)
                .anyMatch(s -> s.isEmpty() || s.charAt(0) == ' ');
    }

    public List<String> getCommandNames() {
        return commandNames;
    }

    public List<CommandArgument> getArguments() {
        return arguments;
    }

    public Optional<String> getDescription() {
        return Optional.ofNullable(description);
    }

    public ParsedCommandLine parse(String commandLine) {
        String commandName = commandNames
                .stream()
                .filter(commandLine::startsWith)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Command line [" + commandLine + "] doesn't match command [" + commandNames + "]"));
        List<String> split = split(commandLine.substring(commandName.length()));
        Map<String, String> map = newLinkedHashMap();
        List<String> remaining = Collections.emptyList();
        int i;
        for (i = 0; i < split.size() && i < arguments.size(); i++) {
            String value = split.get(i);
            CommandArgument argDef = arguments.get(i);
            Optional<Set<String>> allowed = argDef.getAllowedValues();
            if (allowed.isPresent()) {
                if (!allowed.get().contains(value)) {
                    throw new IllegalArgumentException("Value " + value + " is not allowed for argument " + argDef.getName() + ". Allowed values: " + allowed.get());
                }
            }
            map.put(argDef.getName(), value);
        }
        if (i < arguments.size()) {
            CommandArgument argDef = arguments.get(i);
            if (argDef.isRequired()) {
                throw new IllegalArgumentException("Argument " + argDef.getName() + " is required");
            }
        }
        if (i < split.size()) {
            remaining = split.subList(i, split.size());
        }
        return new ParsedCommandLine(map, remaining);
    }

    private static List<String> split(String str) {
        List<String> split = new ArrayList<>();
        boolean isEscaped = false;
        StringBuilder currentArg = new StringBuilder();
        for (int i = 0; i <= str.length(); i++) {
            char c;
            if (i < str.length()) {
                c = str.charAt(i);
            } else {
                c = 0;
            }
            if (isEscaped) {
                switch (c) {
                    case '"':
                        isEscaped = false;
                        split.add(currentArg.toString());
                        currentArg.setLength(0);
                        break;

                    case 0:
                        throw new IllegalArgumentException("Missing closing quote");

                    default:
                        currentArg.append(c);
                        break;
                }
            } else {
                switch (c) {
                    case '"':
                        isEscaped = true;
                        break;

                    case ' ':
                    case 0:
                        if (currentArg.length() > 0) {
                            split.add(currentArg.toString());
                            currentArg.setLength(0);
                        }
                        break;

                    default:
                        currentArg.append(c);
                        break;
                }
            }
        }
        return split;
    }

    @Override
    public String toString() {
        return String.format("CommandPattern[%s]", commandNames.toString());
    }

    public static class ParsedCommandLine {

        private final Map<String, String> argumentMap;

        private final List<String> remainingArguments;

        private ParsedCommandLine(Map<String, String> argumentMap, List<String> remainingArguments) {
            this.argumentMap = argumentMap;
            this.remainingArguments = remainingArguments;
        }

        public String getArgument(String name) {
            return argumentMap.get(name);
        }

        public List<String> getRemainingArguments() {
            return remainingArguments;
        }
    }

    public static class Builder {

        private final List<String> commandNames;

        private final List<CommandArgument> arguments;

        private String description;

        private Builder(String[] commandNames) {
            this.commandNames = ImmutableList.copyOf(commandNames);
            this.arguments = new ArrayList<>();
        }

        public static Builder create(String longName) {
            return new Builder(new String[] {longName});
        }

        public static Builder create(String longName, String shortName) {
            return new Builder(new String[] {longName, shortName});
        }

        public Builder withRequiredArgument(String name) {
            assertNoOptionalLastArgument();
            arguments.add(new CommandArgument(name, true));
            return this;
        }

        public Builder withDescription(String description) {
            this.description = description;
            return this;
        }

        private void assertNoOptionalLastArgument() {
            if (!arguments.isEmpty() && !arguments.get(arguments.size() - 1).isRequired()) {
                throw new UnsupportedOperationException("Can't add argument after the optional one");
            }
        }

        public CommandPattern build() {
            return new CommandPattern(this);
        }
    }

}
