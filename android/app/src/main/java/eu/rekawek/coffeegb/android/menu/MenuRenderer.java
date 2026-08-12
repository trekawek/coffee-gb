package eu.rekawek.coffeegb.android.menu;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;

import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.util.Locale;
import java.util.List;

/** Canvas renderer for the Proposal 3 in-screen menu. */
public final class MenuRenderer {

    private static final int CREAM = Color.rgb(239, 240, 211);
    private static final int MINT = Color.rgb(211, 226, 181);
    private static final int MINT_DARK = Color.rgb(177, 194, 143);
    private static final int OLIVE = Color.rgb(38, 52, 35);
    private static final int OLIVE_LIGHT = Color.rgb(65, 79, 49);
    private static final int INK = Color.rgb(29, 42, 28);
    private static final int OXBLOOD = Color.rgb(119, 38, 43);
    private static final int MUTED = Color.rgb(117, 127, 87);

    private final Paint fill = new Paint();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private MenuPreview cachedPreview;
    private Bitmap cachedPreviewBitmap;

    public MenuRenderer() {
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(1.0f);
        text.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        text.setFakeBoldText(true);
        text.setSubpixelText(true);
        text.setLinearText(false);
    }

    /** Draws one immutable menu snapshot into the supplied RasterSkin display bounds. */
    public void draw(Canvas canvas, MenuPresentation presentation, RectF displayBounds) {
        if (canvas == null || presentation == null || !presentation.visible()
                || displayBounds == null || displayBounds.width() <= 0.0f
                || displayBounds.height() <= 0.0f) {
            return;
        }
        MenuGeometry.Layout layout = MenuGeometry.forDisplay(
                displayBounds.width(), displayBounds.height());
        if (layout.scale() <= 0.0f) {
            return;
        }

        int save = canvas.save();
        try {
            canvas.clipRect(displayBounds);
            canvas.translate(displayBounds.left + layout.offsetX(),
                    displayBounds.top + layout.offsetY());
            canvas.scale(layout.scale(), layout.scale());
            fill.setColor(CREAM);
            canvas.drawRect(0, 0, layout.logicalWidth(), layout.logicalHeight(), fill);
            if (layout.portrait()) {
                drawPortrait(canvas, presentation, layout.logicalWidth(), layout.logicalHeight());
            } else {
                drawLandscape(canvas, presentation, layout.logicalWidth(), layout.logicalHeight());
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawLandscape(Canvas canvas, MenuPresentation presentation, int width, int height) {
        RectF header = new RectF(5, 5, width - 5, 34);
        RectF side = new RectF(8, 42, 120, height - 16);
        RectF list = new RectF(123, 42, width - 8, height - 16);
        RectF footer = new RectF(5, height - 13, width - 5, height - 4);
        drawHeader(canvas, presentation, header);
        drawSide(canvas, presentation, side, false);
        drawList(canvas, presentation, list);
        drawFooter(canvas, presentation, footer);
    }

    private void drawPortrait(Canvas canvas, MenuPresentation presentation, int width, int height) {
        RectF header = new RectF(5, 5, width - 5, 34);
        RectF side = new RectF(8, 40, width - 8, 127);
        RectF list = new RectF(8, 131, width - 8, height - 25);
        RectF footer = new RectF(5, height - 20, width - 5, height - 5);
        drawHeader(canvas, presentation, header);
        drawSide(canvas, presentation, side, true);
        drawList(canvas, presentation, list);
        drawFooter(canvas, presentation, footer);
    }

    private void drawHeader(Canvas canvas, MenuPresentation presentation, RectF bounds) {
        drawPanel(canvas, bounds, CREAM, INK);
        String title = upper(presentation.title());
        String context = upper(presentation.context());
        String action = upper(presentation.headerAction());
        text.setTextSize(12.0f);
        float buttonWidth = action.isEmpty() ? 0.0f
                : Math.min(68.0f, Math.max(46.0f, action.length() * 5.2f));
        MenuTextLayout.HeaderColumns columns = MenuTextLayout.header(bounds.left, bounds.right,
                text.measureText(title), !context.isEmpty(), buttonWidth);
        drawFittedText(canvas, title, columns.title(), bounds.top, bounds.bottom,
                bounds.centerY() + 4, 12, INK, Paint.Align.LEFT);
        if (!context.isEmpty()) {
            drawFittedText(canvas, context, columns.context(), bounds.top, bounds.bottom,
                    bounds.centerY() + 3, 8, OLIVE_LIGHT, Paint.Align.LEFT);
        }
        if (!action.isEmpty()) {
            MenuTextLayout.Span actionSpan = columns.action();
            RectF button = new RectF(actionSpan.left(), bounds.top + 5,
                    actionSpan.right(), bounds.bottom - 5);
            drawPanel(canvas, button, MINT, INK);
            drawFittedText(canvas, action,
                    new MenuTextLayout.Span(button.left + 3, button.right - 3),
                    button.top, button.bottom, button.centerY() + 3,
                    6.5f, INK, Paint.Align.CENTER);
        }
    }

    private void drawSide(Canvas canvas, MenuPresentation presentation, RectF bounds,
            boolean portrait) {
        drawPanel(canvas, bounds, MINT, INK);
        drawFittedText(canvas, upper(presentation.sideHeading()),
                new MenuTextLayout.Span(bounds.left + 6, bounds.right - 6),
                bounds.top + 2, bounds.top + 18, bounds.top + 14,
                portrait ? 6.5f : 7.5f, INK, Paint.Align.LEFT);

        float thumbnailTop = bounds.top + 19;
        float thumbnailHeight = portrait ? 28 : 61;
        float thumbnailBottom = Math.min(bounds.bottom - 45, thumbnailTop + thumbnailHeight);
        drawThumbnail(canvas, new RectF(bounds.left + 5, thumbnailTop, bounds.right - 5, thumbnailBottom));

        ListTextLayout sideText = new ListTextLayout(bounds.left + 6, thumbnailBottom + 14,
                bounds.right - 6, bounds.bottom - 6, presentation.sideLines().size());
        float size = portrait ? 6.0f : 7.5f;
        for (String line : presentation.sideLines()) {
            if (sideText.nextBaseline() > sideText.bottom()) {
                break;
            }
            drawFittedText(canvas, upper(line),
                    new MenuTextLayout.Span(sideText.left(), bounds.right - 6),
                    Math.max(bounds.top, sideText.nextBaseline() - size),
                    Math.min(bounds.bottom, sideText.nextBaseline() + 2),
                    sideText.nextBaseline(), size, OLIVE, Paint.Align.LEFT);
            sideText.advance();
        }
    }

    void drawThumbnail(Canvas canvas, RectF bounds) {
        MenuGeometry.ThumbnailGrid grid = MenuGeometry.thumbnailGrid(
                bounds.width(), bounds.height());
        if (!grid.valid()) {
            return;
        }
        int save = canvas.save();
        try {
            // Thumbnail art is decorative and must never escape into side text or menu rows.
            canvas.clipRect(bounds);
            fill.setColor(OLIVE_LIGHT);
            canvas.drawRect(bounds, fill);
            stroke.setColor(INK);
            stroke.setStrokeWidth(1.0f);
            canvas.drawRect(bounds, stroke);

            fill.setColor(MINT_DARK);
            for (int index = 0; index < 13; index++) {
                float column = index * 7 % 31;
                float row = index * 11 % 19;
                drawThumbnailRect(canvas, bounds, grid, column, row, column + 3, row + 2);
            }
            fill.setColor(MINT);
            drawThumbnailRect(canvas, bounds, grid, 3, 13, 13, 17);
            drawThumbnailRect(canvas, bounds, grid, 23, 7, 31, 17);
            fill.setColor(CREAM);
            float characterColumn = 36.0f * 0.28f;
            drawThumbnailRect(canvas, bounds, grid, characterColumn, 12,
                    characterColumn + 4, 17);
            drawThumbnailRect(canvas, bounds, grid, characterColumn + 1, 9,
                    characterColumn + 3, 12);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawThumbnailRect(Canvas canvas, RectF bounds,
            MenuGeometry.ThumbnailGrid grid, float left, float top, float right, float bottom) {
        canvas.drawRect(bounds.left + grid.x(left), bounds.top + grid.y(top),
                bounds.left + grid.x(right), bounds.top + grid.y(bottom), fill);
    }

    private void drawList(Canvas canvas, MenuPresentation presentation, RectF bounds) {
        drawPanel(canvas, bounds, OLIVE, MINT_DARK);
        List<MenuPresentation.Item> items = presentation.items();
        if (items.isEmpty()) {
            return;
        }
        if (presentation.route() == MenuRoute.PRINTER_PAPER) {
            float rowsHeight = Math.min(bounds.height() * .34f,
                    Math.max(32.0f, items.size() * 23.0f));
            RectF previewBounds = new RectF(
                    bounds.left + 4, bounds.top + 4, bounds.right - 4,
                    Math.max(bounds.top + 8, bounds.bottom - rowsHeight - 3));
            drawPreview(canvas, presentation.preview(), previewBounds);
            drawRows(canvas, presentation, new RectF(
                    bounds.left, previewBounds.bottom + 3, bounds.right, bounds.bottom));
            return;
        }
        drawRows(canvas, presentation, bounds);
    }

    private void drawRows(Canvas canvas, MenuPresentation presentation, RectF bounds) {
        List<MenuPresentation.Item> items = presentation.items();
        float rowHeight = bounds.height() / items.size();
        float textSize = Math.max(5.5f, Math.min(10.0f, rowHeight * 0.43f));
        for (int index = 0; index < items.size(); index++) {
            float top = bounds.top + rowHeight * index;
            float bottom = index == items.size() - 1 ? bounds.bottom : top + rowHeight;
            MenuPresentation.Item item = items.get(index);
            if (index == presentation.focusedIndex()) {
                fill.setColor(OXBLOOD);
                canvas.drawRect(bounds.left + 1, top + 1, bounds.right - 1, bottom, fill);
            }
            stroke.setColor(MINT_DARK);
            stroke.setStrokeWidth(0.75f);
            canvas.drawLine(bounds.left + 1, bottom, bounds.right - 1, bottom, stroke);
            if (index == presentation.focusedIndex()) {
                drawFocusArrow(canvas, bounds.left + 7, (top + bottom) / 2.0f);
            }
            int color = item.enabled() ? CREAM : MUTED;
            MenuTextLayout.RowColumns columns = MenuTextLayout.row(bounds.left, bounds.right,
                    !item.detail().isEmpty());
            float baseline = (top + bottom) / 2.0f + textSize * 0.34f;
            drawFittedText(canvas, upper(item.label()), columns.label(), top, bottom,
                    baseline, textSize, color, Paint.Align.LEFT);
            if (!item.detail().isEmpty()) {
                drawFittedText(canvas, upper(item.detail()), columns.detail(), top, bottom,
                        baseline, Math.max(5.0f, textSize - 1),
                        item.enabled() ? MINT_DARK : MUTED, Paint.Align.RIGHT);
            }
            if (item.progress() >= 0) {
                float left = bounds.left + 17;
                float right = bounds.right - 7;
                float barBottom = bottom - 3;
                fill.setColor(MUTED);
                canvas.drawRect(left, barBottom - 2, right, barBottom, fill);
                fill.setColor(item.enabled() ? MINT : MUTED);
                canvas.drawRect(left, barBottom - 2,
                        left + (right - left) * item.progress() / 100.0f, barBottom, fill);
            }
        }
    }

    void drawPreview(Canvas canvas, MenuPreview preview, RectF bounds) {
        int save = canvas.save();
        try {
            canvas.clipRect(bounds);
            fill.setColor(CREAM);
            canvas.drawRect(bounds, fill);
            stroke.setColor(MINT_DARK);
            canvas.drawRect(bounds, stroke);
            if (preview.state() == MenuPreview.State.LOADING) {
                drawFittedText(canvas, "LOADING PAPER...",
                        new MenuTextLayout.Span(bounds.left + 3, bounds.right - 3),
                        bounds.top, bounds.bottom, bounds.centerY() + 3,
                        7, INK, Paint.Align.CENTER);
                return;
            }
            if (preview.state() == MenuPreview.State.EMPTY) {
                drawFittedText(canvas, "NOTHING PRINTED",
                        new MenuTextLayout.Span(bounds.left + 3, bounds.right - 3),
                        bounds.top, bounds.bottom, bounds.centerY() + 3,
                        7, INK, Paint.Align.CENTER);
                return;
            }
            Bitmap bitmap = bitmapFor(preview);
            float scale = Math.min(bounds.width() / bitmap.getWidth(),
                    bounds.height() / bitmap.getHeight());
            float width = bitmap.getWidth() * scale;
            float height = bitmap.getHeight() * scale;
            RectF destination = new RectF(bounds.centerX() - width / 2.0f,
                    bounds.centerY() - height / 2.0f, bounds.centerX() + width / 2.0f,
                    bounds.centerY() + height / 2.0f);
            Paint pixels = new Paint();
            pixels.setFilterBitmap(false);
            canvas.drawBitmap(bitmap, null, destination, pixels);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private Bitmap bitmapFor(MenuPreview preview) {
        if (cachedPreview != preview || cachedPreviewBitmap == null
                || cachedPreviewBitmap.isRecycled()) {
            if (cachedPreviewBitmap != null && !cachedPreviewBitmap.isRecycled()) {
                cachedPreviewBitmap.recycle();
            }
            cachedPreview = preview;
            cachedPreviewBitmap = Bitmap.createBitmap(preview.copyPixels(), preview.width(),
                    preview.height(), Bitmap.Config.ARGB_8888);
        }
        return cachedPreviewBitmap;
    }

    private void drawFocusArrow(Canvas canvas, float x, float centerY) {
        fill.setColor(CREAM);
        canvas.drawPath(triangle(x - 3, centerY - 4, x - 3, centerY + 4, x + 2, centerY), fill);
    }

    private android.graphics.Path triangle(float x1, float y1, float x2, float y2,
            float x3, float y3) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        path.lineTo(x3, y3);
        path.close();
        return path;
    }

    private void drawFooter(Canvas canvas, MenuPresentation presentation, RectF bounds) {
        drawPanel(canvas, bounds, CREAM, INK);
        List<String> hints = presentation.footerHints();
        if (hints.isEmpty()) {
            return;
        }
        float segment = bounds.width() / hints.size();
        for (int index = 0; index < hints.size(); index++) {
            float left = bounds.left + segment * index;
            float right = index == hints.size() - 1 ? bounds.right : left + segment;
            if (index > 0) {
                stroke.setColor(MINT_DARK);
                stroke.setStrokeWidth(0.75f);
                canvas.drawLine(left, bounds.top + 2, left, bounds.bottom - 2, stroke);
            }
            drawFittedText(canvas, upper(hints.get(index)),
                    new MenuTextLayout.Span(left + 2, Math.max(left + 2, right - 2)),
                    bounds.top, bounds.bottom, bounds.centerY() + 2.3f,
                    bounds.height() < 12 ? 4.5f : 5.5f, INK, Paint.Align.CENTER);
        }
    }

    private void drawPanel(Canvas canvas, RectF bounds, int background, int border) {
        fill.setColor(background);
        canvas.drawRect(bounds, fill);
        stroke.setColor(border);
        stroke.setStrokeWidth(1.0f);
        canvas.drawRect(bounds, stroke);
        if (bounds.width() > 12 && bounds.height() > 12) {
            stroke.setStrokeWidth(0.75f);
            canvas.drawRect(bounds.left + 2, bounds.top + 2, bounds.right - 2, bounds.bottom - 2, stroke);
        }
    }

    private void drawText(Canvas canvas, String value, float x, float baseline, float size, int color,
            Paint.Align align) {
        text.setColor(color);
        text.setTextSize(size);
        text.setTextAlign(align);
        canvas.drawText(value, x, baseline, text);
    }

    private void drawFittedText(Canvas canvas, String value, MenuTextLayout.Span span,
            float clipTop, float clipBottom, float baseline, float size, int color,
            Paint.Align align) {
        if (value.isEmpty() || span.width() <= 0.0f || clipBottom <= clipTop) {
            return;
        }
        text.setTextSize(size);
        String fitted = fitText(value, span.width());
        if (fitted.isEmpty()) {
            return;
        }
        float x = switch (align) {
            case LEFT -> span.left();
            case CENTER -> (span.left() + span.right()) / 2.0f;
            case RIGHT -> span.right();
        };
        int save = canvas.save();
        try {
            canvas.clipRect(span.left(), clipTop, span.right(), clipBottom);
            drawText(canvas, fitted, x, baseline, size, color, align);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private String fitText(String value, float maxWidth) {
        return MenuTextLayout.ellipsize(value, maxWidth, text::measureText);
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.US);
    }

    private static final class ListTextLayout {

        private final float left;
        private final float bottom;
        private final float step;
        private float baseline;

        private ListTextLayout(float left, float top, float right, float bottom, int count) {
            this.left = left;
            this.bottom = bottom;
            this.step = count == 0 ? 0.0f : Math.max(8.0f, (bottom - top) / count);
            this.baseline = top;
        }

        private float left() {
            return left;
        }

        private float bottom() {
            return bottom;
        }

        private float nextBaseline() {
            return baseline;
        }

        private void advance() {
            baseline += step;
        }
    }
}
