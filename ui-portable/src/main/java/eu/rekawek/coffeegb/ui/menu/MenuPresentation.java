package eu.rekawek.coffeegb.ui.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable renderer handoff for one menu frame.
 *
 * <p>The reducer owns construction. Consumers can retain a snapshot without synchronizing with
 * the controller or host thread; all strings and collections exposed by this class are immutable.
 */
public final class MenuPresentation {

    private static final MenuPresentation HIDDEN = new MenuPresentation(
            false, null, "", "", "", "", Collections.emptyList(), Collections.emptyList(),
            -1, 1, Collections.emptyList(), MenuPreview.empty());

    private final boolean visible;
    private final MenuRoute route;
    private final String title;
    private final String context;
    private final String headerAction;
    private final String sideHeading;
    private final List<String> sideLines;
    private final List<Item> items;
    private final int focusedIndex;
    private final int columns;
    private final List<String> footerHints;
    private final MenuPreview preview;

    MenuPresentation(boolean visible, MenuRoute route, String title, String context,
            String headerAction, String sideHeading, List<String> sideLines, List<Item> items,
            int focusedIndex, int columns, List<String> footerHints, MenuPreview preview) {
        this.visible = visible;
        this.route = route;
        this.title = requireText(title, "title");
        this.context = requireText(context, "context");
        this.headerAction = requireText(headerAction, "headerAction");
        this.sideHeading = requireText(sideHeading, "sideHeading");
        this.sideLines = immutableStrings(sideLines, "sideLines");
        this.items = immutableItems(items);
        this.focusedIndex = focusedIndex;
        this.columns = 1;
        this.footerHints = immutableStrings(footerHints, "footerHints");
        this.preview = java.util.Objects.requireNonNull(preview, "preview");
        if (visible && route == null) {
            throw new IllegalArgumentException("A visible menu needs a route");
        }
        if (focusedIndex < -1 || focusedIndex >= this.items.size()) {
            throw new IllegalArgumentException("Focused item is outside the presentation");
        }
    }

    static MenuPresentation hidden() {
        return HIDDEN;
    }

    public boolean visible() {
        return visible;
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

    public int focusedIndex() {
        return focusedIndex;
    }

    public int columns() {
        return columns;
    }

    public List<String> footerHints() {
        return footerHints;
    }

    public MenuPreview preview() {
        return preview;
    }

    private static String requireText(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }

    private static List<String> immutableStrings(List<String> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        ArrayList<String> copy = new ArrayList<>(values.size());
        for (String value : values) {
            copy.add(requireText(value, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<Item> immutableItems(List<Item> values) {
        if (values == null) {
            throw new IllegalArgumentException("items cannot be null");
        }
        ArrayList<Item> copy = new ArrayList<>(values.size());
        for (Item value : values) {
            if (value == null) {
                throw new IllegalArgumentException("items cannot contain null");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }

    /** Immutable row data exposed to platform renderers. */
    public static final class Item {

        private final String id;
        private final String label;
        private final String detail;
        private final boolean enabled;
        private final String secondaryId;
        private final MenuWidgetType widgetType;
        private final int progress;
        private final boolean checked;

        public Item(String id, String label, String detail, boolean enabled) {
            this(id, label, detail, enabled, null);
        }

        public Item(String id, String label, String detail, boolean enabled, String secondaryId) {
            this(id, label, detail, enabled, secondaryId, MenuWidgetType.BUTTON, -1);
        }

        /**
         * Legacy constructor retained for renderer and host source compatibility.
         */
        public Item(String id, String label, String detail, boolean enabled, String secondaryId,
                boolean adjustable, int progress) {
            this(id, label, detail, enabled, secondaryId,
                    adjustable ? MenuWidgetType.SLIDER : MenuWidgetType.BUTTON, progress);
        }

        public Item(String id, String label, String detail, boolean enabled, String secondaryId,
                MenuWidgetType widgetType) {
            this(id, label, detail, enabled, secondaryId, widgetType, -1);
        }

        public Item(String id, String label, String detail, boolean enabled, String secondaryId,
                MenuWidgetType widgetType, int progress) {
            this(id, label, detail, enabled, secondaryId, widgetType, progress,
                    widgetType != null && widgetType.checkedFrom(detail));
        }

        Item(String id, String label, String detail, boolean enabled, String secondaryId,
                MenuWidgetType widgetType, int progress, boolean checked) {
            this.id = requireText(id, "id");
            this.label = requireText(label, "label");
            this.detail = requireText(detail, "detail");
            this.enabled = enabled;
            this.secondaryId = secondaryId == null ? null : requireText(secondaryId, "secondaryId");
            this.widgetType = java.util.Objects.requireNonNull(widgetType, "widgetType");
            this.progress = progress(progress);
            this.checked = widgetType == MenuWidgetType.CHECKBOX && checked;
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

        public MenuWidgetType widgetType() {
            return widgetType;
        }

        public boolean adjustable() {
            return widgetType.adjustable();
        }

        public int progress() {
            return progress;
        }

        /** Returns the checkbox state; non-checkbox widgets always return {@code false}. */
        public boolean checked() {
            return checked;
        }

        private static int progress(int value) {
            if (value < -1 || value > 100) {
                throw new IllegalArgumentException(
                        "progress must be absent or between 0 and 100");
            }
            return value;
        }
    }
}
