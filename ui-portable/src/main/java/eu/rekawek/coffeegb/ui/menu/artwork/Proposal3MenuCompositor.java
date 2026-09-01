package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-java compositor for the fixed Proposal 3 route artwork.
 *
 * <p>Every composition starts by cloning one immutable 924x736 PNG.  The compositor only paints
 * audited dynamic interiors: palette-class focus changes, exact focus-marker sprites, runtime
 * text/status glyphs, bounded previews, the audio slider, and confirmation copy.  Platform file
 * browsing and sharing remain host responsibilities.
 */
public final class Proposal3MenuCompositor {

    private static final int PAPER_MATTE = MenuRaster.PAPER;
    /** Warm save-seal center from the imagegen concept; the pale frame keeps it legible on focus. */
    private static final int STATE_USED_SEAL = 0xffbd5d4e;

    private final Object lock = new Object();
    private MenuRoute cachedRoute;
    private int[] cachedTemplatePixels;
    private Proposal3GlyphAtlas cachedAtlas;
    private Proposal3WidgetSkins cachedSkins;
    private MenuPresentation cachedPresentation;
    private MenuArgbFrame cachedFrame;

    public Proposal3MenuCompositor() {
    }

    /** Returns the final detached canonical frame, or empty for a hidden presentation. */
    public Optional<MenuArgbFrame> compose(MenuPresentation presentation) {
        Objects.requireNonNull(presentation, "presentation");
        synchronized (lock) {
            if (!presentation.visible()) {
                cachedPresentation = null;
                cachedFrame = null;
                return Optional.empty();
            }
            if (presentation == cachedPresentation) {
                return Optional.of(cachedFrame);
            }

            MenuRoute route = Objects.requireNonNull(presentation.route(), "route");
            // Resolve all packaged raster dependencies before beginning the first frame. This
            // keeps the initial non-focus composition deterministic when a host requests a
            // screenshot immediately after constructing the compositor; subsequent frames use
            // the same immutable caches.
            atlas();
            skins();
            Proposal3OverlayCatalog.RouteLayout layout = Proposal3OverlayCatalog.layout(route);
            int[] authority = authorityPixels(route);
            int[] working = authority.clone();
            MenuRaster raster = new MenuRaster(working);
            Prepared prepared = prepare(route, presentation);

            drawChrome(route, presentation, prepared, raster);
            drawRows(route, presentation, prepared, layout, raster);
            drawActions(route, presentation, prepared, layout, raster);
            drawRouteWidgets(route, presentation, prepared, layout, raster);

            MenuArgbFrame frame = MenuArgbFrame.trusted(MenuArtworkCatalog.PACKAGED_WIDTH,
                    MenuArtworkCatalog.PACKAGED_HEIGHT, raster.pixels());
            cachedPresentation = presentation;
            cachedFrame = frame;
            return Optional.of(frame);
        }
    }

    /** Package-private mask view used by portable exactness tests. */
    static List<MenuRect> dynamicMasks(MenuRoute route) {
        Proposal3OverlayCatalog.RouteLayout layout = Proposal3OverlayCatalog.layout(route);
        ArrayList<MenuRect> masks = new ArrayList<>(layout.dynamicMasks());
        if (route == MenuRoute.SETTINGS) {
            // Settings rows are centered according to the host's actual item count. The panel
            // itself is a deliberate repaint boundary so no obsolete seven-row rail can leak
            // through when a platform supplies one or two settings.
            masks.add(Proposal3OverlayCatalog.SETTINGS_PANEL);
        }
        if (route == MenuRoute.TOUCH_CONTROLS) {
            // Android may expose only Haptics, or Haptics plus Remap. Treat the Controls rail as
            // a compact presentation instead of leaving fixed empty bordered slots behind.
            masks.add(Proposal3OverlayCatalog.TOUCH_PANEL);
        }
        if (route == MenuRoute.AUDIO) {
            masks.add(Proposal3OverlayCatalog.AUDIO_VOLUME_ARROW);
        }
        if (route == MenuRoute.SYSTEM || route == MenuRoute.DISPLAY) {
            masks.add(new MenuRect(22, 129, 334, 467));
        }
        if (route == MenuRoute.DISPLAY) {
            masks.add(new MenuRect(374, 326, 538, 114));
        }
        if (route == MenuRoute.OPTIONAL_DEVICES) {
            masks.add(new MenuRect(17, 118, 318, 524));
            masks.add(new MenuRect(350, 320, 560, 215));
            masks.add(new MenuRect(350, 540, 560, 98));
        }
        if (route == MenuRoute.OPTION_PICKER) {
            masks.add(Proposal3OverlayCatalog.OPTION_PICKER_ILLUSTRATION);
        }
        if (route == MenuRoute.SETTINGS || route == MenuRoute.AUDIO
                || route == MenuRoute.DISPLAY || route == MenuRoute.TOUCH_CONTROLS
                || route == MenuRoute.CONTROLLER_MAPPING || route == MenuRoute.SYSTEM
                || route == MenuRoute.OPTIONAL_DEVICES || route == MenuRoute.OPTION_PICKER) {
            masks.add(Proposal3OverlayCatalog.BACK_HEADER);
        }
        masks.addAll(Proposal3TextCatalog.masks(route));
        for (Proposal3OverlayCatalog.Slot slot : layout.rows()) {
            masks.add(expand(slot.bounds(), 3));
        }
        for (Proposal3OverlayCatalog.Slot slot : layout.actions()) {
            masks.add(expand(slot.bounds(), 3));
        }
        return List.copyOf(masks);
    }

    /** Fixed major-row role; selection never participates in this decision. */
    static Proposal3GlyphAtlas.Role rowTextRole(MenuRoute route, int rowIndex) {
        return switch (route) {
            case PAUSE_CONSOLE, SAVE_STATES -> Proposal3GlyphAtlas.Role.SEMIBOLD;
            // Seven archive rows use the compact 36px face so their ink fits the fixed viewport.
            case CHOOSE_ROM -> Proposal3GlyphAtlas.Role.MEDIUM;
            case CONTROLLER_MAPPING -> Proposal3GlyphAtlas.Role.SMALL;
            case OPTIONAL_DEVICES, SYSTEM, DISPLAY, OPTION_PICKER ->
                    Proposal3GlyphAtlas.Role.NOTICE;
            case ABOUT -> rowIndex == 0 ? Proposal3GlyphAtlas.Role.SEMIBOLD
                    : Proposal3GlyphAtlas.Role.NOTICE;
            default -> Proposal3GlyphAtlas.Role.MEDIUM;
        };
    }

    /** Fixed action role; selection never participates in this decision. */
    static Proposal3GlyphAtlas.Role actionTextRole(MenuRoute route) {
        return switch (route) {
            case SAVE_STATES, RECENT_GAMES, CHOOSE_ROM -> Proposal3GlyphAtlas.Role.SEMIBOLD;
            default -> Proposal3GlyphAtlas.Role.MEDIUM;
        };
    }

    /** Route-aware chrome role; kept separate so canonical strings can be audited for fit. */
    static Proposal3GlyphAtlas.Role chromeTextRole(MenuRoute route,
            Proposal3TextCatalog.TextRegion region) {
        return region.role();
    }

    int cachedTemplateRouteCount() {
        synchronized (lock) {
            return cachedTemplatePixels == null ? 0 : 1;
        }
    }

    int cachedComposedFrameCount() {
        synchronized (lock) {
            return cachedFrame == null ? 0 : 1;
        }
    }

