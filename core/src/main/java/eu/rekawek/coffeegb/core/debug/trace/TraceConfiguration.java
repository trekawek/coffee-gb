package eu.rekawek.coffeegb.core.debug.trace;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Fixed trace-ring capacity and capture categories negotiated for one trace attachment. */
public record TraceConfiguration(
        int capacity,
        Set<TraceCategory> categories,
        TraceFilter filter) {

    public static final int MAX_CAPACITY = 1_048_576;

    public TraceConfiguration {
        TraceChecks.range("capacity", capacity, 1, MAX_CAPACITY);
        Objects.requireNonNull(categories, "categories");
        EnumSet<TraceCategory> copy = categories.isEmpty()
                ? EnumSet.noneOf(TraceCategory.class)
                : EnumSet.copyOf(categories);
        categories = Collections.unmodifiableSet(copy);
        Objects.requireNonNull(filter, "filter");
    }

    public TraceConfiguration(int capacity, Set<TraceCategory> categories) {
        this(capacity, categories, TraceFilter.all());
    }

    public static TraceConfiguration allCategories(int capacity) {
        return new TraceConfiguration(
                capacity, EnumSet.allOf(TraceCategory.class), TraceFilter.all());
    }

    public static TraceConfiguration disabled(int capacity) {
        return new TraceConfiguration(
                capacity, EnumSet.noneOf(TraceCategory.class), TraceFilter.all());
    }

    public boolean isEnabled(TraceCategory category) {
        return categories.contains(Objects.requireNonNull(category, "category"));
    }

    public boolean isEnabled() {
        return !categories.isEmpty();
    }
}
