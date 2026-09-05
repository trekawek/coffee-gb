package eu.rekawek.coffeegb.ui.menu;

import java.util.ArrayList;
import java.util.List;

/** Pure reducer for menu visibility, focus, and route-stack navigation. */
final class MenuReducer {

    private MenuReducer() {
    }

    static MenuState initial() {
        return MenuState.hidden();
    }

    static MenuState reduce(MenuState state, MenuCommand command) {
        if (state == null || command == null) {
            throw new IllegalArgumentException("state and command are required");
        }
        return switch (command.type()) {
            case SHOW -> show(state, command.route());
            case HIDE -> MenuState.hidden();
            case MOVE -> move(state, command.direction());
            case PUSH -> push(state, command.route());
            case BACK -> back(state);
        };
    }

    static MenuState show(MenuState state, MenuRoute route) {
        requireRoute(route);
        MenuPage page = MenuPages.forRoute(route);
        return show(state, page);
    }

    static MenuState show(MenuState state, MenuPage page) {
        if (page == null) {
            throw new IllegalArgumentException("page is required");
        }
        return MenuState.visible(page, page.initialFocusIndex());
    }

    static MenuState hide(MenuState state) {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        return MenuState.hidden();
    }

    static MenuState push(MenuState state, MenuRoute route) {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        requireRoute(route);
        MenuPage page = MenuPages.forRoute(route);
        return push(state, page);
    }

    static MenuState push(MenuState state, MenuPage page) {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (page == null) {
            throw new IllegalArgumentException("page is required");
        }
        if (!state.visible()) {
            return MenuState.visible(page, page.initialFocusIndex());
        }
        ArrayList<MenuState.Frame> stack = new ArrayList<>(state.stack());
        stack.add(new MenuState.Frame(page, page.initialFocusIndex()));
        return MenuState.withStack(stack);
    }

    static MenuState replaceCurrent(MenuState state, MenuPage page) {
        if (state == null || page == null) {
            throw new IllegalArgumentException("state and page are required");
        }
        if (!state.visible()) {
            return state;
        }
        String focusedId = state.focusedItemId();
        int focusedIndex = page.enabledIndex(focusedId);
        if (focusedIndex < 0) {
            focusedIndex = page.initialFocusIndex();
        }
        ArrayList<MenuState.Frame> stack = new ArrayList<>(state.stack());
        stack.set(stack.size() - 1, new MenuState.Frame(page, focusedIndex));
        return MenuState.withStack(stack);
    }

    static MenuState replacePage(MenuState state, MenuPage page) {
        if (state == null || page == null) {
            throw new IllegalArgumentException("state and page are required");
        }
        if (!state.visible()) {
            return state;
        }
        ArrayList<MenuState.Frame> stack = new ArrayList<>(state.stack());
        for (int index = 0; index < stack.size(); index++) {
            MenuState.Frame frame = stack.get(index);
            if (frame.page().route() != page.route()) {
                continue;
            }
            String focusedId = frame.page().items().get(frame.focusedIndex()).id();
            int focusedIndex = page.enabledIndex(focusedId);
            if (focusedIndex < 0) {
                focusedIndex = page.initialFocusIndex();
            }
            stack.set(index, new MenuState.Frame(page, focusedIndex));
        }
        return MenuState.withStack(stack);
    }

    static MenuState replacePageAndFocus(MenuState state, MenuPage page, String focusedItemId) {
        if (state == null || page == null || focusedItemId == null) {
            throw new IllegalArgumentException("state, page and focused item id are required");
        }
        int focusedIndex = page.enabledIndex(focusedItemId);
        if (focusedIndex < 0) {
            throw new IllegalArgumentException("Focused item must be enabled and present");
        }
        if (!state.visible()) {
            return state;
        }
        ArrayList<MenuState.Frame> stack = new ArrayList<>(state.stack());
        for (int index = 0; index < stack.size(); index++) {
            MenuState.Frame frame = stack.get(index);
            if (frame.page().route() == page.route()) {
                stack.set(index, new MenuState.Frame(page, focusedIndex));
            }
        }
        return MenuState.withStack(stack);
    }