    private int[] authorityPixels(MenuRoute route) {
        if (route != cachedRoute) {
            try {
                cachedTemplatePixels = Proposal3TemplateFrameCatalog.decode(route).copyPixels();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to decode Proposal 3 template for "
                        + route, e);
            }
            cachedRoute = route;
            cachedPresentation = null;
            cachedFrame = null;
        }
        return cachedTemplatePixels;
    }

    private Proposal3GlyphAtlas atlas() {
        if (cachedAtlas == null) {
            try {
                cachedAtlas = Proposal3GlyphAtlas.load();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to decode the Proposal 3 glyph atlas", e);
            }
        }
        return cachedAtlas;
    }

    private Proposal3WidgetSkins skins() {
        if (cachedSkins == null) {
            try {
                cachedSkins = Proposal3WidgetSkins.load();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to decode the Proposal 3 widget skins", e);
            }
        }
        return cachedSkins;
    }

    private static Prepared prepare(MenuRoute route, MenuPresentation presentation) {
        Prepared prepared = new Prepared();
        Set<String> rowIds = new HashSet<>();
        Set<String> actionIds = new HashSet<>();
        List<MenuPresentation.Item> items = presentation.items();
        for (int index = 0; index < items.size(); index++) {
            MenuPresentation.Item item = items.get(index);
            String id = item.id();
            if ("back".equals(id)) {
                continue;
            }
            if ("volume".equals(id)) {
                prepared.volume = entry(item, index, "volume", -1);
                continue;
            }
            if (route == MenuRoute.ABOUT && ("version".equals(id) || "license".equals(id)
                    || "source".equals(id) || "third-party".equals(id))) {
                continue;
            }

            String visualId = visualId(route, id, prepared.rows.size());
            if (isAction(route, id)) {
                // Disabled action rows have no distinct portable disabled skin. Omitting them
                // keeps a capability that is unavailable from looking like a live button, while
                // the host can still keep an enabled status row to make the route navigable.
                if (item.enabled() && actionIds.add(visualId)) {
                    prepared.actions.add(entry(item, index, visualId, -1));
                }
            } else if (rowIds.add(visualId)) {
                int slotNumber = parseSlot(visualId);
                prepared.rows.add(entry(item, index, visualId, slotNumber));
            }
        }

        if (route == MenuRoute.CHOOSE_ROM) {
            addSyntheticAction(prepared, actionIds, "open-selected", "OPEN SELECTED",
                    findEnabledSource(items, "open-selected"));
            addSyntheticAction(prepared, actionIds, "cancel", "CANCEL",
                    findEnabledSource(items, "cancel"));
        }
        if (route == MenuRoute.ABOUT) {
            addSyntheticAction(prepared, actionIds, "source",
                    "GITHUB.COM/TREKAWEK/COFFEE-GB",
                    findEnabledSource(items, "source-notices"));
        }
        orderActions(route, prepared.actions);
        return prepared;
    }

    private static void orderActions(MenuRoute route, List<Entry> actions) {
        List<String> order = switch (route) {
            case SAVE_STATES, RECENT_GAMES -> List.of();
            case AUDIO, TOUCH_CONTROLS -> List.of();
            case OPTIONAL_DEVICES -> List.of("save-devices", "cancel-devices");
            case CHOOSE_ROM -> List.of("open-selected", "cancel");
            case ABOUT -> List.of("source");
            case CONFIRM_ACTION -> List.of("cancel", "confirm");
            case PRINTER_PAPER -> List.of("clear-paper", "export-share-paper");
            default -> List.of();
        };
        actions.sort((left, right) -> Integer.compare(actionOrder(order, left.id),
                actionOrder(order, right.id)));
    }

    private static int actionOrder(List<String> order, String id) {
        int index = order.indexOf(id);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    private static String visualId(MenuRoute route, String id, int rowCount) {
        if (route == MenuRoute.SAVE_STATES) {
            int slot = parseSlot(id);
            if (slot >= 0) {
                return "slot-" + slot;
            }
        }
        if (route == MenuRoute.RECENT_GAMES) {
            // Recent identifiers are host-owned stable tokens (for example recent:7), while the
            // artwork marker is anchored to a canonical visual row. Normalize every supplied
            // entry by presentation order so the selected row always resolves that anchor.
            return "recent-" + rowCount;
        }
        if (route == MenuRoute.LIBRARY
                && ("recent-games".equals(id) || id.startsWith("recent:"))) {
            return "recent-" + rowCount;
        }
        if (route == MenuRoute.CHOOSE_ROM && id.startsWith("archive:")) {
            return "rom-" + (rowCount + 1);
        }
        if (route == MenuRoute.CONTROLLER_MAPPING) {
            if ("invert-x".equals(id)) {
                return "horizontal-axis";
            }
            if ("invert-y".equals(id)) {
                return "vertical-axis";
            }
            if ("reset-controller".equals(id)) {
                return "reset-mappings";
            }
        }
        return id;
    }

    private static boolean isAction(MenuRoute route, String id) {
        return switch (route) {
            case SAVE_STATES, RECENT_GAMES -> false;
            case AUDIO, DISPLAY, TOUCH_CONTROLS, OPTION_PICKER -> false;
            case OPTIONAL_DEVICES -> id.equals("save-devices") || id.equals("cancel-devices");
            case CHOOSE_ROM -> id.equals("open-selected") || id.equals("cancel");
            case ABOUT -> id.equals("source") || id.equals("third-party");
            case CONFIRM_ACTION -> id.equals("cancel") || id.equals("confirm");
            case PRINTER_PAPER -> id.equals("clear-paper") || id.equals("export-share-paper");
            default -> false;
        };
    }

    private static int findEnabledSource(List<MenuPresentation.Item> items, String id) {
        for (int index = 0; index < items.size(); index++) {
            MenuPresentation.Item item = items.get(index);
            if (id.equals(item.id()) && item.enabled()) {
                return index;
            }
        }
        return -1;
    }

    private static void addSyntheticAction(Prepared prepared, Set<String> ids, String id,
            String label, int sourceIndex) {
        // A host may keep a route alive with a single enabled status row while its platform
        // capability is unavailable. Do not invent an actionable-looking footer button in that
        // case; only synthesize the artwork action when its capable source item is present and
        // enabled in the same immutable presentation.
        if (sourceIndex >= 0 && ids.add(id)) {
            prepared.actions.add(new Entry(id, label, "", true, sourceIndex, false, -1, -1));
        }
    }

    private static Entry entry(MenuPresentation.Item item, int sourceIndex, String visualId,
            int slotNumber) {
        return new Entry(visualId, item.label(), item.detail(), item.enabled(), sourceIndex,
                item.adjustable(), item.progress(), slotNumber);
    }

    private void paintById(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, String id, boolean selected,
            MenuRaster raster) {
        if (id == null) {
            return;
        }
        int rowIndex = indexOf(prepared.rows, id);
        if (rowIndex >= 0) {
            ScrollWindow window = scrollWindow(prepared.rows, presentation.focusedIndex(),
                    layout.rows().size(), layout.scrollable());
            int visibleIndex = window.visualIndex(rowIndex);
            if (visibleIndex < 0) {
                if (rowIndex != 0 || layout.rows().isEmpty()) {
                    return;
                }
                visibleIndex = window.firstContentIndex();
            }
            paintRowWidget(route, presentation, prepared, layout, prepared.rows.get(rowIndex),
                    rowIndex, layout.rows().get(visibleIndex), selected, raster);
            return;
        }
        int actionIndex = indexOf(prepared.actions, id);
        if (actionIndex >= 0 && actionIndex < layout.actions().size()) {
            paintActionWidget(route, presentation, prepared, layout,
                    prepared.actions.get(actionIndex), actionIndex,
                    layout.actions().get(actionIndex), selected, raster);
        }
    }

    private void drawChrome(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            MenuRaster raster) {
        for (MenuRect clear : Proposal3TextCatalog.footerClearRegions()) {
            paintSurface(raster, clear, Proposal3OverlayCatalog.Surface.PAPER, false);
        }
        if (route == MenuRoute.PAUSE_CONSOLE) {
            // The pause menu has no context or header action.  Clear the original, now obsolete
            // chrome once per frame so it cannot reappear when returning from a subpage.
            paintSurface(raster, Proposal3OverlayCatalog.PAUSE_HEADER_CONTEXT,
                    Proposal3OverlayCatalog.Surface.PAPER, false);
            paintSurface(raster, Proposal3OverlayCatalog.PAUSE_HEADER_ACTION,
                    Proposal3OverlayCatalog.Surface.PAPER, false);
        } else if (route == MenuRoute.CONFIRM_ACTION) {
            // Confirmation is a two-option decision page.  Its title bar has no Back action,
            // including the source artwork's button outline.
            paintSurface(raster, Proposal3OverlayCatalog.CONFIRM_HEADER_CLEAR,
                    Proposal3OverlayCatalog.Surface.PAPER, false);
        }
        if (route == MenuRoute.SETTINGS || route == MenuRoute.AUDIO
                || route == MenuRoute.DISPLAY
                || route == MenuRoute.TOUCH_CONTROLS || route == MenuRoute.CONTROLLER_MAPPING
                || route == MenuRoute.SYSTEM || route == MenuRoute.OPTIONAL_DEVICES
                || route == MenuRoute.OPTION_PICKER) {
            // The source artwork's top-right Back outline is decorative in the portable overlay;
            // B is the single global back action. Clear the entire footprint, including its
            // border, rather than only replacing the old word.
            paintSurface(raster, Proposal3OverlayCatalog.BACK_HEADER,
                    Proposal3OverlayCatalog.Surface.PAPER, false);
        }
        String[] footer = footerValues(route, presentation.footerHints());
        if (footer[1].isEmpty()) {
            // A positional blank is an explicit host contract, not a request to restore the
            // default footer. Remove the approved A keycap as well as its label so a B-only host
            // does not advertise a control that cannot be used on that page.
            paintSurface(raster, Proposal3TextCatalog.FOOTER_CHOOSE_KEYCAP_CLEAR,
                    Proposal3OverlayCatalog.Surface.PAPER, false);
        }
        for (Proposal3TextCatalog.TextRegion region : Proposal3TextCatalog.regions(route)) {
            String value = textValue(region, presentation, prepared, footer);
            Proposal3OverlayCatalog.Surface surface = region.surface()
                    == Proposal3TextCatalog.Surface.DARK
                    ? Proposal3OverlayCatalog.Surface.DARK
                    : Proposal3OverlayCatalog.Surface.PAPER;
            if (value.isEmpty()) {
                continue;
            }
            int color = surface == Proposal3OverlayCatalog.Surface.DARK
                    ? MenuRaster.PAPER_TEXT : MenuRaster.INK;
            drawCatalogText(raster, region, chromeTextRole(route, region), value, color);
        }
    }

    private void drawCatalogText(MenuRaster raster, Proposal3TextCatalog.TextRegion region,
            Proposal3GlyphAtlas.Role role, String value, int color) {
        String[] lines = value.split("\\n", -1);
        if (lines.length == 1) {
            raster.drawText(atlas(), role, value, region.bounds(), color,
                    toAlignment(region.alignment().horizontal()));
            return;
        }
        int lineHeight = Math.max(1, region.bounds().height() / lines.length);
        for (int index = 0; index < lines.length; index++) {
            MenuRect line = new MenuRect(region.bounds().x(),
                    region.bounds().y() + index * lineHeight, region.bounds().width(),
                    index == lines.length - 1
                            ? region.bounds().bottom() - region.bounds().y() - index * lineHeight
                            : lineHeight);
            raster.drawText(atlas(), role, lines[index], line, color,
                    toAlignment(region.alignment().horizontal()));
        }
    }

    private static MenuRaster.HorizontalAlignment toAlignment(
            Proposal3TextCatalog.Horizontal alignment) {
        return switch (alignment) {
            case LEFT -> MenuRaster.HorizontalAlignment.LEFT;
            case CENTER -> MenuRaster.HorizontalAlignment.CENTER;
            case RIGHT -> MenuRaster.HorizontalAlignment.RIGHT;
        };
    }

    private static String[] footerValues(MenuRoute route, List<String> hints) {
        // The pause screen establishes the console-wide physical-control contract. Other routes
        // retain their page-specific text (for example SAVE/CANCEL). For every other route a
        // supplied list is positional: an empty entry intentionally suppresses that slot.
        if (route == MenuRoute.PAUSE_CONSOLE) {
            return new String[]{"D-PAD MOVE", "A CHOOSE", "B BACK"};
        }
        if (hints == null || hints.isEmpty()) {
            return new String[]{"D-PAD MOVE", "A CHOOSE", "B BACK"};
        }
        String[] values = {"", "", ""};
        for (int index = 0; index < values.length && index < hints.size(); index++) {
            values[index] = display(hints.get(index));
        }
        return values;
    }

    private static String textValue(Proposal3TextCatalog.TextRegion region,
            MenuPresentation presentation, Prepared prepared, String[] footer) {
        if (region.literal() != null) {
            return region.literal();
        }
        return switch (region.key()) {
            case HEADER_TITLE -> display(presentation.title());
            case HEADER_CONTEXT -> presentation.route() == MenuRoute.PAUSE_CONSOLE
                    ? "" : presentation.context().isEmpty()
                    ? "/" : "/ " + display(presentation.context());
            case HEADER_ACTION -> presentation.route() == MenuRoute.PAUSE_CONSOLE
                    || presentation.route() == MenuRoute.SAVE_STATES
                    || presentation.route() == MenuRoute.CONFIRM_ACTION
                    || presentation.route() == MenuRoute.SETTINGS
                    || presentation.route() == MenuRoute.AUDIO
                    || presentation.route() == MenuRoute.DISPLAY
                    || presentation.route() == MenuRoute.TOUCH_CONTROLS
                    || presentation.route() == MenuRoute.CONTROLLER_MAPPING
                    || presentation.route() == MenuRoute.SYSTEM
                    || presentation.route() == MenuRoute.OPTIONAL_DEVICES
                    || presentation.route() == MenuRoute.OPTION_PICKER
                    || presentation.route() == MenuRoute.LIBRARY
                    || presentation.route() == MenuRoute.RECENT_GAMES
                    ? "" : display(presentation.headerAction());
            case FOOTER_DPAD -> display(footer[0]);
            case FOOTER_BUTTON -> footerButton(footer[region.index()]);
            case FOOTER_LABEL -> footerLabel(footer[region.index()]);
            case SIDE_HEADING -> display(presentation.sideHeading());
            case SIDE_LINE -> presentation.route() == MenuRoute.RECENT_GAMES
                    ? recentLastPlayedText(valueAt(presentation.sideLines(), region.index(), ""))
                    : display(valueAt(presentation.sideLines(), region.index(), ""));
            case CONFIRM_TITLE -> confirmationTitle(presentation, prepared);
            case CONFIRM_COPY_ONE, CONFIRM_COPY_TWO, CONFIRM_COPY_THREE ->
                    confirmationCopy(region.key(), presentation);
            case LITERAL -> "";
        };
    }

    private static String footerButton(String hint) {
        String value = display(hint);
        int open = value.indexOf('[');
        int close = value.indexOf(']');
        if (open >= 0 && close > open + 1) {
            return value.substring(open + 1, close);
        }
        return value.isEmpty() ? "" : value.substring(0, 1);
    }

    private static String footerLabel(String hint) {
        String value = display(hint);
        int close = value.indexOf(']');
        if (close >= 0 && close + 1 < value.length()) {
            return value.substring(close + 1).trim();
        }
        int space = value.indexOf(' ');
        return space < 0 ? value : value.substring(space + 1).trim();
    }

    static String recentLastPlayedText(String value) {
        String normalized = display(value);
        if (normalized.isEmpty()) {
            return "";
        }
        String prefix = "LAST PLAYED:";
        String timestamp = normalized.startsWith(prefix)
                ? normalized.substring(prefix.length()).trim() : normalized;
        return timestamp.isEmpty() ? prefix : prefix + "\n" + timestamp;
    }

    private static String confirmationTitle(MenuPresentation presentation, Prepared prepared) {
        Entry confirm = find(prepared.actions, "confirm");
        String action = confirm == null || confirm.detail.isEmpty()
                ? valueAt(presentation.sideLines(), 0, "RESET GAME")
                : display(confirm.detail);
        if (action.isEmpty()) {
            action = "RESET GAME";
        }
        return action.endsWith("?") ? action : action + "?";
    }

    private static String confirmationCopy(Proposal3TextCatalog.Key key,
            MenuPresentation presentation) {
        String description = valueAt(presentation.sideLines(), 0,
                "UNSAVED PROGRESS MAY BE LOST");
        String[] lines = twoLines(description);
        return switch (key) {
            case CONFIRM_COPY_ONE -> lines[0].isEmpty() ? "UNSAVED PROGRESS" : lines[0];
            case CONFIRM_COPY_TWO -> lines[1].isEmpty() ? "MAY BE LOST." : lines[1];
            case CONFIRM_COPY_THREE -> {
                String extra = valueAt(presentation.sideLines(), 1, "");
                String consequence = valueAt(presentation.sideLines(), 2, "");
                if (extra.isEmpty() && consequence.isEmpty()) {
                    yield "";
                }
                if (consequence.isEmpty()) {
                    yield display(extra);
                }
                if (extra.isEmpty()) {
                    yield display(consequence);
                }
                yield display(extra) + "\n" + display(consequence);
            }
            default -> "";
        };
    }

    private void paintSurface(MenuRaster raster, MenuRect bounds,
            Proposal3OverlayCatalog.Surface surface, boolean selected) {
        Proposal3WidgetSkins.Surface skin = selected ? Proposal3WidgetSkins.Surface.SELECTED
                : surface == Proposal3OverlayCatalog.Surface.DARK
                ? Proposal3WidgetSkins.Surface.DARK : Proposal3WidgetSkins.Surface.PAPER;
        raster.paintWidget(skins().surface(skin), bounds);
    }

    private void paintMarker(MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, Proposal3OverlayCatalog.Slot target,
            MenuRaster raster) {
        Proposal3OverlayCatalog.Marker marker = layout.marker();
        if (marker == null) {
            return;
        }
        Proposal3OverlayCatalog.Slot canonical = slotForId(layout, prepared,
                layout.canonicalFocusId(), presentation.focusedIndex(), false);
        if (canonical == null) {
            return;
        }
        int x = target.bounds().x() + marker.sourceX() - canonical.bounds().x();
        raster.drawFocusArrow(x, target.bounds().y() + target.bounds().height() / 2,
                MenuRaster.PAPER_TEXT);
    }

    private static MenuRect expand(MenuRect bounds, int amount) {
        int left = Math.max(0, bounds.x() - amount);
        int top = Math.max(0, bounds.y() - amount);
        int right = Math.min(MenuArtworkCatalog.PACKAGED_WIDTH, bounds.right() + amount);
        int bottom = Math.min(MenuArtworkCatalog.PACKAGED_HEIGHT, bounds.bottom() + amount);
        return new MenuRect(left, top, right - left, bottom - top);
    }

    private static Proposal3OverlayCatalog.Slot slotForId(
            Proposal3OverlayCatalog.RouteLayout layout, Prepared prepared, String id,
            int focusedIndex, boolean target) {
        if (id == null) {
            return null;
        }
        List<Entry> entries = prepared.rows;
        int entryIndex = indexOf(entries, id);
        if (entryIndex >= 0) {
            List<Proposal3OverlayCatalog.Slot> slots = rowSlots(layout, entries.size());
            ScrollWindow window = scrollWindow(entries, focusedIndex, slots.size(),
                    layout.scrollable());
            int visibleIndex = window.visualIndex(entryIndex);
            if (visibleIndex >= 0) {
                return slots.get(visibleIndex);
            }
            if (!target && entryIndex == 0 && !layout.rows().isEmpty()) {
                return slots.get(0);
            }
        }
        int actionIndex = indexOf(prepared.actions, id);
        if (actionIndex >= 0 && actionIndex < layout.actions().size()) {
            return layout.actions().get(actionIndex);
        }
        return null;
    }

    private static int indexOf(List<Entry> entries, String id) {
        for (int index = 0; index < entries.size(); index++) {
            if (id.equals(entries.get(index).id)) {
                return index;
            }
        }
        return -1;
    }

    private static ScrollWindow scrollWindow(List<Entry> entries, int focusedIndex, int capacity,
            boolean scrollable) {
        if (!scrollable || entries.size() <= capacity) {
            return new ScrollWindow(0, Math.min(entries.size(), capacity), false, false);
        }
        int focused = -1;
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).sourceIndex == focusedIndex) {
                focused = index;
                break;
            }
        }
        if (focused < 0) {
            focused = 0;
        }
        int edgeCapacity = capacity - 1;
        if (focused < edgeCapacity) {
            return new ScrollWindow(0, edgeCapacity, false, true);
        }
        if (focused >= entries.size() - edgeCapacity) {
            return new ScrollWindow(entries.size() - edgeCapacity, edgeCapacity, true, false);
        }
        int contentCapacity = capacity - 2;
        int start = Math.max(1, Math.min(focused - contentCapacity + 1,
                entries.size() - contentCapacity - 1));
        return new ScrollWindow(start, contentCapacity, true, true);
    }

    private void paintRowWidget(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, Entry entry, int entryIndex,
            Proposal3OverlayCatalog.Slot slot, boolean selected, MenuRaster raster) {
        paintSurface(raster, rowSurfaceBounds(route, slot), slot.surface(), selected);
        int color = selected || slot.surface() == Proposal3OverlayCatalog.Surface.DARK
                ? MenuRaster.PAPER_TEXT : MenuRaster.INK;
        String label = label(route, entry, entryIndex);
        String detail = detail(route, entry, entryIndex);
        boolean checkbox = isCheckboxRow(route, entry);
        boolean optionSelected = route == MenuRoute.OPTION_PICKER
                && "SELECTED".equals(display(detail));
        boolean choice = isChoiceRow(route, entry);
        boolean drawsDetail = supportsDetail(route, entry) && !detail.isEmpty()
                && !checkbox && !optionSelected && !choice;
        MenuRect labelBounds = labelBounds(route, slot.bounds(), drawsDetail || choice, entryIndex);
        drawWidgetText(raster, label, labelBounds, color, MenuRaster.HorizontalAlignment.LEFT,
                rowTextRole(route, entryIndex));
        if (choice) {
            paintChoiceField(route, slot.bounds(), detail, selected, raster);
        } else if (drawsDetail) {
            MenuRect detailBounds = detailBounds(route, slot.bounds());
            drawWidgetText(raster, detail, detailBounds, color,
                    MenuRaster.HorizontalAlignment.RIGHT, detailTextRole(route));
        }
        if (checkbox || optionSelected) {
            // Preserve the model's ON/OFF/SELECTED state but keep the control near its label.
            raster.drawCheckbox(checkboxBounds(route, slot.bounds()),
                    checkbox ? "ON".equals(display(detail)) : optionSelected);
        }
        paintRowIcon(route, entryIndex, slot, raster);
        if (route == MenuRoute.SAVE_STATES && isUsedState(entry)) {
            paintStateUsedSeal(slot, raster);
        }
        if (selected && layout.marker() != null
                && slot.surface() == Proposal3OverlayCatalog.Surface.DARK) {
            paintMarker(presentation, prepared, layout, slot, raster);
        }
    }

    private static boolean isChoiceRow(MenuRoute route, Entry entry) {
        return switch (route) {
            case SYSTEM -> entry.id.equals("dmg-games") || entry.id.equals("cgb-games")
                    || entry.id.equals("bootstrap") || entry.id.equals("execution-mode");
            case DISPLAY -> entry.id.equals("dmg-colors");
            case OPTIONAL_DEVICES -> entry.id.equals("camera") || entry.id.equals("gamepad");
            default -> false;
        };
    }

    private static boolean isCheckboxRow(MenuRoute route, Entry entry) {
        return route == MenuRoute.AUDIO && entry.id.equals("mute-audio")
                || route == MenuRoute.DISPLAY && entry.id.equals("sgb-border")
                || route == MenuRoute.OPTIONAL_DEVICES && entry.id.equals("gps");
    }

    private static MenuRect checkboxBounds(MenuRoute route, MenuRect row) {
        if (route == MenuRoute.AUDIO) {
            return Proposal3OverlayCatalog.AUDIO_MUTE_CHECKBOX;
        }
        if (route == MenuRoute.OPTION_PICKER) {
            return new MenuRect(row.x() + 8,
                    row.y() + Math.max(0, (row.height() - 36) / 2), 36, 36);
        }
        int size = 48;
        return new MenuRect(row.x() + 172,
                row.y() + Math.max(0, (row.height() - size) / 2), size, size);
    }

    static MenuRect checkboxBoundsForAudit(MenuRoute route, MenuRect row) {
        return checkboxBounds(route, row);
    }

    private void paintChoiceField(MenuRoute route, MenuRect row, String value, boolean selected,
            MenuRaster raster) {
        Proposal3WidgetSkins.Sprite choiceField = skins().choiceField();
        int left = row.right() - choiceField.width() - 4;
        int top = row.y() + Math.max(0, (row.height() - choiceField.height()) / 2);
        raster.paintSprite(choiceField, left, top);
        MenuRect valueBounds = new MenuRect(left + 12, top + 7, choiceField.width() - 48, 40);
        drawWidgetText(raster, value, valueBounds,
                selected ? MenuRaster.PAPER_TEXT : MenuRaster.PAPER_TEXT,
                MenuRaster.HorizontalAlignment.RIGHT, choiceTextRole(route));
    }

    static Proposal3GlyphAtlas.Role choiceTextRole(MenuRoute route) {
        return switch (route) {
            case SYSTEM, DISPLAY, OPTIONAL_DEVICES -> Proposal3GlyphAtlas.Role.NOTICE;
            default -> Proposal3GlyphAtlas.Role.SMALL;
        };
    }

    private void paintRowIcon(MenuRoute route, int entryIndex,
            Proposal3OverlayCatalog.Slot slot, MenuRaster raster) {
        Proposal3WidgetSkins.Sprite icon = switch (route) {
            case DATA_MEDIA -> skins().dataRowIcon(entryIndex);
            case ABOUT -> skins().aboutRowIcon(entryIndex);
            default -> null;
        };
        if (icon == null) {
            return;
        }
        int left = slot.bounds().x() + 4;
        int top = slot.bounds().y() + Math.max(0, (slot.bounds().height() - icon.height()) / 2);
        raster.paintSprite(icon, left, top);
    }

    private static boolean isUsedState(Entry entry) {
        return "USED".equals(display(entry.detail));
    }

    /** Framed red save seal, intentionally independent from row focus coloring. */
    private static void paintStateUsedSeal(Proposal3OverlayCatalog.Slot slot, MenuRaster raster) {
        MenuRect bounds = slot.bounds();
        int left = bounds.right() - 40;
        int top = bounds.y() + Math.max(0, (bounds.height() - 30) / 2);
        // A round-cornered 30px cartridge/save seal. The pale label and dark write-window make
        // an occupied row recognizable even when the selected background is also warm red.
        raster.fill(new MenuRect(left + 6, top, 18, 3), MenuRaster.PAPER_TEXT);
        raster.fill(new MenuRect(left + 3, top + 3, 24, 24), MenuRaster.PAPER_TEXT);
        raster.fill(new MenuRect(left + 6, top + 27, 18, 3), MenuRaster.PAPER_TEXT);
        raster.fill(new MenuRect(left + 6, top + 3, 18, 24), STATE_USED_SEAL);
        raster.fill(new MenuRect(left + 3, top + 6, 24, 18), STATE_USED_SEAL);
        raster.fill(new MenuRect(left + 8, top + 6, 14, 6), MenuRaster.PAPER_TEXT);
        raster.fill(new MenuRect(left + 9, top + 15, 12, 8), MenuRaster.INK);
        raster.fill(new MenuRect(left + 12, top + 17, 6, 3), MenuRaster.PAPER_TEXT);
    }

    /** A compact, framed chevron row communicates that D-pad movement reveals more items. */
    private void paintScrollWidget(MenuRoute route, Proposal3OverlayCatalog.Slot slot, boolean up,
            MenuRaster raster) {
        paintSurface(raster, rowSurfaceBounds(route, slot), slot.surface(), false);
        MenuRect bounds = slot.bounds();
        int centerX = bounds.x() + bounds.width() / 2;
        int centerY = bounds.y() + bounds.height() / 2;
        int top = centerY - 8;
        for (int step = 0; step < 4; step++) {
            int y = top + (up ? 3 - step : step) * 4;
            raster.fill(new MenuRect(centerX - 16 + step * 4, y, 5, 4),
                    MenuRaster.PAPER_TEXT);
            raster.fill(new MenuRect(centerX + 11 - step * 4, y, 5, 4),
                    MenuRaster.PAPER_TEXT);
        }
    }

    private void paintActionWidget(MenuRoute route, MenuPresentation presentation,
            Prepared prepared, Proposal3OverlayCatalog.RouteLayout layout, Entry entry,
            int actionIndex, Proposal3OverlayCatalog.Slot slot, boolean selected,
            MenuRaster raster) {
        paintSurface(raster, expand(slot.bounds(), 3), slot.surface(), selected);
        MenuRect bounds = slot.bounds();
        Proposal3WidgetSkins.Sprite icon = skins().actionIcon(route, actionIndex);
        MenuRect text;
        int color = selected || slot.surface() == Proposal3OverlayCatalog.Surface.DARK
                ? MenuRaster.PAPER_TEXT : MenuRaster.INK;
        String label = actionLabel(route, entry, actionIndex);
        Proposal3GlyphAtlas.Role role = route == MenuRoute.PRINTER_PAPER && actionIndex == 1
                ? Proposal3GlyphAtlas.Role.SMALL : actionTextRole(route);
        int contentWidth = atlas().measure(role, display(label))
                + (icon == null ? 0 : icon.width() + 12);
        int contentLeft = bounds.x() + Math.max(10, (bounds.width() - contentWidth) / 2);
        if (icon != null) {
            int iconTop = bounds.y() + Math.max(0, (bounds.height() - icon.height()) / 2);
            raster.paintSprite(icon, contentLeft, iconTop, color);
            text = new MenuRect(contentLeft + icon.width() + 12, bounds.y() + 4,
                    Math.max(1, bounds.right() - contentLeft - icon.width() - 22),
                    Math.max(1, bounds.height() - 8));
        } else {
            text = new MenuRect(bounds.x() + 10, bounds.y() + 4,
                    Math.max(1, bounds.width() - 20), Math.max(1, bounds.height() - 8));
        }
        drawWidgetText(raster, label, text, color, icon == null
                        ? MenuRaster.HorizontalAlignment.CENTER : MenuRaster.HorizontalAlignment.LEFT,
                role);
        if (selected && layout.actionMarker() && layout.marker() != null) {
            paintMarker(presentation, prepared, layout, slot, raster);
        }
    }

    private void drawWidgetText(MenuRaster raster, String value, MenuRect bounds, int color,
            MenuRaster.HorizontalAlignment alignment, Proposal3GlyphAtlas.Role role) {
        Proposal3GlyphAtlas glyphs = atlas();
        raster.drawText(glyphs, role, value, bounds, color, alignment);
    }

    private void drawRows(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, MenuRaster raster) {
        List<Proposal3OverlayCatalog.Slot> slots = rowSlots(layout, prepared.rows.size());
        if (slots.isEmpty()) {
            return;
        }
        if (!layout.scrollable() && prepared.rows.size() < layout.rows().size()) {
            // Capability-filtered submenu pages must not leave the template's unused outlined
            // slots visible. Clear each complete widget footprint before painting live rows.
            for (int index = prepared.rows.size(); index < layout.rows().size(); index++) {
                paintSurface(raster, expand(layout.rows().get(index).bounds(), 3),
                        layout.rows().get(index).surface(), false);
            }
            if (route == MenuRoute.DISPLAY && prepared.rows.size() < 3) {
                paintDarkAperture(raster, new MenuRect(374, 326, 538, 114));
            } else if (route == MenuRoute.OPTIONAL_DEVICES && prepared.rows.size() < 6) {
                paintDarkAperture(raster, new MenuRect(350, 320, 560, 215));
            }
        }
        ScrollWindow window = scrollWindow(prepared.rows, presentation.focusedIndex(),
                slots.size(), layout.scrollable());
        String focused = focusedId(presentation, prepared);
        for (int visible = 0; visible < slots.size(); visible++) {
            Proposal3OverlayCatalog.Slot slot = slots.get(visible);
            if (window.topArrow() && visible == 0) {
                paintScrollWidget(route, slot, true, raster);
                continue;
            }
            if (window.bottomArrow() && visible == slots.size() - 1) {
                paintScrollWidget(route, slot, false, raster);
                continue;
            }
            int index = window.entryIndex(visible);
            if (index >= prepared.rows.size()) {
                paintSurface(raster, rowSurfaceBounds(route, slot), slot.surface(), false);
                continue;
            }
            Entry entry = prepared.rows.get(index);
            paintRowWidget(route, presentation, prepared, layout, entry, index, slot,
                    entry.id.equals(focused), raster);
        }
        if (route == MenuRoute.PAUSE_CONSOLE) {
            // Draw these last: selection changes must not overpaint a separator, and the
            // separators establish the intentional two-pixel gap between every equal-height row.
            for (MenuRect divider : Proposal3OverlayCatalog.PAUSE_DIVIDERS) {
                raster.fill(divider, PAPER_MATTE);
            }
        } else if (route == MenuRoute.SAVE_STATES || route == MenuRoute.RECENT_GAMES
                || route == MenuRoute.SETTINGS
                || route == MenuRoute.OPTION_PICKER
                || route == MenuRoute.TOUCH_CONTROLS
                || route == MenuRoute.CONTROLLER_MAPPING || route == MenuRoute.LIBRARY
                || route == MenuRoute.CHOOSE_ROM) {
            // Expanded row skins repaint focus cleanly. Restore the rail dividers afterwards so
            // every seven-item viewport remains evenly spaced, including arrow rows.
            for (MenuRect divider : scrollDividers(route, slots.size())) {
                raster.fill(divider, PAPER_MATTE);
            }
        }
    }

    private static List<MenuRect> scrollDividers(MenuRoute route, int slotCount) {
        return switch (route) {
            case SAVE_STATES, RECENT_GAMES -> Proposal3OverlayCatalog.SAVE_DIVIDERS;
            case SETTINGS -> Proposal3OverlayCatalog.compactSettingsDividers(slotCount);
            case OPTION_PICKER -> Proposal3OverlayCatalog.SETTINGS_DIVIDERS;
            case TOUCH_CONTROLS -> Proposal3OverlayCatalog.compactTouchDividers(slotCount);
            case CONTROLLER_MAPPING -> Proposal3OverlayCatalog.CONTROLLER_DIVIDERS;
            case LIBRARY -> Proposal3OverlayCatalog.LIBRARY_DIVIDERS;
            case CHOOSE_ROM -> Proposal3OverlayCatalog.CHOOSE_ROM_DIVIDERS;
            default -> List.of();
        };
    }

    private static List<Proposal3OverlayCatalog.Slot> rowSlots(
            Proposal3OverlayCatalog.RouteLayout layout, int itemCount) {
        if (layout.route() == MenuRoute.SETTINGS) {
            return Proposal3OverlayCatalog.compactSettingsRows(itemCount);
        }
        if (layout.route() == MenuRoute.TOUCH_CONTROLS) {
            return Proposal3OverlayCatalog.compactTouchRows(itemCount);
        }
        if (layout.route() == MenuRoute.DISPLAY || layout.route() == MenuRoute.OPTIONAL_DEVICES) {
            int count = Math.max(1, Math.min(layout.rows().size(), itemCount));
            return layout.rows().subList(0, count);
        }
        return layout.rows();
    }

    private static MenuRect rowSurfaceBounds(MenuRoute route, Proposal3OverlayCatalog.Slot slot) {
        return route == MenuRoute.PAUSE_CONSOLE ? slot.bounds() : expand(slot.bounds(), 2);
    }

    private void drawActions(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, MenuRaster raster) {
        if (layout.actions().isEmpty()) {
            return;
        }
        List<Entry> actions = prepared.actions;
        String focused = focusedId(presentation, prepared);
        for (int index = 0; index < layout.actions().size(); index++) {
            Entry entry = actionFor(route, actions, index);
            if (entry == null) {
                paintSurface(raster, expand(layout.actions().get(index).bounds(), 3),
                        layout.actions().get(index).surface(), false);
                continue;
            }
            paintActionWidget(route, presentation, prepared, layout, entry, index,
                    layout.actions().get(index), entry.id.equals(focused), raster);
        }
    }

    private static Entry actionFor(MenuRoute route, List<Entry> actions, int index) {
        if (route == MenuRoute.ABOUT && index == 0) {
            return actions.isEmpty() ? null : actions.get(0);
        }
        return index < actions.size() ? actions.get(index) : null;
    }

    private static String actionLabel(MenuRoute route, Entry entry, int index) {
        if (route == MenuRoute.ABOUT) {
            return "GITHUB.COM/TREKAWEK/COFFEE-GB";
        }
        if (route == MenuRoute.CONFIRM_ACTION && index == 1) {
            return confirmationVerb(entry.detail);
        }
        return display(entry.label);
    }

    private static boolean supportsDetail(MenuRoute route, Entry entry) {
        return switch (route) {
            case AUDIO, DISPLAY, TOUCH_CONTROLS, CONTROLLER_MAPPING,
                    OPTIONAL_DEVICES, SYSTEM, OPTION_PICKER -> true;
            default -> false;
        };
    }

    private void drawRouteWidgets(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, MenuRaster raster) {
        switch (route) {
            case PAUSE_CONSOLE -> drawPause(presentation, raster);
            case SAVE_STATES, RECENT_GAMES -> drawSaveSide(presentation, raster);
            case AUDIO -> drawAudioSide(presentation, prepared, raster);
            case SYSTEM, DISPLAY, OPTIONAL_DEVICES -> drawSettingsIllustration(route, raster);
            case OPTION_PICKER -> drawOptionPickerIllustration(presentation, raster);
            case PRINTER_PAPER -> drawPrinter(presentation, raster);
            case SETTINGS, TOUCH_CONTROLS, CONTROLLER_MAPPING,
                    DATA_MEDIA, LIBRARY, CHOOSE_ROM, ABOUT -> {
                // Their previews and route copy are handled by the text catalog above.
            }
        }
    }

    private void drawPause(MenuPresentation presentation, MenuRaster raster) {
        if (presentation.preview().state() == MenuPreview.State.READY) {
            raster.copyPreview(presentation.preview(), Proposal3OverlayCatalog.PAUSE_PREVIEW,
                    0xff121b14);
            return;
        }
        raster.fill(Proposal3OverlayCatalog.PAUSE_PREVIEW, 0xff121b14);
        drawWidgetText(raster, "NO FRAME AVAILABLE", Proposal3OverlayCatalog.PAUSE_PREVIEW,
                MenuRaster.PAPER_TEXT, MenuRaster.HorizontalAlignment.CENTER,
                Proposal3GlyphAtlas.Role.SMALL);
    }

    private static void drawSaveSide(MenuPresentation p, MenuRaster r) {
        // State thumbnails are detached page data; an empty/missing slot paints a blank well.
        r.fill(Proposal3OverlayCatalog.SAVE_PREVIEW, PAPER_MATTE);
        if (p.preview().state() == MenuPreview.State.READY) {
            r.copyPreview(p.preview(), Proposal3OverlayCatalog.SAVE_PREVIEW, PAPER_MATTE);
        }
    }

    private void drawSettingsIllustration(MenuRoute route, MenuRaster raster) {
        Proposal3WidgetSkins.Sprite illustration = skins().settingsIllustration(route);
        if (illustration == null) {
            return;
        }
        // Repaint the old illustration/text-free aperture first. This removes the legacy chip,
        // divider, and peripheral labels without touching the surrounding approved bezel.
        MenuRect clear = route == MenuRoute.OPTIONAL_DEVICES
                ? new MenuRect(17, 118, 318, 524)
                : new MenuRect(22, 129, 334, 467);
        paintPaperAperture(raster, clear);
        if (route == MenuRoute.OPTIONAL_DEVICES) {
            // The peripherals page has no footer actions; clear the legacy optional-device
            // button strip while retaining the approved paper panel beneath the rail.
            paintPaperAperture(raster, new MenuRect(350, 540, 560, 98));
        }
        int left;
        int top;
        if (route == MenuRoute.SYSTEM) {
            left = 35;
            top = 173;
        } else if (route == MenuRoute.DISPLAY) {
            left = 49;
            top = 145;
        } else {
            left = 26;
            top = 181;
        }
        raster.paintSprite(illustration, left, top);
    }

    private void drawOptionPickerIllustration(MenuPresentation presentation, MenuRaster raster) {
        MenuRoute origin = optionPickerIllustrationRoute(presentation.context());
        if (origin == null) {
            return;
        }
        Proposal3WidgetSkins.Sprite illustration = skins().settingsIllustration(origin);
        MenuRect clear = Proposal3OverlayCatalog.OPTION_PICKER_ILLUSTRATION;
        paintPaperAperture(raster, clear);
        int left = clear.x() + (clear.width() - illustration.width()) / 2;
        int top = switch (origin) {
            case SYSTEM -> 170;
            case DISPLAY -> 145;
            case OPTIONAL_DEVICES -> 180;
            default -> throw new IllegalStateException("Unsupported picker illustration: " + origin);
        };
        raster.paintSprite(illustration, left, top);
    }

    static MenuRoute optionPickerIllustrationRoute(String context) {
        return switch (display(context)) {
            case "DMG GAMES", "CGB GAMES", "BOOTSTRAP", "EXECUTION MODE" -> MenuRoute.SYSTEM;
            case "DMG COLORS" -> MenuRoute.DISPLAY;
            case "CAMERA", "GAMEPAD" -> MenuRoute.OPTIONAL_DEVICES;
            default -> null;
        };
    }

    private void paintPaperAperture(MenuRaster raster, MenuRect bounds) {
        int top = bounds.y();
        int remaining = bounds.height();
        while (remaining > 0) {
            int height = Math.min(160, remaining);
            paintSurface(raster, new MenuRect(bounds.x(), top, bounds.width(), height),
                    Proposal3OverlayCatalog.Surface.PAPER, false);
            top += height;
            remaining -= height;
        }
    }

    private void paintDarkAperture(MenuRaster raster, MenuRect bounds) {
        int top = bounds.y();
        int remaining = bounds.height();
        while (remaining > 0) {
            int height = Math.min(160, remaining);
            paintSurface(raster, new MenuRect(bounds.x(), top, bounds.width(), height),
                    Proposal3OverlayCatalog.Surface.DARK, false);
            top += height;
            remaining -= height;
        }
    }

    private void drawAudioSide(MenuPresentation p, Prepared prepared, MenuRaster r) {
        Entry volume = prepared.volume;
        if (volume == null) {
            // A host that cannot expose any audio control may still open this route as a B-only
            // status page. Remove the whole control, including its percentage ticks.
            r.fill(Proposal3OverlayCatalog.AUDIO_SLIDER_ZONE, PAPER_MATTE);
        } else if (volume.adjustable || volume.progress >= 0) {
            int progress = volume.progress >= 0 ? volume.progress : parsePercent(volume.detail);
            r.paintWidget(skins().surface(Proposal3WidgetSkins.Surface.PAPER),
                    Proposal3OverlayCatalog.AUDIO_SLIDER_ZONE);
            r.drawAudioSlider(Proposal3OverlayCatalog.AUDIO_SLIDER, progress);
            if (volume.sourceIndex == p.focusedIndex()) {
                // Volume is an adjustable control, not a normal text row. Give it the same
                // unmistakable left-edge focus cue as the selectable mute row.
                r.drawFocusArrow(Proposal3OverlayCatalog.AUDIO_VOLUME_ARROW.x(),
                        Proposal3OverlayCatalog.AUDIO_SLIDER.y()
                                + Proposal3OverlayCatalog.AUDIO_SLIDER.height() / 2,
                        MenuRaster.INK);
            }
            // The template is text-free, including at the canonical 75% value. Always paint
            // the dynamic label in a frame-safe left-panel aperture.
            r.paintWidget(skins().surface(Proposal3WidgetSkins.Surface.PAPER),
                    Proposal3OverlayCatalog.AUDIO_VOLUME_LABEL);
            drawWidgetText(r, "VOLUME " + progress + "%",
                    Proposal3OverlayCatalog.AUDIO_VOLUME_LABEL, MenuRaster.INK,
                    MenuRaster.HorizontalAlignment.CENTER, Proposal3GlyphAtlas.Role.MEDIUM);
        }
    }

    private void drawPrinter(MenuPresentation p, MenuRaster r) {
        if (p.preview().state() == MenuPreview.State.READY) {
            r.copyPreview(p.preview(), Proposal3OverlayCatalog.PRINTER_PREVIEW, PAPER_MATTE);
        } else if (p.preview().state() == MenuPreview.State.LOADING) {
            r.fill(Proposal3OverlayCatalog.PRINTER_PREVIEW, PAPER_MATTE);
            drawWidgetText(r, "LOADING", Proposal3OverlayCatalog.PRINTER_PREVIEW,
                    MenuRaster.INK,
                    MenuRaster.HorizontalAlignment.CENTER, Proposal3GlyphAtlas.Role.MEDIUM);
        } else {
            // The route template contains a decorative sample print. An empty runtime roll must
            // replace that sample with an explicit status so an unavailable printer never looks
            // as though it has retained paper.
            r.fill(Proposal3OverlayCatalog.PRINTER_PREVIEW, PAPER_MATTE);
            drawWidgetText(r, "NO PAPER", Proposal3OverlayCatalog.PRINTER_PREVIEW,
                    MenuRaster.INK,
                    MenuRaster.HorizontalAlignment.CENTER, Proposal3GlyphAtlas.Role.MEDIUM);
        }
    }

    private void overlayChanged(MenuRaster raster, String value, String canonical, MenuRect bounds,
            boolean paperSurface, int color, MenuRaster.HorizontalAlignment alignment) {
        String normalized = display(value);
        if (same(normalized, display(canonical))) {
            return;
        }
        Proposal3WidgetSkins.Surface surface = paperSurface ? Proposal3WidgetSkins.Surface.PAPER
                : Proposal3WidgetSkins.Surface.DARK;
        raster.paintWidget(skins().surface(surface), bounds);
        drawWidgetText(raster, normalized, bounds, color, alignment,
                Proposal3GlyphAtlas.Role.MEDIUM);
    }

    private static String[] twoLines(String value) {
        String normalized = display(value);
        if (normalized.isEmpty()) {
            return new String[]{"", ""};
        }
        int midpoint = normalized.length() / 2;
        int before = normalized.lastIndexOf(' ', midpoint);
        int after = normalized.indexOf(' ', midpoint + 1);
        int split;
        if (before < 0) {
            split = after;
        } else if (after < 0) {
            split = before;
        } else {
            split = midpoint - before <= after - midpoint ? before : after;
        }
        if (split <= 0) {
            return new String[]{normalized, ""};
        }
        return new String[]{normalized.substring(0, split).trim(),
                normalized.substring(split + 1).trim()};
    }

    private static String confirmationVerb(String detail) {
        String value = display(detail);
        if (value.isEmpty() || value.equals("RESET GAME")) {
            return "RESET";
        }
        if (value.equals("STOP GAME")) {
            return "STOP";
        }
        if (value.equals("DELETE STATE")) {
            return "DELETE";
        }
        if (value.equals("OVERWRITE STATE")) {
            return "OVERWRITE";
        }
        if (value.equals("CLEAR PAPER")) {
            return "CLEAR";
        }
        return value;
    }

    private static MenuRect labelBounds(MenuRoute route, MenuRect row, boolean drawsDetail,
            int rowIndex) {
        int left = switch (route) {
            case DATA_MEDIA -> row.x() + 52;
            // Notice rows reserve the left icon rail, but use the remaining full row width for
            // normal-width ByteBounce metrics. Their labels have no detail column to reserve.
            case ABOUT -> row.x() + (rowIndex == 0 ? 22 : 98);
            case CONTROLLER_MAPPING -> row.x() + 12;
            case SYSTEM, DISPLAY -> row.x() + 16;
            default -> row.x() + 52;
        };
        int detail = drawsDetail ? detailWidth(route) : 0;
        int reserved = route == MenuRoute.ABOUT && rowIndex > 0
                ? 0 : detail == 0 ? 20 : detail + 22;
        return new MenuRect(left, row.y() + 4, Math.max(1, row.right() - left - reserved),
                Math.max(1, row.height() - 8));
    }

    static MenuRect rowLabelBoundsForAudit(MenuRoute route, MenuRect row, boolean drawsDetail,
            int rowIndex) {
        return labelBounds(route, row, drawsDetail, rowIndex);
    }

    private static MenuRect detailBounds(MenuRoute route, MenuRect row) {
        int width = detailWidth(route);
        return new MenuRect(Math.max(row.x() + 1, row.right() - width - 10), row.y() + 4, width,
                Math.max(1, row.height() - 8));
    }

    static MenuRect rowDetailBoundsForAudit(MenuRoute route, MenuRect row) {
        return detailBounds(route, row);
    }

    private static int detailWidth(MenuRoute route) {
        return switch (route) {
            case SAVE_STATES -> 130;
            case AUDIO, TOUCH_CONTROLS -> 105;
            case CONTROLLER_MAPPING -> 180;
            case OPTIONAL_DEVICES -> 175;
            case LIBRARY -> 190;
            case SYSTEM -> 250;
            case DISPLAY -> 250;
            default -> 0;
        };
    }

    static Proposal3GlyphAtlas.Role detailTextRole(MenuRoute route) {
        return switch (route) {
            case SYSTEM, DISPLAY, OPTIONAL_DEVICES -> Proposal3GlyphAtlas.Role.NOTICE;
            default -> Proposal3GlyphAtlas.Role.MEDIUM;
        };
    }

    private static String focusedId(MenuPresentation p, Prepared prepared) {
        int index = p.focusedIndex();
        if (prepared.volume != null && prepared.volume.sourceIndex == index) {
            return prepared.volume.id;
        }
        for (Entry entry : prepared.rows) {
            if (entry.sourceIndex == index) {
                return entry.id;
            }
        }
        for (Entry entry : prepared.actions) {
            if (entry.sourceIndex == index) {
                return entry.id;
            }
        }
        return null;
    }

    private static String label(MenuRoute route, Entry entry, int index) {
        String candidate = display(entry.label);
        String canonical = canonicalLabel(route, entry, index);
        if (candidate.isEmpty() || isDefaultAlias(route, entry, candidate, canonical)) {
            return canonical;
        }
        return candidate;
    }

    private static String detail(MenuRoute route, Entry entry, int index) {
        String candidate = display(entry.detail);
        String canonical = canonicalDetail(route, entry, index);
        if (candidate.isEmpty() || isGenericDetail(candidate)) {
            return canonical;
        }
        return candidate;
    }

    private static boolean isGenericDetail(String detail) {
        return detail.equals("SAVE / LOAD") || detail.equals("CURRENT")
                || detail.equals("FIXED") || detail.equals("READY");
    }

    private static boolean isDefaultAlias(MenuRoute route, Entry e, String value, String canonical) {
        if (same(value, canonical)) {
            return true;
        }
        return switch (route) {
            case AUDIO -> e.id.equals("mute-audio") && value.equals("MUTE");
            case TOUCH_CONTROLS -> (e.id.equals("button-opacity") && value.equals("OPACITY"))
                    || (e.id.equals("reset-touch") && value.equals("RESET"));
            case CHOOSE_ROM -> value.equals("GAME A") || value.equals("GAME B")
                    || value.equals("GAME C");
            case LIBRARY -> value.equals("RECENT ROM") || value.equals("CHOOSE ROM")
                    || value.equals("CLEAR RECENTS");
            default -> false;
        };
    }

    private static String canonicalLabel(MenuRoute route, Entry e, int index) {
        return switch (route) {
            case PAUSE_CONSOLE -> switch (e.id) {
                case "resume" -> "RESUME";
                case "save-state" -> "SAVE STATE";
                case "load-state" -> "LOAD STATE";
                case "reset" -> "RESET GAME";
                case "settings" -> "SETTINGS";
                case "recent-games" -> "RECENT GAMES";
                default -> display(e.label);
            };
            case SAVE_STATES -> "SLOT " + Math.max(0, e.slotNumber);
            case RECENT_GAMES -> display(e.label);
            case SETTINGS -> switch (e.id) {
                case "system" -> "SYSTEM";
                case "display" -> "DISPLAY";
                case "audio" -> "AUDIO";
                case "peripherals" -> "PERIPHERALS";
                case "touch-controls" -> "CONTROLS";
                case "controller-mapping" -> "CONTROLLER MAPPING";
                case "optional-devices" -> "OPTIONAL DEVICES";
                case "video" -> "VIDEO";
                case "system-profile" -> "SYSTEM PROFILE";
                case "rewind-save" -> "REWIND & SAVE";
                case "data-media" -> "DATA & MEDIA";
                case "about" -> "ABOUT";
                default -> display(e.label);
            };
            case AUDIO -> switch (e.id) {
                case "mute-audio" -> "MUTE";
                default -> display(e.label);
            };
            case TOUCH_CONTROLS -> switch (e.id) {
                case "haptics" -> "HAPTIC FEEDBACK";
                case "button-opacity" -> "BUTTON OPACITY";
                case "reset-touch" -> "RESET DEFAULTS";
                case "controller-mapping" -> "BUTTON MAPPING";
                default -> display(e.label);
            };
            case CONTROLLER_MAPPING -> switch (e.id) {
                case "map-a" -> "A";
                case "map-b" -> "B";
                case "map-start" -> "START";
                case "map-select" -> "SELECT";
                case "map-up" -> "UP";
                case "map-down" -> "DOWN";
                case "map-left" -> "LEFT";
                case "map-right" -> "RIGHT";
                case "horizontal-axis" -> "HORIZONTAL AXIS";
                case "vertical-axis" -> "VERTICAL AXIS";
                case "reset-mappings" -> "RESET MAPPINGS";
                default -> display(e.label);
            };
            case OPTIONAL_DEVICES -> switch (e.id) {
                case "camera" -> "CAMERA";
                case "gamepad" -> "GAMEPAD";
                case "gps" -> "GPS";
                case "live-camera" -> "LIVE CAMERA";
                case "game-boy-printer" -> "GAME BOY PRINTER";
                case "calibrate-tilt" -> "CALIBRATE TILT";
                case "preview-printer-paper" -> "PREVIEW PRINTER PAPER";
                case "export-share-paper" -> "EXPORT & SHARE PAPER";
                default -> display(e.label);
            };
            case DATA_MEDIA -> switch (e.id) {
                case "import-battery" -> "IMPORT BATTERY SAVE";
                case "export-battery" -> "EXPORT BATTERY SAVE";
                case "import-state-0" -> "IMPORT STATE SLOT 0";
                case "export-state-0" -> "EXPORT STATE SLOT 0";
                case "export-screenshot" -> "EXPORT SCREENSHOT";
                case "preview-printer-paper" -> "PRINTER PAPER";
                default -> display(e.label);
            };
            case LIBRARY -> display(e.label);
            case CHOOSE_ROM -> chooseLabel(e, index);
            case OPTION_PICKER -> display(e.label);
            case SYSTEM -> switch (e.id) {
                case "dmg-games" -> "DMG GAMES";
                case "cgb-games" -> "CGB GAMES";
                case "bootstrap" -> "BOOTSTRAP";
                case "video-status" -> "VIDEO";
                case "profile-status" -> "SYSTEM PROFILE";
                case "rewind-save-status" -> "REWIND & SAVE";
                case "screen-fit" -> "SCREEN FIT";
                case "color-correction" -> "COLOR CORRECTION";
                case "frame-blending" -> "FRAME BLENDING";
                default -> display(e.label);
            };
            case DISPLAY -> switch (e.id) {
                case "sgb-border" -> "SGB BORDER";
                case "dmg-colors" -> "DMG COLORS";
                default -> display(e.label);
            };
            case ABOUT -> switch (e.id) {
                case "privacy-notices" -> "PRIVACY & NOTICES";
                case "network" -> "NO NETWORK ACCESS";
                case "storage" -> "NO BROAD STORAGE ACCESS";
                case "live-camera" -> "CAMERA ONLY WHEN ENABLED";
                case "source-notices", "source", "third-party" -> "SOURCE & THIRD-PARTY NOTICES";
                default -> display(e.label);
            };
            case CONFIRM_ACTION, PRINTER_PAPER -> display(e.label);
        };
    }

    private static String canonicalDetail(MenuRoute route, Entry e, int index) {
        return switch (route) {
            case SAVE_STATES, RECENT_GAMES -> "";
            case AUDIO -> e.id.equals("mute-audio") ? "OFF" : "";
            case TOUCH_CONTROLS -> e.id.equals("haptics") ? "ON"
                    : e.id.equals("button-opacity") ? "70%" : "";
            case CONTROLLER_MAPPING -> switch (e.id) {
                case "map-a" -> "BUTTON 1";
                case "map-b" -> "BUTTON 2";
                case "map-start" -> "START";
                case "map-select" -> "BACK";
                case "map-up" -> "DPAD UP";
                case "map-down" -> "DPAD DOWN";
                case "map-left" -> "DPAD LEFT";
                case "map-right" -> "DPAD RIGHT";
                case "horizontal-axis", "vertical-axis" -> "NORMAL";
                default -> "";
            };
            case OPTIONAL_DEVICES -> switch (e.id) {
                case "camera", "gamepad" -> "AUTO";
                case "gps" -> "OFF";
                case "rumble", "live-camera", "game-boy-printer" -> "OFF";
                default -> "";
            };
            case LIBRARY -> "";
            case SYSTEM -> switch (e.id) {
                case "dmg-games", "cgb-games" -> "AUTO";
                case "bootstrap" -> "SKIP";
                case "execution-mode" -> "PERFORMANCE";
                case "video-status" -> "NEAREST NEIGHBOR / ASPECT FIT";
                case "profile-status" -> "SELECTED ON OPEN";
                case "rewind-save-status" -> "PORTABLE DEFAULTS";
                default -> "";
            };
            case DISPLAY -> switch (e.id) {
                case "dmg-colors" -> "GREEN";
                default -> "";
            };
            default -> "";
        };
    }

    private static String chooseLabel(Entry e, int index) {
        return switch (index) {
            case 0 -> "ADVENTURE BOY.GB";
            case 1 -> "POCKET CAMERA.GBC";
            case 2 -> "COFFEE DEMO.GB";
            default -> display(e.label);
        };
    }

    private static int parseSlot(String id) {
        if (id == null) {
            return -1;
        }
        String value = id.startsWith("slot:") ? id.substring(5)
                : id.startsWith("slot-") ? id.substring(5).split("-", 2)[0] : "";
        if (value.isEmpty()) {
            return -1;
        }
        try {
            int slot = Integer.parseInt(value);
            return slot >= 0 && slot <= 9 ? slot : -1;
        } catch (NumberFormatException ignored) {
            return -1;
        }
    }

    private static int parsePercent(String value) {
        String normalized = display(value).replace("%", "").trim();
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(normalized)));
        } catch (NumberFormatException ignored) {
            return 75;
        }
    }

    private static String valueAt(List<String> values, int index, String fallback) {
        return values != null && index >= 0 && index < values.size() && !values.get(index).isEmpty()
                ? values.get(index) : fallback;
    }

    private static String display(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static boolean same(String left, String right) {
        return display(left).equals(display(right));
    }

    private static Entry find(List<Entry> entries, String id) {
        for (Entry entry : entries) {
            if (id.equals(entry.id)) {
                return entry;
            }
        }
        return null;
    }

    private static final class Prepared {
        private final List<Entry> rows = new ArrayList<>();
        private final List<Entry> actions = new ArrayList<>();
        private Entry volume;
    }

    /** Maps a focused full list into a seven-row viewport with non-selectable edge cues. */
    private record ScrollWindow(int start, int contentCount, boolean topArrow,
                                boolean bottomArrow) {
        int firstContentIndex() {
            return topArrow ? 1 : 0;
        }

        int visualIndex(int entryIndex) {
            if (entryIndex < start || entryIndex >= start + contentCount) {
                return -1;
            }
            return firstContentIndex() + entryIndex - start;
        }

        int entryIndex(int visualIndex) {
            return start + visualIndex - firstContentIndex();
        }
    }

    private static final class Entry {
        private final String id;
        private final String label;
        private final String detail;
        private final boolean enabled;
        private final int sourceIndex;
        private final boolean adjustable;
        private final int progress;
        private final int slotNumber;

        private Entry(String id, String label, String detail, boolean enabled, int sourceIndex,
                boolean adjustable, int progress, int slotNumber) {
            this.id = id;
            this.label = label;
            this.detail = detail;
            this.enabled = enabled;
            this.sourceIndex = sourceIndex;
            this.adjustable = adjustable;
            this.progress = progress;
            this.slotNumber = slotNumber;
        }
    }
}
