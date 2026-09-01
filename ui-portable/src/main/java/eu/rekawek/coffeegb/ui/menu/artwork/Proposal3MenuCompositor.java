package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.MenuWidgetType;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Portable compositor for the common Coffee GB menu template and reusable widget library.
 *
 * <p>Every route starts with the same immutable 924x736 raster.  A presentation may change only
 * its title, left picture, optional subtitle, typed option rows, and footer hints.  Geometry and
 * typography never depend on a route or item id, so Swing and Android receive the same pixels and
 * every widget remains replaceable by another widget without moving the surrounding layout.</p>
 */
public final class Proposal3MenuCompositor {

    /** One font role for every item label and item value. */
    private static final Proposal3GlyphAtlas.Role ITEM_ROLE = Proposal3GlyphAtlas.Role.MEDIUM;
    private static final Proposal3GlyphAtlas.Role FOOTER_ROLE = Proposal3GlyphAtlas.Role.MEDIUM;
    private static final MenuRect FOOTER_MOVE = new MenuRect(73, 660, 226, 56);
    private static final MenuRect FOOTER_CHOOSE = new MenuRect(458, 670, 123, 48);
    private static final MenuRect FOOTER_BACK = new MenuRect(722, 670, 94, 48);
    private static final int DISABLED_TEXT = 0xff89927b;
    private static final int DIVIDER = 0xffd4d2ad;
    private static final int DROPDOWN_FILL = 0xffd4d2ad;
    private static final int SCROLL_ARROW_SIZE = 18;
    private static final int TRAILING_CONTENT_RIGHT_INSET = 20;

    private final Object lock = new Object();
    private int[] cachedTemplatePixels;
    private Proposal3GlyphAtlas cachedAtlas;
    private Proposal3WidgetSkins cachedSkins;
    private MenuPresentation cachedPresentation;
    private MenuArgbFrame cachedFrame;

    public Proposal3MenuCompositor() {
    }

    /** Returns a detached canonical frame, or empty when the presentation is hidden. */
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

            int[] working = templatePixels().clone();
            MenuRaster raster = new MenuRaster(working);
            drawTitle(presentation, raster);
            drawPicture(presentation, raster);
            drawSubtitle(presentation, raster);
            drawOptions(presentation, raster);
            drawFooter(presentation, raster);

