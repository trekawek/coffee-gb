package eu.rekawek.coffeegb.android.menu;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable reducer state; only the reducer creates new instances. */
final class MenuState {

    private final List<Frame> stack;

    private MenuState(List<Frame> stack) {
        this.stack = Collections.unmodifiableList(new ArrayList<>(stack));
    }

    static MenuState hidden() {
        return new MenuState(Collections.emptyList());
    }

    static MenuState visible(MenuPage page, int focusedIndex) {
        return withStack(List.of(new Frame(page, focusedIndex)));
    }

    static MenuState withStack(List<Frame> stack) {
        if (stack == null || stack.isEmpty()) {
            return hidden();
        }
        for (Frame frame : stack) {
            if (frame == null) {
                throw new IllegalArgumentException("Menu stack cannot contain null");
            }
        }
        return new MenuState(stack);
    }

    boolean visible() {
        return !stack.isEmpty();
    }

    MenuRoute route() {
        return visible() ? current().page.route() : null;
    }

    int focusedIndex() {
        return visible() ? current().focusedIndex() : -1;
    }

    String focusedItemId() {
        if (!visible() || focusedIndex() < 0) {
            return null;
        }
        return current().page.items().get(focusedIndex()).id();
    }

    int depth() {
        return stack.size();
    }

    MenuPage page() {
        return current().page;
    }

    List<Frame> stack() {
        return stack;
    }

    MenuPresentation presentation() {
        return visible() ? current().page.presentation(current().focusedIndex())
                : MenuPresentation.hidden();
    }

    private Frame current() {
        return stack.get(stack.size() - 1);
    }

    static final class Frame {

        private final MenuPage page;
        private final int focusedIndex;

        Frame(MenuPage page, int focusedIndex) {
            if (page == null) {
                throw new IllegalArgumentException("Menu frame needs a page");
            }
            if (focusedIndex < 0 || focusedIndex >= page.items().size()
                    || !page.items().get(focusedIndex).enabled()) {
                throw new IllegalArgumentException("Menu frame has an invalid focused item");
            }
            this.page = page;
            this.focusedIndex = focusedIndex;
        }

        MenuPage page() {
            return page;
        }

        int focusedIndex() {
            return focusedIndex;
        }
    }
}
