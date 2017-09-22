package eu.rekawek.coffeegb.debug;

import java.util.Optional;
import java.util.Set;

public class CommandArgument {

    private final String name;

    private final boolean required;

    private final Optional<Set<String>> allowedValues;

    public CommandArgument(String name, boolean required) {
        this.name = name;
        this.required = required;
        this.allowedValues = Optional.empty();
    }

    public CommandArgument(String name, boolean required, Set<String> allowedValues) {
        this.name = name;
        this.required = required;
        this.allowedValues = Optional.of(allowedValues);
    }

    public String getName() {
        return name;
    }

    public boolean isRequired() {
        return required;
    }

    public Optional<Set<String>> getAllowedValues() {
        return allowedValues;
    }

    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();
        if (!required) {
            builder.append('[');
        }
        if (allowedValues.isPresent()) {
            builder.append('{');
            builder.append(String.join(",", allowedValues.get()));
            builder.append('}');
        } else {
            builder.append(name.toUpperCase());
        }
        if (!required) {
            builder.append(']');
        }
        return builder.toString();
    }
}
