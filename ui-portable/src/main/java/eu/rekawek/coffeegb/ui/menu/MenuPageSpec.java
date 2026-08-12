package eu.rekawek.coffeegb.ui.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable page data supplied by the host coordinator, including dynamic runtime rows. */
public final class MenuPageSpec {

    private final MenuRoute route;
    private final String title;
    private final String context;
    private final String headerAction;
    private final String sideHeading;
    private final List<String> sideLines;
    private final List<Item> items;
    private final int columns;
    private final List<String> footerHints;
    private final String preferredFocusId;
    private final MenuPreview preview;

    public MenuPageSpec(MenuRoute route, String title, String context, String headerAction,
            String sideHeading, List<String> sideLines, List<Item> items, int columns,
            List<String> footerHints) {
        this(route, title, context, headerAction, sideHeading, sideLines, items, columns,
                footerHints, null, MenuPreview.empty());
    }

    public MenuPageSpec(MenuRoute route, String title, String context, String headerAction,
            String sideHeading, List<String> sideLines, List<Item> items, int columns,
            List<String> footerHints, String preferredFocusId, MenuPreview preview) {
        this.route = Objects.requireNonNull(route, "route");
        this.title = text(title, "title");
        this.context = text(context, "context");
        this.headerAction = text(headerAction, "headerAction");
        this.sideHeading = text(sideHeading, "sideHeading");
        this.sideLines = strings(sideLines, "sideLines");
        this.items = items(items);
        this.columns = Math.max(1, columns);
        this.footerHints = strings(footerHints, "footerHints");
        this.preferredFocusId = preferredFocusId == null ? null
                : text(preferredFocusId, "preferredFocusId");
        this.preview = Objects.requireNonNull(preview, "preview");
        boolean enabled = false;
        for (Item item : this.items) {
            if (item.enabled()) {
                enabled = true;
                break;
            }
        }
        if (!enabled) {
            throw new IllegalArgumentException("A menu page needs an enabled item");
        }
    }

    public MenuRoute route() {
        return route;
    }

    public String title() {
        return title;
    }

    public String context() {
        return context;
    }

    public String headerAction() {
        return headerAction;
    }

    public String sideHeading() {
        return sideHeading;
    }

    public List<String> sideLines() {
        return sideLines;
    }

    public List<Item> items() {
        return items;
    }

    public int columns() {
        return columns;
    }

    public List<String> footerHints() {
        return footerHints;
    }

    public String preferredFocusId() {
        return preferredFocusId;
    }

    public MenuPreview preview() {
        return preview;
    }

    private static String text(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }

    private static List<String> strings(List<String> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        ArrayList<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(text(value, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<Item> items(List<Item> values) {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("A menu page needs items");
        }
        ArrayList<Item> copy = new ArrayList<>(values.size());
        for (Item item : values) {
            copy.add(Objects.requireNonNull(item, "items cannot contain null"));
        }
        return Collections.unmodifiableList(copy);
    }

    /** One immutable primary row and optional secondary action. */
    public static final class Item {

        private final String id;
        private final String label;
        private final String detail;
        private final boolean enabled;
        private final String secondaryId;
        private final boolean adjustable;
        private final int progress;

        public Item(String id, String label, String detail, boolean enabled) {
            this(id, label, detail, enabled, null);
        }

        public Item(String id, String label, String detail, boolean enabled, String secondaryId) {
            this(id, label, detail, enabled, secondaryId, false, -1);
        }

        public Item(String id, String label, String detail, boolean enabled, String secondaryId,
                boolean adjustable, int progress) {
            this.id = text(id, "id");
            this.label = text(label, "label");
            this.detail = text(detail, "detail");
            this.enabled = enabled;
            this.secondaryId = secondaryId == null ? null : text(secondaryId, "secondaryId");
            this.adjustable = adjustable;
            if (progress < -1 || progress > 100) {
                throw new IllegalArgumentException("progress must be absent or between 0 and 100");
            }
            this.progress = progress;
        }

        public String id() {
            return id;
        }

        public String label() {
            return label;
        }

        public String detail() {
            return detail;
        }

        public boolean enabled() {
            return enabled;
        }

        public String secondaryId() {
            return secondaryId;
        }

        public boolean adjustable() {
            return adjustable;
        }

        public int progress() {
            return progress;
        }
    }
}
