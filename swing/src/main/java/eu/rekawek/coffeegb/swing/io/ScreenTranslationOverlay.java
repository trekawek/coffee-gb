package eu.rekawek.coffeegb.swing.io;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable host-resolution translation overlay painted above the emulated Game Boy frame.
 *
 * <p>Region coordinates are expressed in the player-visible (rotated) source frame space
 * ({@code 0..sourceWidth}, {@code 0..sourceHeight}) so they map linearly into
 * {@link DisplayViewport} device pixels at any window size or scale mode.
 */
public final class ScreenTranslationOverlay {

    /**
     * Fraction of pixels that must differ from the captured frame before the overlay auto-dismisses.
     * A 5% threshold ignores blinking 8x8 dialogue prompt arrows (~0.27% of a 160x144 screen) and
     * small idle sprite animations while clearing immediately when dialogue advances or scrolls.
     */
    private static final double AUTO_DISMISS_DIFF_RATIO = 0.05;

    private static final Color BOX_BACKGROUND = new Color(16, 18, 24, 232);

    private static final Color BOX_BORDER = new Color(255, 255, 255, 195);

    private static final Color TEXT_COLOR = Color.WHITE;

    private final int sourceWidth;

    private final int sourceHeight;

    private final List<Region> regions;

    private final String fallbackText;

    private final int capturedRawWidth;

    private final int capturedRawHeight;

    private final int[] capturedRawRgb;

