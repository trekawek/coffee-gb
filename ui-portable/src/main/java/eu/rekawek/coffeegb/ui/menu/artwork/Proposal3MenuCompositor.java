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
            case CHOOSE_ROM -> Proposal3GlyphAtlas.Role.DISPLAY;
            case CONTROLLER_MAPPING, OPTIONAL_DEVICES, LIBRARY, SYSTEM ->
                    Proposal3GlyphAtlas.Role.SMALL;
            case ABOUT -> rowIndex == 0 ? Proposal3GlyphAtlas.Role.SEMIBOLD
                    : Proposal3GlyphAtlas.Role.NOTICE;
            default -> Proposal3GlyphAtlas.Role.MEDIUM;
        };
    }

    /** Fixed action role; selection never participates in this decision. */
    static Proposal3GlyphAtlas.Role actionTextRole(MenuRoute route) {
        return switch (route) {
            case SAVE_STATES, CHOOSE_ROM -> Proposal3GlyphAtlas.Role.SEMIBOLD;
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
                if (actionIds.add(visualId)) {
                    prepared.actions.add(entry(item, index, visualId, -1));
                }
            } else if (rowIds.add(visualId)) {
                int slotNumber = parseSlot(visualId);
                prepared.rows.add(entry(item, index, visualId, slotNumber));
            }
        }

        if (route == MenuRoute.LIBRARY) {
            addSyntheticAction(prepared, actionIds, "open-rom", "OPEN ROM",
                    findSource(items, "open-rom"));
        }
        if (route == MenuRoute.CHOOSE_ROM) {
            addSyntheticAction(prepared, actionIds, "open-selected", "OPEN SELECTED",
                    findSource(items, "open-selected"));
            addSyntheticAction(prepared, actionIds, "cancel", "CANCEL", findSource(items, "cancel"));
        }
        if (route == MenuRoute.ABOUT) {
            addSyntheticAction(prepared, actionIds, "source",
                    "GITHUB.COM/TREKAWEK/COFFEE-GB",
                    findSource(items, "source-notices"));
        }
        orderActions(route, prepared.actions);
        return prepared;
    }

    private static void orderActions(MenuRoute route, List<Entry> actions) {
        List<String> order = switch (route) {
            case SAVE_STATES -> List.of();
            case AUDIO -> List.of("save-audio", "cancel-audio");
            case TOUCH_CONTROLS -> List.of("save-touch", "cancel-touch");
            case OPTIONAL_DEVICES -> List.of("save-devices", "cancel-devices");
            case LIBRARY -> List.of("open-rom");
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
        if (route == MenuRoute.LIBRARY
                && ("recent-rom".equals(id) || id.startsWith("recent:"))) {
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
            case SAVE_STATES -> false;
            case AUDIO -> id.equals("save-audio") || id.equals("cancel-audio");
            case TOUCH_CONTROLS -> id.equals("save-touch") || id.equals("cancel-touch");
            case OPTIONAL_DEVICES -> id.equals("save-devices") || id.equals("cancel-devices");
            case LIBRARY -> id.equals("open-rom");
            case CHOOSE_ROM -> id.equals("open-selected") || id.equals("cancel");
            case ABOUT -> id.equals("source") || id.equals("third-party");
            case CONFIRM_ACTION -> id.equals("cancel") || id.equals("confirm");
            case PRINTER_PAPER -> id.equals("clear-paper") || id.equals("export-share-paper");
            default -> false;
        };
    }

    private static int findSource(List<MenuPresentation.Item> items, String id) {
        for (int index = 0; index < items.size(); index++) {
            if (id.equals(items.get(index).id())) {
                return index;
            }
        }
        return -1;
    }

    private static void addSyntheticAction(Prepared prepared, Set<String> ids, String id,
            String label, int sourceIndex) {
        if (ids.add(id)) {
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
            int start = windowStart(prepared.rows, presentation.focusedIndex(),
                    layout.rows().size(), layout.scrollable());
            int visibleIndex = rowIndex - start;
            if (visibleIndex < 0 || visibleIndex >= layout.rows().size()) {
                if (rowIndex != 0 || layout.rows().isEmpty()) {
                    return;
                }
                visibleIndex = 0;
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
        }
        String[] footer = footerValues(route, presentation.footerHints());
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
        // retain their page-specific text (for example SAVE/CANCEL), while keycap artwork stays
        // untouched so no glyph is drawn over the approved A/B shapes.
        if (route == MenuRoute.PAUSE_CONSOLE) {
            return new String[]{"D-PAD MOVE", "A CHOOSE", "B BACK"};
        }
        String[] fallback = {"D-PAD MOVE", "A CHOOSE", "B BACK"};
        for (int index = 0; index < fallback.length && index < hints.size(); index++) {
            String hint = hints.get(index);
            if (hint != null && !hint.isBlank()) {
                fallback[index] = hint;
            }
        }
        return fallback;
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
                    ? "" : presentation.headerAction().isEmpty()
                    ? "BACK" : display(presentation.headerAction());
            case FOOTER_DPAD -> display(footer[0]);
            case FOOTER_BUTTON -> footerButton(footer[region.index()]);
            case FOOTER_LABEL -> footerLabel(footer[region.index()]);
            case SIDE_HEADING -> display(presentation.sideHeading());
            case SIDE_LINE -> display(valueAt(presentation.sideLines(), region.index(), ""));
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
            case CONFIRM_COPY_THREE -> display(valueAt(presentation.sideLines(), 1,
                    "SAME PAGE USED FOR")) + "\n" + display(valueAt(
                    presentation.sideLines(), 2, "STOP GAME AND DELETE STATE"));
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
        int y = target.bounds().y() + marker.relativeY();
        raster.paintSprite(skins().focusArrow(), x, y, MenuRaster.PAPER_TEXT);
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
            int start = windowStart(entries, focusedIndex, layout.rows().size(), layout.scrollable());
            int visibleIndex = entryIndex - start;
            if (visibleIndex >= 0 && visibleIndex < layout.rows().size()) {
                return layout.rows().get(visibleIndex);
            }
            if (!target && entryIndex == 0 && !layout.rows().isEmpty()) {
                return layout.rows().get(0);
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

    private static int windowStart(List<Entry> entries, int focusedIndex, int capacity,
            boolean scrollable) {
        if (!scrollable || entries.size() <= capacity) {
            return 0;
        }
        int focused = -1;
        for (int index = 0; index < entries.size(); index++) {
            if (entries.get(index).sourceIndex == focusedIndex) {
                focused = index;
                break;
            }
        }
        if (focused < 0) {
            return 0;
        }
        int start = focused - capacity / 2;
        return Math.max(0, Math.min(start, entries.size() - capacity));
    }

    private void paintRowWidget(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, Entry entry, int entryIndex,
            Proposal3OverlayCatalog.Slot slot, boolean selected, MenuRaster raster) {
        paintSurface(raster, rowSurfaceBounds(route, slot), slot.surface(), selected);
        int color = selected || slot.surface() == Proposal3OverlayCatalog.Surface.DARK
                ? MenuRaster.PAPER_TEXT : MenuRaster.INK;
        String label = label(route, entry, entryIndex);
        String detail = detail(route, entry, entryIndex);
        boolean drawsDetail = supportsDetail(route, entry) && !detail.isEmpty();
        MenuRect labelBounds = labelBounds(route, slot.bounds(), drawsDetail, entryIndex);
        drawWidgetText(raster, label, labelBounds, color, MenuRaster.HorizontalAlignment.LEFT,
                rowTextRole(route, entryIndex));
        if (drawsDetail) {
            MenuRect detailBounds = detailBounds(route, slot.bounds());
            drawWidgetText(raster, detail, detailBounds, color,
                    MenuRaster.HorizontalAlignment.RIGHT, detailTextRole(route));
        }
        paintRowIcon(route, entryIndex, slot, raster);
        if (selected && layout.marker() != null
                && slot.surface() == Proposal3OverlayCatalog.Surface.DARK) {
            paintMarker(presentation, prepared, layout, slot, raster);
        }
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
        if (layout.rows().isEmpty()) {
            return;
        }
        int start = windowStart(prepared.rows, presentation.focusedIndex(), layout.rows().size(),
                layout.scrollable());
        String focused = focusedId(presentation, prepared);
        for (int visible = 0; visible < layout.rows().size(); visible++) {
            int index = start + visible;
            Proposal3OverlayCatalog.Slot slot = layout.rows().get(visible);
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
        } else if (route == MenuRoute.SAVE_STATES) {
            // State rows use the same fixed-height treatment as the pause rail.  The row skins
            // are intentionally expanded for focus repaint cleanup, so restore the dividers
            // after every row (including an empty/focused row) to keep all four slots equal.
            for (MenuRect divider : Proposal3OverlayCatalog.SAVE_DIVIDERS) {
                raster.fill(divider, PAPER_MATTE);
            }
        }
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
        if (route == MenuRoute.LIBRARY) {
            return "OPEN ROM";
        }
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
            case AUDIO, TOUCH_CONTROLS, CONTROLLER_MAPPING,
                    OPTIONAL_DEVICES, LIBRARY, SYSTEM -> true;
            default -> false;
        };
    }

    private void drawRouteWidgets(MenuRoute route, MenuPresentation presentation, Prepared prepared,
            Proposal3OverlayCatalog.RouteLayout layout, MenuRaster raster) {
        switch (route) {
            case PAUSE_CONSOLE -> drawPause(presentation, raster);
            case SAVE_STATES -> drawSaveSide(presentation, raster);
            case AUDIO -> drawAudioSide(presentation, prepared, raster);
            case PRINTER_PAPER -> drawPrinter(presentation, raster);
            case SETTINGS, TOUCH_CONTROLS, CONTROLLER_MAPPING,
                    OPTIONAL_DEVICES, DATA_MEDIA, LIBRARY, CHOOSE_ROM, SYSTEM, ABOUT -> {
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

    private void drawAudioSide(MenuPresentation p, Prepared prepared, MenuRaster r) {
        Entry volume = prepared.volume;
        if (volume != null && (volume.adjustable || volume.progress >= 0)) {
            int progress = volume.progress >= 0 ? volume.progress : parsePercent(volume.detail);
            r.drawAudioSlider(skins().audioSliderEmpty(), skins().audioSliderFilled(),
                    skins().audioKnob(),
                    Proposal3OverlayCatalog.AUDIO_KNOB_TRAVEL,
                    Proposal3OverlayCatalog.AUDIO_KNOB, progress);
            overlayChanged(r, "VOLUME " + progress + "%", "VOLUME 75%",
                    new MenuRect(62, 405, 315, 45), true, MenuRaster.INK,
                    MenuRaster.HorizontalAlignment.CENTER);
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
            case LIBRARY -> row.x() + 20;
            case SYSTEM -> row.x() + 16;
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
            case SYSTEM -> 310;
            default -> 0;
        };
    }

    private static Proposal3GlyphAtlas.Role detailTextRole(MenuRoute route) {
        return route == MenuRoute.SYSTEM ? Proposal3GlyphAtlas.Role.SMALL
                : Proposal3GlyphAtlas.Role.MEDIUM;
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
        String candidate = display(nativeCopy(entry.detail));
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
                case "stop" -> "STOP GAME";
                default -> display(e.label);
            };
            case SAVE_STATES -> "SLOT " + Math.max(0, e.slotNumber);
            case SETTINGS -> switch (e.id) {
                case "audio" -> "AUDIO";
                case "touch-controls" -> "TOUCH CONTROLS";
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
                case "mute-audio" -> "MUTE AUDIO";
                case "emulated-audio" -> "EMULATED AUDIO";
                default -> display(e.label);
            };
            case TOUCH_CONTROLS -> switch (e.id) {
                case "haptics" -> "HAPTIC FEEDBACK";
                case "button-opacity" -> "BUTTON OPACITY";
                case "reset-touch" -> "RESET DEFAULTS";
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
                case "export-screenshot" -> "EXPORT NATIVE SCREENSHOT";
                case "preview-printer-paper" -> "PRINTER PAPER";
                default -> display(e.label);
            };
            case LIBRARY -> libraryLabel(e, index);
            case CHOOSE_ROM -> chooseLabel(e, index);
            case SYSTEM -> switch (e.id) {
                case "video-status" -> "VIDEO";
                case "profile-status" -> "SYSTEM PROFILE";
                case "rewind-save-status" -> "REWIND & SAVE";
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
            case SAVE_STATES -> "";
            case AUDIO -> e.id.equals("mute-audio") ? "OFF"
                    : e.id.equals("emulated-audio") ? "ON" : "";
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
                case "rumble", "live-camera", "game-boy-printer" -> "OFF";
                default -> "";
            };
            case LIBRARY -> index == 0 ? "TODAY" : index == 1 ? "YESTERDAY"
                    : index == 2 ? "3 DAYS AGO" : "";
            case SYSTEM -> switch (e.id) {
                case "video-status" -> "NEAREST NEIGHBOR / ASPECT FIT";
                case "profile-status" -> "SELECTED ON OPEN";
                case "rewind-save-status" -> "PORTABLE DEFAULTS";
                default -> "";
            };
            default -> "";
        };
    }

    private static String libraryLabel(Entry e, int index) {
        return switch (index) {
            case 0 -> "ADVENTURE BOY.GB";
            case 1 -> "POCKET CAMERA.GBC";
            case 2 -> "COFFEE TEST.ZIP";
            default -> display(e.label);
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

    private static String nativeCopy(String value) {
        String normalized = display(value);
        return switch (normalized) {
            case "ANDROID PICKER" -> "NATIVE PICKER";
            case "ANDROID FILE PICKER" -> "NATIVE FILE PICKER";
            case "ANDROID FILE BROWSER" -> "NATIVE FILE BROWSER";
            case "ANDROID'S NATIVE FILE BROWSER" -> "NATIVE FILE BROWSER";
            case "SAF / NO BROAD STORAGE" -> "NATIVE STORAGE / NO BROAD STORAGE";
            case "SAF / NO BROAD ACCESS" -> "NATIVE STORAGE / NO BROAD ACCESS";
            case "COFFEE GB ANDROID" -> "COFFEE GB";
            default -> normalized;
        };
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