            MenuArgbFrame frame = MenuArgbFrame.trusted(MenuArtworkCatalog.PACKAGED_WIDTH,
                    MenuArtworkCatalog.PACKAGED_HEIGHT, raster.pixels());
            cachedPresentation = presentation;
            cachedFrame = frame;
            return Optional.of(frame);
        }
    }

    /** The only regions that any screen is allowed to customize above the shared base. */
    static List<MenuRect> dynamicMasks(MenuRoute route) {
        Objects.requireNonNull(route, "route");
        return List.of(MenuScreenTemplate.TITLE, MenuScreenTemplate.PICTURE,
                MenuScreenTemplate.SUBTITLE, MenuScreenTemplate.OPTION_LIST,
                MenuScreenTemplate.FOOTER);
    }

    /** The single font role shared by buttons, dropdowns, checkboxes, and sliders. */
    static Proposal3GlyphAtlas.Role itemTextRole() {
        return ITEM_ROLE;
    }

    /** Shared footer role; navigation hints stay legible at desktop and Android scales. */
    static Proposal3GlyphAtlas.Role footerTextRole() {
        return FOOTER_ROLE;
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

    private int[] templatePixels() {
        if (cachedTemplatePixels == null) {
            try {
                // Every route resolves the same resource. LIBRARY makes the visual provenance
                // explicit without coupling the common frame to the currently visible route.
                cachedTemplatePixels = Proposal3TemplateFrameCatalog.decode(MenuRoute.LIBRARY)
                        .copyPixels();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to decode the common menu template", e);
            }
        }
        return cachedTemplatePixels;
    }

    private Proposal3GlyphAtlas atlas() {
        if (cachedAtlas == null) {
            try {
                cachedAtlas = Proposal3GlyphAtlas.load();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to decode the common menu glyph atlas", e);
            }
        }
        return cachedAtlas;
    }

    private Proposal3WidgetSkins skins() {
        if (cachedSkins == null) {
            try {
                cachedSkins = Proposal3WidgetSkins.load();
            } catch (IOException e) {
                throw new IllegalStateException("Unable to decode the common menu widget skins", e);
            }
        }
        return cachedSkins;
    }

    private void drawTitle(MenuPresentation presentation, MenuRaster raster) {
        String title = display(presentation.context()).isEmpty()
                ? display(presentation.title()) : display(presentation.context());
        raster.drawText(atlas(), Proposal3GlyphAtlas.Role.SEMIBOLD, title,
                MenuScreenTemplate.TITLE, MenuRaster.INK, MenuRaster.HorizontalAlignment.CENTER);
    }

    private void drawPicture(MenuPresentation presentation, MenuRaster raster) {
        MenuPreview preview = presentation.preview();
        if (preview.state() == MenuPreview.State.READY) {
            raster.copyPreview(preview, MenuScreenTemplate.PICTURE, MenuRaster.INK);
            return;
        }
        if (preview.state() == MenuPreview.State.LOADING) {
            raster.drawText(atlas(), ITEM_ROLE, "LOADING", MenuScreenTemplate.PICTURE,
                    MenuRaster.INK, MenuRaster.HorizontalAlignment.CENTER);
            return;
        }
        try {
            MenuIllustrationCatalog.decode(presentation.route())
                    .ifPresent(frame -> raster.paintFrame(frame, MenuScreenTemplate.PICTURE));
        } catch (IOException e) {
            throw new IllegalStateException("Unable to decode the common menu illustration for "
                    + presentation.route(), e);
        }
    }

    private void drawSubtitle(MenuPresentation presentation, MenuRaster raster) {
        ArrayList<SubtitleLine> lines = new ArrayList<>();
        if (!display(presentation.sideHeading()).isEmpty()) {
            appendWrappedLines(lines, display(presentation.sideHeading()),
                    Proposal3GlyphAtlas.Role.MEDIUM);
        }
        for (String line : presentation.sideLines()) {
            String value = display(line);
            if (!value.isEmpty()) {
                appendWrappedLines(lines, value, Proposal3GlyphAtlas.Role.NOTICE);
            }
        }
        if (lines.isEmpty()) {
            return;
        }

        int lineHeight = 32;
        int visible = Math.min(4, lines.size());
        int totalHeight = visible * lineHeight;
        int top = MenuScreenTemplate.SUBTITLE.y()
                + Math.max(0, (MenuScreenTemplate.SUBTITLE.height() - totalHeight) / 2);
        for (int index = 0; index < visible; index++) {
            SubtitleLine line = lines.get(index);
            raster.drawText(atlas(), line.role, line.text,
                    new MenuRect(MenuScreenTemplate.SUBTITLE.x(), top + index * lineHeight,
                            MenuScreenTemplate.SUBTITLE.width(), lineHeight),
                    MenuRaster.INK, MenuRaster.HorizontalAlignment.CENTER);
        }
    }

    private void appendWrappedLines(List<SubtitleLine> target, String value,
            Proposal3GlyphAtlas.Role role) {
        String current = "";
        for (String word : value.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (current.isEmpty()
                    || atlas().measure(role, candidate)
                    <= MenuScreenTemplate.SUBTITLE.width() - 16) {
                current = candidate;
            } else {
                target.add(new SubtitleLine(current, role));
                current = word;
            }
        }
        if (!current.isEmpty()) {
            target.add(new SubtitleLine(current, role));
        }
    }

    private void drawOptions(MenuPresentation presentation, MenuRaster raster) {
        List<VisibleSlot> visible = visibleSlots(presentation.items(), presentation.focusedIndex());
        for (int slotIndex = 0; slotIndex < MenuScreenTemplate.OPTION_ROW_COUNT; slotIndex++) {
            MenuRect row = MenuScreenTemplate.optionRow(slotIndex);
            VisibleSlot slot = visible.get(slotIndex);
            if (slot.arrow != Arrow.NONE) {
                raster.paintWidget(skins().surface(Proposal3WidgetSkins.Surface.DARK), row);
                drawScrollArrow(raster, row, slot.arrow == Arrow.UP);
                continue;
            }
            if (slot.item == null) {
                raster.paintWidget(skins().surface(Proposal3WidgetSkins.Surface.DARK), row);
                continue;
            }
            boolean focused = slot.sourceIndex == presentation.focusedIndex();
            paintWidgetRow(raster, row, slot.item, focused);
        }
        for (MenuRect divider : MenuScreenTemplate.OPTION_DIVIDERS) {
            raster.fill(divider, DIVIDER);
        }
    }

    private void paintWidgetRow(MenuRaster raster, MenuRect row, MenuPresentation.Item item,
            boolean focused) {
        Proposal3WidgetSkins.Surface surface = focused
                ? Proposal3WidgetSkins.Surface.SELECTED : Proposal3WidgetSkins.Surface.DARK;
        raster.paintWidget(skins().surface(surface), row);
        int textColor = item.enabled()
                ? focused ? MenuRaster.INK : MenuRaster.PAPER_TEXT
                : DISABLED_TEXT;
        if (focused) {
            raster.drawFocusArrow(row.x() + 5, row.y() + row.height() / 2, textColor);
        }

        switch (item.widgetType()) {
            case BUTTON -> drawButton(raster, row, item, textColor);
            case DROPDOWN -> drawDropdown(raster, row, item, textColor);
            case CHECKBOX -> drawCheckbox(raster, row, item, textColor);
            case SLIDER -> drawSlider(raster, row, item, textColor);
        }
    }

    private void drawButton(MenuRaster raster, MenuRect row, MenuPresentation.Item item,
            int textColor) {
        boolean detail = !display(item.detail()).isEmpty();
        MenuRect label = detail
                ? new MenuRect(row.x() + 38, row.y(), 276, row.height())
                : new MenuRect(row.x() + 38, row.y(), row.width() - 58, row.height());
        raster.drawText(atlas(), ITEM_ROLE, item.label(), label, textColor,
                MenuRaster.HorizontalAlignment.LEFT);
        if (detail) {
            raster.drawText(atlas(), ITEM_ROLE, item.detail(),
                    new MenuRect(row.x() + 316, row.y(),
                            row.width() - 316 - TRAILING_CONTENT_RIGHT_INSET, row.height()),
                    textColor, MenuRaster.HorizontalAlignment.RIGHT);
        }
    }

    private void drawDropdown(MenuRaster raster, MenuRect row, MenuPresentation.Item item,
            int textColor) {
        String valueText = display(item.detail());
        int availableLeft = row.x() + 20;
        int availableRight = row.right() - TRAILING_CONTENT_RIGHT_INSET;
        int gap = 4;
        int fieldWidth = Math.min(262,
                Math.max(103, atlas().renderedWidth(ITEM_ROLE, valueText) + 27));
        int labelWidth = Math.max(80, availableRight - availableLeft - gap - fieldWidth);
        raster.drawText(atlas(), ITEM_ROLE, item.label(),
                new MenuRect(availableLeft, row.y(), labelWidth, row.height()), textColor,
                MenuRaster.HorizontalAlignment.LEFT);
        MenuRect field = new MenuRect(availableLeft + labelWidth + gap, row.y() + 10,
                fieldWidth, row.height() - 20);
        raster.fill(field, MenuRaster.INK);
        raster.fill(new MenuRect(field.x() + 3, field.y() + 3, field.width() - 6,
                field.height() - 6), DROPDOWN_FILL);
        raster.drawText(atlas(), ITEM_ROLE, valueText,
                new MenuRect(field.x() + 4, field.y(), field.width() - 27, field.height()),
                MenuRaster.INK, MenuRaster.HorizontalAlignment.LEFT);
        drawDownChevron(raster, field.right() - 14, field.y() + field.height() / 2,
                MenuRaster.INK);
    }

    private void drawCheckbox(MenuRaster raster, MenuRect row, MenuPresentation.Item item,
            int textColor) {
        MenuRect checkbox = new MenuRect(
                row.right() - TRAILING_CONTENT_RIGHT_INSET - 36, row.y() + 18, 36, 36);
        int labelLeft = row.x() + 38;
        raster.drawText(atlas(), ITEM_ROLE, item.label(),
                new MenuRect(labelLeft, row.y(), checkbox.x() - 16 - labelLeft, row.height()),
                textColor,
                MenuRaster.HorizontalAlignment.LEFT);
        raster.drawCheckbox(checkbox, item.checked());
    }

    private void drawSlider(MenuRaster raster, MenuRect row, MenuPresentation.Item item,
            int textColor) {
        raster.drawText(atlas(), ITEM_ROLE, item.label(),
                new MenuRect(row.x() + 38, row.y(), 148, row.height()), textColor,
                MenuRaster.HorizontalAlignment.LEFT);
        int progress = item.progress() >= 0 ? item.progress() : parsePercent(item.detail());
        raster.drawSlider(new MenuRect(row.x() + 192, row.y() + 29, 176, 14), progress);
        String value = display(item.detail()).isEmpty() ? progress + "%" : item.detail();
        raster.drawText(atlas(), ITEM_ROLE, value,
                new MenuRect(row.x() + 376, row.y(),
                        row.width() - 376 - TRAILING_CONTENT_RIGHT_INSET, row.height()), textColor,
                MenuRaster.HorizontalAlignment.RIGHT);
    }

    private void drawFooter(MenuPresentation presentation, MenuRaster raster) {
        List<String> hints = presentation.footerHints();
        String move = valueAt(hints, 0);
        String choose = stripButton(valueAt(hints, 1), "A");
        String back = stripButton(valueAt(hints, 2), "B");
        raster.drawText(atlas(), FOOTER_ROLE, move, FOOTER_MOVE, MenuRaster.INK,
                MenuRaster.HorizontalAlignment.CENTER);
        raster.drawText(atlas(), FOOTER_ROLE, choose, FOOTER_CHOOSE, MenuRaster.INK,
                MenuRaster.HorizontalAlignment.LEFT);
        raster.drawText(atlas(), FOOTER_ROLE, back, FOOTER_BACK, MenuRaster.INK,
                MenuRaster.HorizontalAlignment.LEFT);
    }

    private static List<VisibleSlot> visibleSlots(List<MenuPresentation.Item> items,
            int focusedIndex) {
        int capacity = MenuScreenTemplate.OPTION_ROW_COUNT;
        ArrayList<VisibleSlot> result = new ArrayList<>(capacity);
        if (items.size() <= capacity) {
            for (int index = 0; index < items.size(); index++) {
                result.add(VisibleSlot.item(items.get(index), index));
            }
            pad(result, capacity);
            return List.copyOf(result);
        }

        if (focusedIndex <= capacity - 2) {
            for (int index = 0; index < capacity - 1; index++) {
                result.add(VisibleSlot.item(items.get(index), index));
            }
            result.add(VisibleSlot.arrow(Arrow.DOWN));
            return List.copyOf(result);
        }

        int finalStart = items.size() - (capacity - 1);
        if (focusedIndex >= finalStart) {
            result.add(VisibleSlot.arrow(Arrow.UP));
            for (int index = finalStart; index < items.size(); index++) {
                result.add(VisibleSlot.item(items.get(index), index));
            }
            return List.copyOf(result);
        }

        int contentCount = capacity - 2;
        int start = Math.max(1, Math.min(items.size() - contentCount - 1, focusedIndex - 2));
        result.add(VisibleSlot.arrow(Arrow.UP));
        for (int index = start; index < start + contentCount; index++) {
            result.add(VisibleSlot.item(items.get(index), index));
        }
        result.add(VisibleSlot.arrow(Arrow.DOWN));
        return List.copyOf(result);
    }

    private static void pad(List<VisibleSlot> slots, int capacity) {
        while (slots.size() < capacity) {
            slots.add(VisibleSlot.empty());
        }
    }

    private static void drawScrollArrow(MenuRaster raster, MenuRect row, boolean up) {
        int centerX = row.x() + row.width() / 2;
        int centerY = row.y() + row.height() / 2;
        int color = MenuRaster.PAPER_TEXT;
        int layers = SCROLL_ARROW_SIZE / 2;
        for (int step = 0; step < layers; step++) {
            int width = layers * 2 - 1 - step * 2;
            int y = up ? centerY + layers - 1 - step * 2
                    : centerY - layers + 1 + step * 2;
            raster.fill(new MenuRect(centerX - width / 2, y, width, 2), color);
        }
    }

    private static void drawDownChevron(MenuRaster raster, int centerX, int centerY, int color) {
        for (int step = 0; step < 7; step++) {
            raster.fill(new MenuRect(centerX - 7 + step, centerY - 3 + step, 3, 3), color);
            raster.fill(new MenuRect(centerX + 7 - step, centerY - 3 + step, 3, 3), color);
        }
    }

    private static int parsePercent(String value) {
        String normalized = display(value).replace("%", "").trim();
        try {
            return Math.max(0, Math.min(100, Integer.parseInt(normalized)));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static String valueAt(List<String> values, int index) {
        return index >= 0 && index < values.size() ? display(values.get(index)) : "";
    }

    private static String stripButton(String hint, String button) {
        String value = display(hint);
        String prefix = button + " ";
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static String display(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
    }

    private enum Arrow {
        NONE,
        UP,
        DOWN
    }

    private static final class SubtitleLine {
        private final String text;
        private final Proposal3GlyphAtlas.Role role;

        private SubtitleLine(String text, Proposal3GlyphAtlas.Role role) {
            this.text = text;
            this.role = role;
        }
    }

    private static final class VisibleSlot {
        private final MenuPresentation.Item item;
        private final int sourceIndex;
        private final Arrow arrow;

        private VisibleSlot(MenuPresentation.Item item, int sourceIndex, Arrow arrow) {
            this.item = item;
            this.sourceIndex = sourceIndex;
            this.arrow = arrow;
        }

        private static VisibleSlot item(MenuPresentation.Item item, int sourceIndex) {
            return new VisibleSlot(item, sourceIndex, Arrow.NONE);
        }

        private static VisibleSlot arrow(Arrow arrow) {
            return new VisibleSlot(null, -1, arrow);
        }

        private static VisibleSlot empty() {
            return new VisibleSlot(null, -1, Arrow.NONE);
        }
    }
}