    public ScreenTranslationOverlay(
            int sourceWidth,
            int sourceHeight,
            List<Region> regions,
            String fallbackText,
            int capturedRawWidth,
            int capturedRawHeight,
            int[] capturedRawRgb) {
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            throw new IllegalArgumentException("Source dimensions must be positive");
        }
        if (capturedRawWidth <= 0 || capturedRawHeight <= 0) {
            throw new IllegalArgumentException("Captured raw dimensions must be positive");
        }
        Objects.requireNonNull(regions, "regions");
        Objects.requireNonNull(capturedRawRgb, "capturedRawRgb");
        int expectedRawLength = Math.multiplyExact(capturedRawWidth, capturedRawHeight);
        if (capturedRawRgb.length != expectedRawLength) {
            throw new IllegalArgumentException(
                    "Captured RGB length must be " + expectedRawLength);
        }
        this.sourceWidth = sourceWidth;
        this.sourceHeight = sourceHeight;
        this.regions = List.copyOf(regions);
        this.fallbackText = fallbackText == null ? "" : fallbackText.trim();
        this.capturedRawWidth = capturedRawWidth;
        this.capturedRawHeight = capturedRawHeight;
        this.capturedRawRgb = Arrays.copyOf(capturedRawRgb, capturedRawRgb.length);
    }

    public int sourceWidth() {
        return sourceWidth;
    }

    public int sourceHeight() {
        return sourceHeight;
    }

    public List<Region> regions() {
        return regions;
    }

    public String fallbackText() {
        return fallbackText;
    }

    public boolean isEmpty() {
        return regions.isEmpty() && fallbackText.isEmpty();
    }

    /**
     * Returns true when a newly published emulator frame differs enough from the translated frame
     * to automatically dismiss the overlay.
     */
    public boolean shouldDismissForFrame(int rawWidth, int rawHeight, int[] rawRgb) {
        if (rawWidth != capturedRawWidth
                || rawHeight != capturedRawHeight
                || rawRgb == null
                || rawRgb.length != capturedRawRgb.length) {
            return true;
        }
        int maxAllowedDiffs = (int) Math.floor(capturedRawRgb.length * AUTO_DISMISS_DIFF_RATIO);
        int diffs = 0;
        for (int i = 0; i < capturedRawRgb.length; i++) {
            if ((rawRgb[i] & 0x00ffffff) != (capturedRawRgb[i] & 0x00ffffff)) {
                diffs++;
                if (diffs > maxAllowedDiffs) {
                    return true;
                }
            }
        }
        return false;
    }

    public void paint(Graphics2D g, DisplayViewport viewport) {
        Objects.requireNonNull(g, "g");
        Objects.requireNonNull(viewport, "viewport");
        if (isEmpty()) {
            return;
        }
        Rectangle bounds = viewport.paintBounds();
        if (bounds.width < 16 || bounds.height < 16) {
            return;
        }

        Graphics2D overlayGraphics = (Graphics2D) g.create();
        try {
            overlayGraphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            overlayGraphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Shape previousClip = overlayGraphics.getClip();
            overlayGraphics.clip(bounds);

            if (!regions.isEmpty()) {
                double scaleX = viewport.width() / (double) sourceWidth;
                double scaleY = viewport.height() / (double) sourceHeight;
                for (Region region : regions) {
                    paintRegion(overlayGraphics, bounds, viewport.x(), viewport.y(), scaleX, scaleY, region);
                }
            } else if (!fallbackText.isEmpty()) {
                paintFallbackBanner(overlayGraphics, bounds, viewport.scale());
            }

            overlayGraphics.setClip(previousClip);
        } finally {
            overlayGraphics.dispose();
        }
    }

    private void paintRegion(
            Graphics2D g,
            Rectangle viewportBounds,
            int originX,
            int originY,
            double scaleX,
            double scaleY,
            Region region) {
        int rawX = (int) Math.round(originX + region.x() * scaleX);
        int rawY = (int) Math.round(originY + region.y() * scaleY);
        int rawW = Math.max(32, (int) Math.round(region.width() * scaleX));
        int rawH = Math.max(20, (int) Math.round(region.height() * scaleY));

        int minX = viewportBounds.x + 2;
        int minY = viewportBounds.y + 2;
        int maxRight = viewportBounds.x + viewportBounds.width - 2;
        int maxBottom = viewportBounds.y + viewportBounds.height - 2;
        if (maxRight <= minX || maxBottom <= minY) {
            return;
        }

        int boxX = clamp(rawX, minX, Math.max(minX, maxRight - 32));
        int boxY = clamp(rawY, minY, Math.max(minY, maxBottom - 20));
        int boxW = clamp(rawW, 32, maxRight - boxX);
        int boxH = clamp(rawH, 20, maxBottom - boxY);

        double localScale = Math.max(1.0, Math.min(scaleX, scaleY));
        int maxFontSize = Math.max(11, Math.min(24, (int) Math.round(6.5 * localScale)));
        int minFontSize = Math.max(9, Math.min(12, maxFontSize));
        int paddingX = Math.max(5, (int) Math.round(2.5 * localScale));
        int paddingY = Math.max(4, (int) Math.round(2.0 * localScale));

        Font chosenFont = new Font(Font.SANS_SERIF, Font.BOLD, minFontSize);
        List<String> wrappedLines = List.of(region.text());
        for (int fontSize = maxFontSize; fontSize >= minFontSize; fontSize--) {
            Font candidate = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
            FontMetrics metrics = g.getFontMetrics(candidate);
            int availableWidth = Math.max(16, boxW - 2 * paddingX);
            List<String> lines = wrapText(region.text(), metrics, availableWidth);
            int neededHeight = lines.size() * metrics.getHeight() + 2 * paddingY;
            chosenFont = candidate;
            wrappedLines = lines;
            if (neededHeight <= boxH) {
                break;
            }
        }

        g.setFont(chosenFont);
        FontMetrics metrics = g.getFontMetrics();
        int neededHeight = wrappedLines.size() * metrics.getHeight() + 2 * paddingY;
        int finalHeight = Math.max(boxH, Math.min(neededHeight, maxBottom - boxY));
        int arc = Math.max(6, (int) Math.round(3.0 * localScale));

        g.setColor(BOX_BACKGROUND);
        g.fillRoundRect(boxX, boxY, boxW, finalHeight, arc, arc);
        g.setColor(BOX_BORDER);
        g.setStroke(new BasicStroke(Math.max(1f, (float) (localScale * 0.45))));
        g.drawRoundRect(boxX, boxY, boxW, finalHeight, arc, arc);

        g.setColor(TEXT_COLOR);
        int lineHeight = metrics.getHeight();
        int totalTextHeight = wrappedLines.size() * lineHeight;
        int startY = boxY + Math.max(paddingY, (finalHeight - totalTextHeight) / 2) + metrics.getAscent();
        int maxTextBottom = boxY + finalHeight - paddingY;
        for (String line : wrappedLines) {
            if (startY - metrics.getAscent() + lineHeight > maxTextBottom + metrics.getDescent()) {
                break;
            }
            g.drawString(line, boxX + paddingX, startY);
            startY += lineHeight;
        }
    }

    private void paintFallbackBanner(Graphics2D g, Rectangle viewportBounds, double scale) {
        double localScale = Math.max(1.0, scale);
        int fontSize = Math.max(11, Math.min(20, (int) Math.round(6.0 * localScale)));
        int paddingX = Math.max(8, (int) Math.round(4.0 * localScale));
        int paddingY = Math.max(6, (int) Math.round(3.0 * localScale));
        int margin = Math.max(6, (int) Math.round(4.0 * localScale));

        int boxW = Math.max(48, viewportBounds.width - 2 * margin);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
        FontMetrics metrics = g.getFontMetrics();
        List<String> lines = wrapText(fallbackText, metrics, Math.max(24, boxW - 2 * paddingX));
        int boxH = Math.min(
                viewportBounds.height - 2 * margin,
                lines.size() * metrics.getHeight() + 2 * paddingY);
        int boxX = viewportBounds.x + margin;
        int boxY = viewportBounds.y + viewportBounds.height - boxH - margin;
        int arc = Math.max(8, (int) Math.round(4.0 * localScale));

        g.setColor(BOX_BACKGROUND);
        g.fillRoundRect(boxX, boxY, boxW, boxH, arc, arc);
        g.setColor(BOX_BORDER);
        g.setStroke(new BasicStroke(Math.max(1f, (float) (localScale * 0.5))));
        g.drawRoundRect(boxX, boxY, boxW, boxH, arc, arc);

        g.setColor(TEXT_COLOR);
        int y = boxY + paddingY + metrics.getAscent();
        for (String line : lines) {
            if (y > boxY + boxH - paddingY + metrics.getDescent()) {
                break;
            }
            g.drawString(line, boxX + paddingX, y);
            y += metrics.getHeight();
        }
    }

    static List<String> wrapText(String text, FontMetrics metrics, int maxWidth) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        String[] paragraphs = text.trim().split("\\R+");
        for (String paragraph : paragraphs) {
            String trimmed = paragraph.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String[] words = trimmed.split("\\s+");
            StringBuilder currentLine = new StringBuilder();
            for (String word : words) {
                if (currentLine.isEmpty()) {
                    if (metrics.stringWidth(word) <= maxWidth) {
                        currentLine.append(word);
                    } else {
                        splitLongWord(word, metrics, maxWidth, lines, currentLine);
                    }
                } else {
                    String candidate = currentLine + " " + word;
                    if (metrics.stringWidth(candidate) <= maxWidth) {
                        currentLine.append(' ').append(word);
                    } else {
                        lines.add(currentLine.toString());
                        currentLine.setLength(0);
                        if (metrics.stringWidth(word) <= maxWidth) {
                            currentLine.append(word);
                        } else {
                            splitLongWord(word, metrics, maxWidth, lines, currentLine);
                        }
                    }
                }
            }
            if (!currentLine.isEmpty()) {
                lines.add(currentLine.toString());
            }
        }
        return lines.isEmpty() ? List.of(text.trim()) : List.copyOf(lines);
    }

    private static void splitLongWord(
            String word,
            FontMetrics metrics,
            int maxWidth,
            List<String> lines,
            StringBuilder currentLine) {
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            String candidate = currentLine.toString() + c;
            if (!currentLine.isEmpty() && metrics.stringWidth(candidate) > maxWidth) {
                lines.add(currentLine.toString());
                currentLine.setLength(0);
            }
            currentLine.append(c);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    public record Region(
            int x,
            int y,
            int width,
            int height,
            String text,
            String originalText) {

        public Region {
            Objects.requireNonNull(text, "text");
            text = text.trim();
            if (text.isEmpty()) {
                throw new IllegalArgumentException("Translated region text must not be blank");
            }
            originalText = originalText == null ? "" : originalText.trim();
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Region dimensions must be positive");
            }
            if (x < 0 || y < 0) {
                throw new IllegalArgumentException("Region origin must not be negative");
            }
        }

        public Region(int x, int y, int width, int height, String text) {
            this(x, y, width, height, text, "");
        }
    }
}
