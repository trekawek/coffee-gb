package eu.rekawek.coffeegb.ui.menu;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Thread-safe coordinator for menu navigation and edge-triggered input.
 *
 * <p>All state transitions are immutable reducer transitions. The listener receives snapshots and
 * semantic actions after the lock is released, so a host callback can safely call back into this
 * controller while a renderer retains the previous presentation.
 */
public final class MenuController implements MenuTouchInput {

    private static final float AXIS_DEAD_ZONE = 0.45f;

    private final Object lock = new Object();
    private final Listener listener;
    private final EnumMap<MenuRoute, MenuPage> pages = new EnumMap<>(MenuRoute.class);
    private final EnumSet<MenuKey> keyHeld = EnumSet.noneOf(MenuKey.class);
    private final EnumSet<MenuKey> axisHeld = EnumSet.noneOf(MenuKey.class);
    private final Map<Integer, EnumSet<MenuKey>> touchPointers = new HashMap<>();
    private final EnumSet<MenuKey> capturedKeys = EnumSet.noneOf(MenuKey.class);

    private MenuState state = MenuReducer.initial();
    private boolean backIntercepted;
    private boolean rootBackIntercepted;
    private boolean rootDismissAllowed = true;

    public MenuController(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        for (MenuRoute route : MenuRoute.values()) {
            pages.put(route, MenuPages.forRoute(route));
        }
    }

    public MenuPresentation presentation() {
        synchronized (lock) {
            return presentationLocked();
        }
    }

    public MenuRoute route() {
        synchronized (lock) {
            return state.route();
        }
    }

