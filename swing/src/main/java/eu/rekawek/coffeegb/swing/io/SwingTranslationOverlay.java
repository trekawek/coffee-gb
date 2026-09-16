package eu.rekawek.coffeegb.swing.io;

import eu.rekawek.coffeegb.swing.translation.TranslationRegion;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A detached screenshot with immutable, source-coordinate translation layouts. */
final class SwingTranslationOverlay {

    private static final Color BOX_BACKGROUND = new Color(24, 27, 32);

    private static final FontRenderContext FONT_CONTEXT =
            new FontRenderContext(null, true, true);

    private final BufferedImage frame;

    private final List<Box> boxes;

    private final String status;

    SwingTranslationOverlay(BufferedImage source, List<TranslationRegion> regions, String status) {
        Objects.requireNonNull(source, "frame");
        Objects.requireNonNull(regions, "regions");
        frame = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = frame.createGraphics();
        try {
            graphics.drawImage(source, 0, 0, null);
        } finally {
            graphics.dispose();
        }
        List<Box> layouts = new ArrayList<>();
        for (TranslationRegion region : regions) {
            Objects.requireNonNull(region, "region");
            Rectangle bounds = clip(region, frame.getWidth(), frame.getHeight());
            if (bounds.isEmpty() || region.text().isBlank()) {
                continue;
            }
            float padding = Math.min(1.5f, Math.min(bounds.width, bounds.height) / 8f);
            float availableWidth = Math.max(0.5f, bounds.width - 2 * padding);
            float availableHeight = Math.max(0.5f, bounds.height - 2 * padding);
            layouts.add(new Box(bounds, padding,
                    fitText(region.text(), availableWidth, availableHeight)));
        }
        boxes = List.copyOf(layouts);
        this.status = status;
    }

    int width() {
        return frame.getWidth();
    }

    int height() {
        return frame.getHeight();
    }

    /** Reserve a compact host-space strip so progress never covers translated game text. */
    int statusHeight(int componentHeight) {
        return status == null || status.isBlank() || componentHeight < 44 ? 0 : 22;
    }

    /** Paints in the same unrotated source coordinates as the screenshot sent to the API. */
    void paint(Graphics2D graphics) {
        graphics.drawImage(frame, 0, 0, null);
        for (Box box : boxes) {
            Graphics2D copy = (Graphics2D) graphics.create();
            try {
                Rectangle bounds = box.bounds();
                copy.clip(bounds);
                copy.setColor(BOX_BACKGROUND);
                copy.fill(bounds);
                copy.setColor(Color.WHITE);
                copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                copy.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                        RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                float y = bounds.y + box.padding();
                for (TextLayout line : box.lines()) {
                    y += line.getAscent();
                    line.draw(copy, bounds.x + box.padding(), y);
                    y += line.getDescent() + line.getLeading();
                }
            } finally {
                copy.dispose();
            }
        }
    }

    void paintStatus(Graphics2D graphics, int componentWidth, int componentHeight) {
        int stripHeight = statusHeight(componentHeight);
        if (stripHeight == 0 || componentWidth <= 0) {
            return;
        }
        Graphics2D copy = (Graphics2D) graphics.create();
        try {
            int y = componentHeight - stripHeight;
            copy.clipRect(0, y, componentWidth, stripHeight);
            copy.setColor(BOX_BACKGROUND);
            copy.fillRect(0, y, componentWidth, stripHeight);
            copy.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            copy.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            FontMetrics metrics = copy.getFontMetrics();
            String text = status;
            int availableWidth = Math.max(0, componentWidth - 12);
            if (metrics.stringWidth(text) > availableWidth) {
                int end = text.length();
                while (end > 0 && metrics.stringWidth(text.substring(0, end) + "…") > availableWidth) {
                    end = text.offsetByCodePoints(end, -1);
                }
                text = text.substring(0, end) + "…";
            }
            copy.setColor(Color.WHITE);
            copy.drawString(text, 6,
                    y + (stripHeight - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally {
            copy.dispose();
        }
    }

    private static Rectangle clip(TranslationRegion region, int width, int height) {
        int left = Math.max(0, Math.min(width, region.x()));
        int top = Math.max(0, Math.min(height, region.y()));
        int right = (int) Math.max(0, Math.min(width, (long) region.x() + region.width()));
        int bottom = (int) Math.max(0, Math.min(height, (long) region.y() + region.height()));
        return new Rectangle(left, top, Math.max(0, right - left), Math.max(0, bottom - top));
    }

    private static List<TextLayout> fitText(String text, float width, float height) {
        String normalized = text.replaceAll("\\s+", " ").trim();
        List<TextLayout> lines = List.of();
        Font font = null;
        for (float size = 9; size >= 4; size -= 0.5f) {
            font = new Font(Font.SANS_SERIF, Font.PLAIN, 1).deriveFont(size);
            lines = wrap(normalized, font, width);
            float requiredHeight = 0;
            for (TextLayout line : lines) {
                requiredHeight += line.getAscent() + line.getDescent() + line.getLeading();
            }
            if (requiredHeight <= height) {
                return lines;
            }
        }

        // Very small or overfull model boxes still remain confined to their detected region.
        // Keep a legible minimum size and indicate overflow instead of painting over gameplay.
        float lineHeight = font.getLineMetrics(normalized, FONT_CONTEXT).getHeight();
        int visibleLines = Math.max(1, (int) (height / lineHeight));
        if (visibleLines >= lines.size()) {
            return lines;
        }
        List<TextLayout> clipped = new ArrayList<>(lines.subList(0, visibleLines));
        int start = 0;
        for (int i = 0; i < visibleLines - 1; i++) {
            start += lines.get(i).getCharacterCount();
        }
        String last = normalized.substring(start).stripLeading();
        int end = Math.min(last.length(), lines.get(visibleLines - 1).getCharacterCount());
        while (end > 0 && new TextLayout(last.substring(0, end) + "…", font, FONT_CONTEXT)
                .getAdvance() > width) {
            end = last.offsetByCodePoints(end, -1);
        }
        clipped.set(visibleLines - 1,
                new TextLayout(last.substring(0, end) + "…", font, FONT_CONTEXT));
        return List.copyOf(clipped);
    }

    private static List<TextLayout> wrap(String text, Font font, float width) {
        AttributedString attributed = new AttributedString(text);
        attributed.addAttribute(TextAttribute.FONT, font);
        LineBreakMeasurer measurer = new LineBreakMeasurer(attributed.getIterator(), FONT_CONTEXT);
        List<TextLayout> lines = new ArrayList<>();
        while (measurer.getPosition() < text.length()) {
            lines.add(measurer.nextLayout(width));
        }
        return List.copyOf(lines);
    }

    private record Box(Rectangle bounds, float padding, List<TextLayout> lines) {
    }
}
