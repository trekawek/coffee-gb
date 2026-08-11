package eu.rekawek.coffeegb.android.menu;

/**
 * Pure column geometry for menu text.
 *
 * <p>The renderer measures and ellipsizes glyphs inside these spans. Keeping the spans here makes
 * the non-overlap contract deterministic and testable without Android graphics classes.
 */
final class MenuTextLayout {

    static final float COLUMN_GAP = 4.0f;

    private MenuTextLayout() {
    }

    @FunctionalInterface
    interface WidthMeasurer {
        float measure(String value);
    }

    static String ellipsize(String value, float maxWidth, WidthMeasurer measurer) {
        if (value == null || value.isEmpty() || maxWidth <= 0.0f) {
            return "";
        }
        if (measurer.measure(value) <= maxWidth) {
            return value;
        }
        String ellipsis = "\u2026";
        if (measurer.measure(ellipsis) > maxWidth) {
            return "";
        }
        int low = 0;
        int high = value.length();
        while (low < high) {
            int candidate = (low + high + 1) / 2;
            String fitted = value.substring(0, candidate).stripTrailing() + ellipsis;
            if (measurer.measure(fitted) <= maxWidth) {
                low = candidate;
            } else {
                high = candidate - 1;
            }
        }
        return value.substring(0, low).stripTrailing() + ellipsis;
    }

    static HeaderColumns header(float left, float right, float measuredTitleWidth,
            boolean hasContext, float actionWidth) {
        float contentLeft = left + 8.0f;
        float contentRight = right - 5.0f;
        Span action = Span.empty(contentRight);
        if (actionWidth > 0.0f) {
            float width = Math.min(Math.max(0.0f, actionWidth),
                    Math.max(0.0f, contentRight - contentLeft));
            action = new Span(contentRight - width, contentRight);
            contentRight = Math.max(contentLeft, action.left() - COLUMN_GAP);
        }

        if (!hasContext) {
            return new HeaderColumns(new Span(contentLeft, contentRight),
                    Span.empty(contentRight), action);
        }

        float available = Math.max(0.0f, contentRight - contentLeft);
        float titleBudget = Math.min(Math.max(0.0f, measuredTitleWidth),
                Math.max(0.0f, (available - COLUMN_GAP) * 0.42f));
        Span title = new Span(contentLeft, contentLeft + titleBudget);
        Span context = new Span(Math.min(contentRight, title.right() + COLUMN_GAP), contentRight);
        return new HeaderColumns(title, context, action);
    }

    static RowColumns row(float left, float right, boolean hasDetail) {
        float contentLeft = left + 17.0f;
        float contentRight = Math.max(contentLeft, right - 7.0f);
        if (!hasDetail) {
            return new RowColumns(new Span(contentLeft, contentRight),
                    Span.empty(contentRight));
        }
        float available = Math.max(0.0f, contentRight - contentLeft);
        float split = contentLeft + available * 0.54f;
        float halfGap = Math.min(COLUMN_GAP / 2.0f, available / 2.0f);
        return new RowColumns(new Span(contentLeft, Math.max(contentLeft, split - halfGap)),
                new Span(Math.min(contentRight, split + halfGap), contentRight));
    }

    record Span(float left, float right) {
        Span {
            if (!Float.isFinite(left) || !Float.isFinite(right) || right < left) {
                throw new IllegalArgumentException("Invalid text span");
            }
        }

        static Span empty(float at) {
            return new Span(at, at);
        }

        float width() {
            return right - left;
        }
    }

    record HeaderColumns(Span title, Span context, Span action) {
    }

    record RowColumns(Span label, Span detail) {
    }
}