    /** Replaces one route's immutable content and preserves focus by item id when visible. */
    public void setPage(MenuPageSpec spec) {
        Objects.requireNonNull(spec, "spec");
        MenuPresentation next;
        synchronized (lock) {
            MenuPage page = MenuPage.from(spec);
            pages.put(spec.route(), page);
            state = MenuReducer.replacePage(state, page);
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    /** Replaces one route's content and explicitly focuses one enabled item in the new page. */
    public void setPageAndFocus(MenuPageSpec spec, String focusedItemId) {
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(focusedItemId, "focusedItemId");
        MenuPresentation next;
        synchronized (lock) {
            MenuPage page = MenuPage.from(spec);
            MenuState replacement = MenuReducer.replacePageAndFocus(
                    state, page, focusedItemId);
            pages.put(spec.route(), page);
            state = replacement;
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    /** Replaces several routes atomically so dynamic rows do not produce intermediate frames. */
    public void setPages(Collection<MenuPageSpec> specs) {
        Objects.requireNonNull(specs, "specs");
        MenuPresentation next;
        synchronized (lock) {
            for (MenuPageSpec spec : specs) {
                MenuPage page = MenuPage.from(Objects.requireNonNull(spec, "spec"));
                pages.put(spec.route(), page);
                state = MenuReducer.replacePage(state, page);
            }
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    public void show(MenuRoute route) {
        MenuPresentation next;
        synchronized (lock) {
            state = MenuReducer.show(state, page(route));
            clearTransientInputsLocked(true);
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    public void push(MenuRoute route) {
        MenuPresentation next;
        synchronized (lock) {
            state = MenuReducer.push(state, page(route));
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    /** Captures every route and focused item in one immutable operation. */
    public MenuStackSnapshot snapshot() {
        synchronized (lock) {
            if (!state.visible()) {
                return MenuStackSnapshot.hidden();
            }
            ArrayList<MenuStackSnapshot.Frame> frames = new ArrayList<>(state.stack().size());
            for (MenuState.Frame frame : state.stack()) {
                frames.add(new MenuStackSnapshot.Frame(frame.page().route(),
                        frame.page().items().get(frame.focusedIndex()).id()));
            }
            return new MenuStackSnapshot(frames);
        }
    }

    /** Restores a complete route chain against the latest page models in one transition. */
    public void restore(MenuStackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        MenuPresentation next;
        synchronized (lock) {
            if (!snapshot.visible()) {
                state = MenuReducer.initial();
            } else {
                ArrayList<MenuPage> routePages = new ArrayList<>(snapshot.frames().size());
                ArrayList<String> focus = new ArrayList<>(snapshot.frames().size());
                for (MenuStackSnapshot.Frame frame : snapshot.frames()) {
                    routePages.add(page(frame.route()));
                    focus.add(frame.focusedItemId());
                }
                state = MenuReducer.restore(routePages, focus);
            }
            clearTransientInputsLocked(true);
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    /** Keeps a B edge on the current route so an inline capture can cancel itself. */
    public void setBackIntercepted(boolean intercepted) {
        synchronized (lock) {
            backIntercepted = intercepted;
        }
    }

    /**
     * Keeps a B edge on a visible root page so the host can replace dismissal with a root action.
     *
     * <p>Unlike {@link #setBackIntercepted(boolean)}, this never affects child-page navigation.
     * Pause-menu hosts use it to make B an explicit Resume command while retaining ordinary B
     * navigation throughout the rest of the route stack.</p>
     */
    public void setRootBackIntercepted(boolean intercepted) {
        synchronized (lock) {
            rootBackIntercepted = intercepted;
        }
    }

    /**
     * Controls whether Back may dismiss a one-page stack.
     *
     * <p>Child routes always pop normally. Hosts use this for an idle Library root when there is
     * no gameplay surface to reveal underneath it.</p>
     */
    public void setRootDismissAllowed(boolean allowed) {
        MenuPresentation next;
        synchronized (lock) {
            if (rootDismissAllowed == allowed) {
                return;
            }
            rootDismissAllowed = allowed;
            // Hosts commonly configure this policy while constructing their menu controller,
            // before the view hierarchy exists. The next visible presentation will carry the
            // policy, while an already visible menu still receives an immediate refresh.
            if (!state.visible()) {
                return;
            }
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    public void back() {
        MenuPresentation next;
        synchronized (lock) {
            if (!state.visible()) {
                return;
            }
            if (state.stack().size() == 1 && !rootDismissAllowed) {
                return;
            }
            state = MenuReducer.back(state);
            if (!state.visible()) {
                clearTransientInputsLocked(false);
            }
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    public void hide() {
        MenuPresentation next;
        synchronized (lock) {
            if (!state.visible()) {
                return;
            }
            state = MenuReducer.hide(state);
            clearTransientInputsLocked(false);
            next = presentationLocked();
        }
        notifyPresentation(next);
    }

    /** Handles a physical/keypad key. Repeats are consumed without re-running the action. */
    public boolean onKeyDown(MenuKey key, boolean repeat) {
        Objects.requireNonNull(key, "key");
        MenuPresentation next = null;
        Event event = null;
        synchronized (lock) {
            if (!state.visible()) {
                return false;
            }
            capturedKeys.add(key);
            if (repeat || !keyHeld.add(key)) {
                return true;
            }
            Transition transition = activateLocked(key);
            next = transition.presentation;
            event = transition.event;
        }
        notifyTransition(next, event);
        return true;
    }

    /** Consumes key-up for every key whose key-down was captured by the visible menu. */
    public boolean onKeyUp(MenuKey key) {
        Objects.requireNonNull(key, "key");
        synchronized (lock) {
            boolean consumed = capturedKeys.remove(key);
            keyHeld.remove(key);
            return consumed || state.visible();
        }
    }

    /** Dispatches system back as one complete B edge and reports whether the menu consumed it. */
    public boolean dispatchBackEdge() {
        if (!onKeyDown(MenuKey.B, false)) {
            return false;
        }
        onKeyUp(MenuKey.B);
        return true;
    }

    /** Activates a released click or tap after checking it still belongs to the visible page. */
    @Override
    public boolean activateTarget(MenuPointerTarget target) {
        Objects.requireNonNull(target, "target");
        Transition transition;
        synchronized (lock) {
            if (!state.visible() || state.route() != target.route()) {
                return false;
            }
            if (target.itemId() != null) {
                int index = state.page().enabledIndex(target.itemId());
                if (index < 0) {
                    return false;
                }
                state = MenuReducer.focus(state, target.itemId());
                if (state.page().items().get(index).adjustable() && target.key() == MenuKey.A) {
                    transition = new Transition(presentationLocked(), null);
                } else {
                    transition = activateLocked(target.key());
                }
            } else {
                transition = activateLocked(target.key());
            }
        }
        notifyTransition(transition.presentation, transition.event);
        return true;
    }

    /** Converts joystick/hat values to edge-triggered menu movement with a stable dead zone. */
    public boolean onAxis(float x, float y) {
        EnumSet<MenuKey> nextAxis = EnumSet.noneOf(MenuKey.class);
        if (Float.isFinite(x) && Math.abs(x) >= AXIS_DEAD_ZONE) {
            nextAxis.add(x < 0.0f ? MenuKey.LEFT : MenuKey.RIGHT);
        }
        if (Float.isFinite(y) && Math.abs(y) >= AXIS_DEAD_ZONE) {
            nextAxis.add(y < 0.0f ? MenuKey.UP : MenuKey.DOWN);
        }

        MenuPresentation nextPresentation = null;
        List<Event> events = new ArrayList<>();
        synchronized (lock) {
            if (!state.visible()) {
                axisHeld.clear();
                return false;
            }
            EnumSet<MenuKey> previous = EnumSet.copyOf(axisHeld);
            axisHeld.clear();
            axisHeld.addAll(nextAxis);
            for (MenuKey key : nextAxis) {
                if (!previous.contains(key) && !heldByOtherSourceLocked(key)) {
                    Transition transition = activateLocked(key);
                    nextPresentation = transition.presentation;
                    if (transition.event != null) {
                        events.add(transition.event);
                    }
                }
            }
        }
        if (nextPresentation != null) {
            notifyPresentation(nextPresentation);
        }
        for (Event event : events) {
            notifyEvent(event);
        }
        return true;
    }

    @Override
    public boolean visible() {
        return visibleInternal();
    }

    @Override
    public void updatePointer(int pointerId, Collection<MenuKey> keys) {
        Objects.requireNonNull(keys, "keys");
        MenuPresentation nextPresentation = null;
        List<Event> events = new ArrayList<>();
        synchronized (lock) {
            if (!state.visible()) {
                touchPointers.remove(pointerId);
                return;
            }
            EnumSet<MenuKey> previous = touchPointers.get(pointerId);
            if (previous == null) {
                previous = EnumSet.noneOf(MenuKey.class);
            }
            EnumSet<MenuKey> next = EnumSet.noneOf(MenuKey.class);
            for (MenuKey key : keys) {
                if (key != null) {
                    next.add(key);
                }
            }
            touchPointers.remove(pointerId);
            for (MenuKey key : next) {
                if (!previous.contains(key) && !heldByOtherSourceLocked(key)) {
                    Transition transition = activateLocked(key);
                    nextPresentation = transition.presentation;
                    if (transition.event != null) {
                        events.add(transition.event);
                    }
                }
            }
            if (state.visible() && !next.isEmpty()) {
                touchPointers.put(pointerId, next);
            }
        }
        if (nextPresentation != null) {
            notifyPresentation(nextPresentation);
        }
        for (Event event : events) {
            notifyEvent(event);
        }
    }

    @Override
    public void releasePointer(int pointerId) {
        synchronized (lock) {
            touchPointers.remove(pointerId);
        }
    }

    @Override
    public void releaseAllPointers() {
        synchronized (lock) {
            touchPointers.clear();
        }
    }

    /** Clears transient input after focus loss without changing the current menu route. */
    public void cancelInput() {
        synchronized (lock) {
            clearTransientInputsLocked(true);
        }
    }

    private MenuPresentation presentationLocked() {
        MenuPresentation presentation = state.presentation();
        return state.depth() == 1 && !rootDismissAllowed
                && !backIntercepted && !rootBackIntercepted
                        ? presentation.withoutBackHint() : presentation;
    }

    private boolean visibleInternal() {
        synchronized (lock) {
            return state.visible();
        }
    }

    private MenuPage page(MenuRoute route) {
        Objects.requireNonNull(route, "route");
        MenuPage page = pages.get(route);
        if (page == null) {
            throw new IllegalArgumentException("No page for route " + route);
        }
        return page;
    }

    private Transition activateLocked(MenuKey key) {
        switch (key) {
            case UP, DOWN -> {
                if (state.route() == MenuRoute.FILE_BROWSER
                        && state.page().layout() == MenuPageLayout.FULL_WIDTH_LIST) {
                    return new Transition(presentationLocked(), Event.row(
                            state.route(), key == MenuKey.UP ? -1 : 1));
                }
                state = MenuReducer.move(state, key == MenuKey.UP
                        ? MenuCommand.Direction.UP : MenuCommand.Direction.DOWN);
            }
            case LEFT, RIGHT -> {
                MenuItem item = state.page().items().get(state.focusedIndex());
                if (item.enabled() && item.adjustable()) {
                    return new Transition(presentationLocked(), Event.adjust(
                            state.route(), item.id(), key == MenuKey.LEFT ? -1 : 1));
                }
                MenuPage page = state.page();
                if (page.layout() == MenuPageLayout.FULL_WIDTH_LIST) {
                    MenuPagination pagination = page.pagination();
                    int targetIndex = pagination.pageIndex()
                            + (key == MenuKey.LEFT ? -1 : 1);
                    if (targetIndex >= 0 && targetIndex < pagination.pageCount()) {
                        return new Transition(presentationLocked(), Event.page(
                                state.route(), targetIndex));
                    }
                }
                state = MenuReducer.move(state, key == MenuKey.LEFT
                        ? MenuCommand.Direction.LEFT : MenuCommand.Direction.RIGHT);
            }
            case B -> {
                if (backIntercepted || (state.stack().size() == 1 && rootBackIntercepted)) {
                    return new Transition(presentationLocked(), Event.back(state.route()));
                }
                if (state.stack().size() == 1 && !rootDismissAllowed) {
                    return new Transition(presentationLocked(), null);
                }
                state = MenuReducer.back(state);
                if (!state.visible()) {
                    clearTransientInputsLocked(false);
                }
            }
            case A, START -> {
                MenuItem item = state.page().items().get(state.focusedIndex());
                if (item.enabled()) {
                    return new Transition(presentationLocked(), Event.item(
                            state.route(), item.id(), false));
                }
            }
            case SECONDARY -> {
                MenuItem item = state.page().items().get(state.focusedIndex());
                if (item.enabled() && item.secondaryId() != null) {
                    return new Transition(presentationLocked(), Event.item(
                            state.route(), item.secondaryId(), true));
                }
            }
            // Select is intentionally consumed but inert on every menu page.  SECONDARY keeps
            // the distinct delete/remap route used by rows that explicitly declare one.
            case SELECT -> { }
        }
        return new Transition(presentationLocked(), null);
    }

    private boolean heldByOtherSourceLocked(MenuKey key) {
        if (keyHeld.contains(key)) {
            return true;
        }
        for (EnumSet<MenuKey> pointer : touchPointers.values()) {
            if (pointer.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private void clearTransientInputsLocked(boolean clearCaptured) {
        keyHeld.clear();
        axisHeld.clear();
        touchPointers.clear();
        if (clearCaptured) {
            capturedKeys.clear();
        }
    }

    private void notifyTransition(MenuPresentation presentation, Event event) {
        notifyPresentation(presentation);
        if (event != null) {
            notifyEvent(event);
        }
    }

    private void notifyPresentation(MenuPresentation presentation) {
        listener.onPresentation(presentation);
    }

    private void notifyEvent(Event event) {
        switch (event.kind) {
            case HEADER -> listener.onHeaderSelected(event.route);
            case ITEM -> listener.onItemSelected(event.route, event.id, event.secondary);
            case ADJUST -> listener.onItemAdjusted(event.route, event.id, event.adjustment);
            case BACK -> listener.onBackIntercepted(event.route);
            case PAGE -> listener.onPageRequested(event.route, event.pageIndex);
            case ROW -> listener.onListRowRequested(event.route, event.adjustment);
        }
    }

    private static final class Transition {
        private final MenuPresentation presentation;
        private final Event event;

        private Transition(MenuPresentation presentation, Event event) {
            this.presentation = presentation;
            this.event = event;
        }
    }

    private static final class Event {
        private enum Kind {
            ITEM,
            HEADER,
            ADJUST,
            BACK,
            PAGE,
            ROW
        }

        private final MenuRoute route;
        private final String id;
        private final boolean secondary;
        private final int adjustment;
        private final int pageIndex;
        private final Kind kind;

        private Event(MenuRoute route, String id, boolean secondary, int adjustment,
                int pageIndex, Kind kind) {
            this.route = route;
            this.id = id;
            this.secondary = secondary;
            this.adjustment = adjustment;
            this.pageIndex = pageIndex;
            this.kind = kind;
        }

        private static Event item(MenuRoute route, String id, boolean secondary) {
            return new Event(route, id, secondary, 0, -1, Kind.ITEM);
        }

        private static Event header(MenuRoute route) {
            return new Event(route, null, false, 0, -1, Kind.HEADER);
        }

        private static Event adjust(MenuRoute route, String id, int adjustment) {
            return new Event(route, id, false, adjustment, -1, Kind.ADJUST);
        }

        private static Event back(MenuRoute route) {
            return new Event(route, null, false, 0, -1, Kind.BACK);
        }

        private static Event page(MenuRoute route, int pageIndex) {
            return new Event(route, null, false, 0, pageIndex, Kind.PAGE);
        }

        private static Event row(MenuRoute route, int direction) {
            return new Event(route, null, false, direction, -1, Kind.ROW);
        }
    }

    /** Semantic menu callbacks; implementations normally run on the host UI thread. */
    public interface Listener {
        void onPresentation(MenuPresentation presentation);

        void onItemSelected(MenuRoute route, String id, boolean secondary);

        void onHeaderSelected(MenuRoute route);

        default void onItemAdjusted(MenuRoute route, String id, int direction) {
        }

        default void onBackIntercepted(MenuRoute route) {
        }

        default void onPageRequested(MenuRoute route, int targetIndex) {
        }

        /** Requests the previous (-1) or next (+1) item in a host-backed full-width list. */
        default void onListRowRequested(MenuRoute route, int direction) {
        }
    }
}
