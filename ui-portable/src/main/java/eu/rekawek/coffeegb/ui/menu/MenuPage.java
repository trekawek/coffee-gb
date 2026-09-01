package eu.rekawek.coffeegb.ui.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable content definition for one menu route. */
final class MenuPage {

    private final MenuRoute route;
    private final String title;
    private final String context;
    private final String headerAction;
    private final String sideHeading;
    private final List<String> sideLines;
    private final List<MenuItem> items;
    private final int columns;
    private final List<String> footerHints;
    private final String preferredFocusId;
    private final MenuPreview preview;

    MenuPage(MenuRoute route, String title, String context, String headerAction,
            String sideHeading, List<String> sideLines, List<MenuItem> items, int columns,
            List<String> footerHints) {
        this(route, title, context, headerAction, sideHeading, sideLines, items, columns,
                footerHints, null, MenuPreview.empty());
    }

    MenuPage(MenuRoute route, String title, String context, String headerAction,
            String sideHeading, List<String> sideLines, List<MenuItem> items, int columns,
            List<String> footerHints, String preferredFocusId, MenuPreview preview) {
        if (route == null) {
            throw new IllegalArgumentException("route cannot be null");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("A menu page needs at least one item");
        }
        this.route = route;
        this.title = text(title, "title");
        this.context = text(context, "context");
        this.headerAction = text(headerAction, "headerAction");
        this.sideHeading = text(sideHeading, "sideHeading");
        this.sideLines = immutableStrings(sideLines, "sideLines");
        this.items = immutableItems(items);
        this.columns = 1;
        this.footerHints = immutableStrings(footerHints, "footerHints");
        this.preferredFocusId = preferredFocusId;
        this.preview = java.util.Objects.requireNonNull(preview, "preview");
        if (firstEnabledIndex() < 0) {
            throw new IllegalArgumentException("A menu page needs an enabled item");
        }
    }

    static MenuPage from(MenuPageSpec spec) {
        ArrayList<MenuItem> items = new ArrayList<>(spec.items().size());
        for (MenuPageSpec.Item item : spec.items()) {
            // Back is a global B action, never a row. Keeping it out of the immutable page model
            // prevents the reducer from focusing a row that the compositor intentionally hides.
            if ("back".equals(item.id())) {
                continue;
            }
            items.add(new MenuItem(item.id(), item.label(), item.detail(), item.enabled(),
                    item.secondaryId(), item.widgetType(), item.progress()));
        }
        if (items.isEmpty()) {
            throw new IllegalArgumentException("A menu page needs a non-back item");
        }
        return new MenuPage(spec.route(), spec.title(), spec.context(), spec.headerAction(),
                spec.sideHeading(), spec.sideLines(), items, spec.columns(), spec.footerHints(),
                spec.preferredFocusId(), spec.preview());
    }

    MenuRoute route() {
        return route;
    }

    String title() {
        return title;
    }

    String context() {
        return context;
    }

    String headerAction() {
        return headerAction;
    }

    String sideHeading() {
        return sideHeading;
    }

    List<String> sideLines() {
        return sideLines;
    }

    List<MenuItem> items() {
        return items;
    }

    int columns() {
        return columns;
    }

    List<String> footerHints() {
        return footerHints;
    }

    MenuPreview preview() {
        return preview;
    }

    int initialFocusIndex() {
        if (preferredFocusId != null) {
            int preferred = enabledIndex(preferredFocusId);
            if (preferred >= 0) {
                return preferred;
            }
        }
        return firstEnabledIndex();
    }

    int enabledIndex(String id) {
        if (id == null) {
            return -1;
        }
        for (int index = 0; index < items.size(); index++) {
            MenuItem item = items.get(index);
            if (item.enabled() && id.equals(item.id())) {
                return index;
            }
        }
        return -1;
    }

    int firstEnabledIndex() {
        for (int index = 0; index < items.size(); index++) {
            if (items.get(index).enabled()) {
                return index;
            }
        }
        return -1;
    }

    MenuPresentation presentation(int focusedIndex) {
        ArrayList<MenuPresentation.Item> rows = new ArrayList<>(items.size());
        for (MenuItem item : items) {
            rows.add(item.presentation());
        }
        return new MenuPresentation(true, route, title, context, headerAction, sideHeading,
                sideLines, rows, focusedIndex, columns, footerHints, preview);
    }

    private static String text(String value, String name) {
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
            copy.add(text(value, name + " entry"));
        }
        return Collections.unmodifiableList(copy);
    }

    private static List<MenuItem> immutableItems(List<MenuItem> values) {
        ArrayList<MenuItem> copy = new ArrayList<>(values.size());
        for (MenuItem value : values) {
            if (value == null) {
                throw new IllegalArgumentException("items cannot contain null");
            }
            copy.add(value);
        }
        return Collections.unmodifiableList(copy);
    }
}

final class MenuItem {

    private final String id;
    private final String label;
    private final String detail;
    private final boolean enabled;
    private final String secondaryId;
    private final MenuWidgetType widgetType;
    private final int progress;

    MenuItem(String id, String label) {
        this(id, label, "", true, null, false, -1);
    }

    MenuItem(String id, String label, String detail) {
        this(id, label, detail, true, null, false, -1);
    }

    MenuItem(String id, String label, String detail, boolean enabled) {
        this(id, label, detail, enabled, null, false, -1);
    }

    MenuItem(String id, String label, String detail, boolean enabled, String secondaryId) {
        this(id, label, detail, enabled, secondaryId, MenuWidgetType.BUTTON, -1);
    }

    MenuItem(String id, String label, String detail, boolean enabled, String secondaryId,
            boolean adjustable, int progress) {
        this(id, label, detail, enabled, secondaryId,
                adjustable ? MenuWidgetType.SLIDER : MenuWidgetType.BUTTON, progress);
    }

    MenuItem(String id, String label, String detail, boolean enabled, String secondaryId,
            MenuWidgetType widgetType) {
        this(id, label, detail, enabled, secondaryId, widgetType, -1);
    }

    MenuItem(String id, String label, String detail, boolean enabled, String secondaryId,
            MenuWidgetType widgetType, int progress) {
        this.id = text(id, "id");
        this.label = text(label, "label");
        this.detail = text(detail, "detail");
        this.enabled = enabled;
        this.secondaryId = secondaryId == null ? null : text(secondaryId, "secondaryId");
        this.widgetType = java.util.Objects.requireNonNull(widgetType, "widgetType");
        this.progress = progress(progress);
    }

    String id() {
        return id;
    }

    String label() {
        return label;
    }

    String detail() {
        return detail;
    }

    boolean enabled() {
        return enabled;
    }

    String secondaryId() {
        return secondaryId;
    }

    MenuWidgetType widgetType() {
        return widgetType;
    }

    boolean adjustable() {
        return widgetType.adjustable();
    }

    int progress() {
        return progress;
    }

    MenuPresentation.Item presentation() {
        return new MenuPresentation.Item(
                id, label, detail, enabled, secondaryId, widgetType, progress);
    }

    private static String text(String value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name + " cannot be null");
        }
        return value;
    }

    private static int progress(int value) {
        if (value < -1 || value > 100) {
            throw new IllegalArgumentException("progress must be absent or between 0 and 100");
        }
        return value;
    }
}