    static MenuState restore(List<MenuPage> pages, List<String> focusedItemIds) {
        if (pages == null || focusedItemIds == null || pages.size() != focusedItemIds.size()) {
            throw new IllegalArgumentException("pages and focused ids must have equal size");
        }
        ArrayList<MenuState.Frame> stack = new ArrayList<>(pages.size());
        for (int index = 0; index < pages.size(); index++) {
            MenuPage page = pages.get(index);
            int focus = page.enabledIndex(focusedItemIds.get(index));
            if (focus < 0) {
                focus = page.initialFocusIndex();
            }
            stack.add(new MenuState.Frame(page, focus));
        }
        return MenuState.withStack(stack);
    }

    static MenuState back(MenuState state) {
        if (state == null) {
            throw new IllegalArgumentException("state is required");
        }
        if (!state.visible() || state.depth() == 1) {
            return MenuState.hidden();
        }
        List<MenuState.Frame> stack = state.stack();
        return MenuState.withStack(stack.subList(0, stack.size() - 1));
    }

    static MenuState move(MenuState state, MenuCommand.Direction direction) {
        if (state == null || direction == null) {
            throw new IllegalArgumentException("state and direction are required");
        }
        if (!state.visible()) {
            return state;
        }
        MenuPage page = state.page();
        int next = nextEnabledIndex(page, state.focusedIndex(), direction);
        if (next == state.focusedIndex()) {
            return state;
        }
        ArrayList<MenuState.Frame> stack = new ArrayList<>(state.stack());
        stack.set(stack.size() - 1, new MenuState.Frame(page, next));
        return MenuState.withStack(stack);
    }

    /** Focuses one enabled row on the current page without changing any parent-page focus. */
    static MenuState focus(MenuState state, String itemId) {
        if (state == null || itemId == null) {
            throw new IllegalArgumentException("state and item id are required");
        }
        if (!state.visible()) {
            return state;
        }
        int next = state.page().enabledIndex(itemId);
        if (next < 0 || next == state.focusedIndex()) {
            return state;
        }
        ArrayList<MenuState.Frame> stack = new ArrayList<>(state.stack());
        stack.set(stack.size() - 1, new MenuState.Frame(state.page(), next));
        return MenuState.withStack(stack);
    }

    private static int nextEnabledIndex(MenuPage page, int current, MenuCommand.Direction direction) {
        if (page.columns() == 1 && (direction == MenuCommand.Direction.LEFT
                || direction == MenuCommand.Direction.RIGHT)) {
            return current;
        }
        int currentRow = current / page.columns();
        int currentColumn = current % page.columns();
        int rowCount = (page.items().size() + page.columns() - 1) / page.columns();
        if (direction == MenuCommand.Direction.LEFT || direction == MenuCommand.Direction.RIGHT) {
            int delta = direction == MenuCommand.Direction.LEFT ? -1 : 1;
            for (int offset = 1; offset <= page.columns(); offset++) {
                int candidateColumn = Math.floorMod(currentColumn + delta * offset, page.columns());
                int candidate = itemAt(page, currentRow, candidateColumn);
                if (candidate >= 0 && page.items().get(candidate).enabled()) {
                    return candidate;
                }
            }
            return current;
        }
        int delta = direction == MenuCommand.Direction.UP ? -1 : 1;
        for (int offset = 1; offset <= rowCount; offset++) {
            int candidateRow = Math.floorMod(currentRow + delta * offset, rowCount);
            int candidate = itemAt(page, candidateRow, currentColumn);
            if (candidate < 0 || !page.items().get(candidate).enabled()) {
                candidate = nearestEnabledInRow(page, candidateRow, currentColumn);
            }
            if (candidate >= 0 && page.items().get(candidate).enabled()) {
                return candidate;
            }
        }
        return current;
    }

    private static int itemAt(MenuPage page, int row, int column) {
        int index = row * page.columns() + column;
        return index >= 0 && index < page.items().size() ? index : -1;
    }

    private static int nearestEnabledInRow(MenuPage page, int row, int requestedColumn) {
        int first = row * page.columns();
        int last = Math.min(page.items().size(), first + page.columns());
        if (first >= last) {
            return -1;
        }
        int nearest = -1;
        int distance = Integer.MAX_VALUE;
        for (int index = first; index < last; index++) {
            if (!page.items().get(index).enabled()) {
                continue;
            }
            int candidateDistance = Math.abs(index - first - requestedColumn);
            if (candidateDistance < distance) {
                nearest = index;
                distance = candidateDistance;
            }
        }
        return nearest;
    }

    private static void requireRoute(MenuRoute route) {
        if (route == null) {
            throw new IllegalArgumentException("route is required");
        }
    }
}
