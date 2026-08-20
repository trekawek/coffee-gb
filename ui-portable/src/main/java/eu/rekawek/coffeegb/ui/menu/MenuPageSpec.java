package eu.rekawek.coffeegb.ui.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable page data supplied by the host coordinator, including dynamic runtime rows. */
public final class MenuPageSpec {

    /** Host-owned recent-game metadata used by the portable Recent Games page. */
    public record RecentGame(String id, String name, String lastPlayed, boolean enabled,
            MenuPreview preview) {
        public RecentGame {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("recent game id cannot be blank");
            }
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("recent game name cannot be blank");
            }
            if (lastPlayed == null) {
                throw new IllegalArgumentException("lastPlayed cannot be null");
            }
            preview = Objects.requireNonNull(preview, "preview");
        }
    }

    /**
     * Builds the host-fed Recent Games page. The selected entry's detached preview and
     * last-played text are placed in the left panel; callers can rebuild the immutable page after
     * focus moves, just as they do for the Load State page.
     */
    public static MenuPageSpec recentGames(List<RecentGame> games, String focusedId) {
        Objects.requireNonNull(games, "games");
        ArrayList<RecentGame> copied = new ArrayList<>(games.size());
        for (RecentGame game : games) {
            copied.add(Objects.requireNonNull(game, "games cannot contain null"));
        }
        ArrayList<Item> items = new ArrayList<>(copied.size());
        for (RecentGame game : copied) {
            items.add(new Item(game.id(), game.name(), "", game.enabled()));
        }
        String selectedId = focusedId;
        RecentGame selected = null;
        if (selectedId != null) {
            for (RecentGame game : copied) {
                if (selectedId.equals(game.id())) {
                    selected = game;
                    break;
                }
            }
        }
        if (items.isEmpty()) {
            items.add(new Item("recent-games-status", "NO RECENT GAMES", "", true));
            selectedId = "recent-games-status";
        } else if (selected == null) {
            for (RecentGame game : copied) {
                if (game.enabled()) {
                    selected = game;
                    selectedId = game.id();
                    break;
                }
            }
            if (selected == null) {
                // Keep unavailable entries visible for honest history, but provide one inert
                // enabled status row so the reducer always has a safe focus target.
                items.add(new Item("recent-games-status", "NO READABLE RECENT GAMES", "", true));
                selectedId = "recent-games-status";
            }
        }
        List<String> sideLines = selected == null || selected.lastPlayed().isBlank()
                ? List.of()
                : List.of("LAST PLAYED: " + selected.lastPlayed());
        MenuPreview preview = selected == null ? MenuPreview.empty() : selected.preview();
        List<String> footer = "recent-games-status".equals(selectedId)
                ? List.of("", "", "B BACK")
                : List.of("D-PAD MOVE", "A OPEN", "B BACK");
        return new MenuPageSpec(MenuRoute.RECENT_GAMES, "COFFEE GB", "RECENT GAMES", "", "",
                sideLines, items, 1, footer, selectedId, preview);
    }

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
