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
import java.util.Comparator;
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
        if (!regions.isEmpty() && rawWidth == sourceWidth && rawHeight == sourceHeight) {
            int capturedTransCount = 0;
            int transDiffs = 0;
            for (Region r : regions) {
                int rx1 = Math.max(1, r.x());
                int rx2 = Math.min(rawWidth - 1, r.x() + r.width() - 1);
                int ry1 = Math.max(0, r.y());
                int ry2 = Math.min(rawHeight - 1, r.y() + r.height() - 1);
                for (int y = ry1; y <= ry2; y++) {
                    int row = y * rawWidth;
                    for (int x = rx1; x <= rx2; x++) {
                        boolean wasTrans =
                                (capturedRawRgb[row + x] & 0x00ffffff)
                                        != (capturedRawRgb[row + x - 1] & 0x00ffffff);
                        boolean isTrans =
                                (rawRgb[row + x] & 0x00ffffff)
                                        != (rawRgb[row + x - 1] & 0x00ffffff);
                        if (wasTrans) {
                            capturedTransCount++;
                        }
                        if (wasTrans != isTrans) {
                            transDiffs++;
                        }
                    }
                }
            }
            if (capturedTransCount >= 16) {
                return transDiffs > Math.floor(capturedTransCount * 0.45);
            }
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
                List<PreparedRegion> prepared =
                        prepareRegions(
                                overlayGraphics,
                                bounds,
                                viewport.x(),
                                viewport.y(),
                                scaleX,
                                scaleY);
                for (PreparedRegion item : prepared) {
                    paintPreparedRegion(overlayGraphics, item);
                }
            } else if (!fallbackText.isEmpty()) {
                paintFallbackBanner(overlayGraphics, bounds, viewport.scale());
            }

            overlayGraphics.setClip(previousClip);
        } finally {
            overlayGraphics.dispose();
        }
    }

    private List<PreparedRegion> prepareRegions(
            Graphics2D g,
            Rectangle viewportBounds,
            int originX,
            int originY,
            double scaleX,
            double scaleY) {
        int minX = viewportBounds.x + 2;
        int minY = viewportBounds.y + 2;
        int maxRight = viewportBounds.x + viewportBounds.width - 2;
        int maxBottom = viewportBounds.y + viewportBounds.height - 2;
        if (maxRight <= minX || maxBottom <= minY) {
            return List.of();
        }

        List<Region> sorted = new ArrayList<>(regions);
        sorted.sort(Comparator.comparingInt(Region::y).thenComparingInt(Region::x));

        double localScale = Math.max(1.0, Math.min(scaleX, scaleY));
        int maxFontSize = Math.max(11, Math.min(22, (int) Math.round(5.8 * localScale)));
        int minFontSize = Math.max(9, Math.min(11, maxFontSize));
        int paddingX = Math.max(5, (int) Math.round(2.5 * localScale));
        int paddingY = Math.max(2, (int) Math.round(1.2 * localScale));
        int minBoxHeight = Math.max(14, (int) Math.round(7.5 * scaleY));
        int verticalGap = Math.max(2, (int) Math.round(scaleY));
        int horizontalGap = Math.max(4, (int) Math.round(2.5 * scaleX));

        List<PreparedRegion> list = new ArrayList<>(sorted.size());
        for (int i = 0; i < sorted.size(); i++) {
            Region region = sorted.get(i);
            int rawX = (int) Math.round(originX + region.x() * scaleX);
            int rawY = (int) Math.round(originY + region.y() * scaleY);
            int rawW = Math.max(32, (int) Math.round(region.width() * scaleX));
            int rawH = Math.max(minBoxHeight, (int) Math.round(region.height() * scaleY));

            int boxX = clamp(rawX, minX, Math.max(minX, maxRight - 32));
            int boxY = clamp(rawY, minY, Math.max(minY, maxBottom - minBoxHeight));

            int limitBottom = maxBottom;
            for (int j = i + 1; j < sorted.size(); j++) {
                Region next = sorted.get(j);
                if (next.y() <= region.y()) {
                    continue;
                }
                boolean horizontalOverlap =
                        next.x() < region.x() + region.width() + 8
                                && region.x() < next.x() + next.width() + 8;
                if (horizontalOverlap) {
                    int nextTopY = (int) Math.round(originY + next.y() * scaleY) - verticalGap;
                    limitBottom = Math.min(limitBottom, Math.max(boxY + minBoxHeight, nextTopY));
                    break;
                }
            }

            int limitRight = maxRight;
            for (int j = 0; j < sorted.size(); j++) {
                if (j == i) {
                    continue;
                }
                Region other = sorted.get(j);
                if (other.x() < region.x() + region.width() - 4) {
                    continue;
                }
                boolean verticalOverlap =
                        other.y() < region.y() + region.height() + 4
                                && region.y() < other.y() + other.height() + 4;
                if (verticalOverlap) {
                    int otherLeftX =
                            (int) Math.round(originX + other.x() * scaleX) - horizontalGap;
                    limitRight = Math.min(limitRight, Math.max(boxX + 32, otherLeftX));
                }
            }

            int maxAllowedWidth = Math.max(32, limitRight - minX);
            int boxW = clamp(rawW, 32, Math.max(32, limitRight - boxX));
            int boxH = clamp(rawH, minBoxHeight, Math.max(minBoxHeight, limitBottom - boxY));
            boolean singleLinePreferred =
                    (region.height() <= 16 || limitBottom < maxBottom)
                            && !region.text().contains("\n");

            Font chosenFont = new Font(Font.SANS_SERIF, Font.BOLD, minFontSize);
            List<String> wrappedLines = List.of(region.text());
            for (int fontSize = maxFontSize; fontSize >= minFontSize; fontSize--) {
                Font candidate = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
                FontMetrics metrics = g.getFontMetrics(candidate);
                if (singleLinePreferred) {
                    int singleLineWidth = metrics.stringWidth(region.text()) + 2 * paddingX;
                    int singleLineHeight = metrics.getHeight() + 2 * paddingY;
                    chosenFont = candidate;
                    wrappedLines = List.of(region.text());
                    if (singleLineWidth <= maxAllowedWidth
                            && singleLineHeight <= Math.max(boxH, limitBottom - boxY)) {
                        boxW = Math.max(boxW, Math.min(maxAllowedWidth, singleLineWidth));
                        break;
                    }
                } else {
                    int availableWidth = Math.max(16, boxW - 2 * paddingX);
                    List<String> lines = wrapText(region.text(), metrics, availableWidth);
                    int neededHeight = lines.size() * metrics.getHeight() + 2 * paddingY;
                    chosenFont = candidate;
                    wrappedLines = lines;
                    if (neededHeight <= boxH) {
                        break;
                    }
                }
            }

            FontMetrics metrics = g.getFontMetrics(chosenFont);
            if (singleLinePreferred) {
                int neededW =
                        Math.min(
                                maxAllowedWidth,
                                metrics.stringWidth(region.text()) + 2 * paddingX);
                boxW = Math.max(boxW, neededW);
                if (boxX + boxW > limitRight) {
                    boxX = Math.max(minX, limitRight - boxW);
                }
            } else {
                wrappedLines = wrapText(region.text(), metrics, Math.max(16, boxW - 2 * paddingX));
            }

            boolean isCenteredBanner =
                    region.width() >= sourceWidth * 0.65
                            && region.height() >= 20
                            && wrappedLines.size() == 1
                            && Math.abs(
                                            (region.x() + region.width() / 2.0)
                                                    - (sourceWidth / 2.0))
                                    <= sourceWidth * 0.15;
            boolean isGraphicalButton =
                    !isCenteredBanner
                            && region.height() >= 20
                            && wrappedLines.size() <= 2;
            int finalHeight;
            boolean centerText = false;
            if (isCenteredBanner) {
                int pillW =
                        clamp(
                                metrics.stringWidth(wrappedLines.get(0))
                                        + 2 * paddingX
                                        + (int) Math.round(10 * localScale),
                                48,
                                boxW);
                int pillH =
                        clamp(
                                metrics.getHeight()
                                        + 2 * paddingY
                                        + (int) Math.round(2 * localScale),
                                minBoxHeight,
                                boxH);
                boxX = boxX + (boxW - pillW) / 2;
                boxY = clamp(boxY + (boxH - pillH) / 2, minY, Math.max(minY, limitBottom - pillH));
                boxW = pillW;
                finalHeight = pillH;
                centerText = true;
            } else if (isGraphicalButton) {
                int pillH =
                        clamp(
                                wrappedLines.size() * metrics.getHeight()
                                        + 2 * paddingY
                                        + (int) Math.round(3 * localScale),
                                minBoxHeight,
                                boxH);
                boxY = clamp(boxY + (boxH - pillH) / 2, minY, Math.max(minY, limitBottom - pillH));
                finalHeight = pillH;
                centerText = true;
            } else {
                int neededHeight = wrappedLines.size() * metrics.getHeight() + 2 * paddingY;
                finalHeight =
                        clamp(
                                Math.max(boxH, neededHeight),
                                minBoxHeight,
                                Math.max(minBoxHeight, limitBottom - boxY));
            }

            list.add(
                    new PreparedRegion(
                            region,
                            boxX,
                            boxY,
                            boxW,
                            finalHeight,
                            limitRight,
                            chosenFont,
                            wrappedLines,
                            paddingX,
                            paddingY,
                            localScale,
                            centerText));
        }

        // Unify peer menu items in the same vertical column stack so their left/right edges and
        // font size match, even when another UI column has regions at overlapping Y positions.
        boolean[] visited = new boolean[list.size()];
        for (int start = 0; start < list.size(); start++) {
            if (visited[start]) {
                continue;
            }
            PreparedRegion first = list.get(start);
            visited[start] = true;
            if (first.region.height() > 36) {
                continue;
            }
            List<Integer> group = new ArrayList<>();
            group.add(start);
            for (int cand = start + 1; cand < list.size(); cand++) {
                if (visited[cand]) {
                    continue;
                }
                PreparedRegion prev = list.get(group.get(group.size() - 1));
                PreparedRegion curr = list.get(cand);
                int sourceGap = curr.region.y() - (prev.region.y() + prev.region.height());
                boolean sameTextMenuColumn =
                        prev.region.x() == curr.region.x()
                                && prev.region.width() == curr.region.width()
                                && prev.region.height() <= 16
                                && curr.region.height() <= 16
                                && sourceGap >= 0
                                && sourceGap <= 20;
                boolean sameGraphicalButtonColumn =
                        prev.region.x() == curr.region.x()
                                && prev.region.width() == curr.region.width()
                                && prev.region.height() >= 17
                                && prev.region.height() <= 36
                                && curr.region.height() >= 17
                                && curr.region.height() <= 36
                                && sourceGap >= 0
                                && sourceGap <= 24;
                if (sameTextMenuColumn || sameGraphicalButtonColumn) {
                    visited[cand] = true;
                    group.add(cand);
                }
            }
            if (group.size() >= 2) {
                int commonFontSize = Integer.MAX_VALUE;
                int commonWidth = 0;
                int minPeerX = Integer.MAX_VALUE;
                int stackLimitRight = maxRight;
                for (int idx : group) {
                    PreparedRegion p = list.get(idx);
                    commonFontSize = Math.min(commonFontSize, p.font.getSize());
                    minPeerX = Math.min(minPeerX, p.boxX);
                    stackLimitRight = Math.min(stackLimitRight, p.limitRight);
                }
                int maxStackAllowedW = Math.max(32, stackLimitRight - minX);
                Font commonFont = new Font(Font.SANS_SERIF, Font.BOLD, commonFontSize);
                FontMetrics commonMetrics = g.getFontMetrics(commonFont);
                for (int idx : group) {
                    PreparedRegion p = list.get(idx);
                    int neededW =
                            commonMetrics.stringWidth(p.region.text()) + 2 * p.paddingX;
                    commonWidth =
                            Math.max(
                                    commonWidth,
                                    Math.min(maxStackAllowedW, Math.max(p.boxW, neededW)));
                }
                commonWidth = Math.min(commonWidth, maxStackAllowedW);
                int unifiedX = minPeerX;
                if (unifiedX + commonWidth > stackLimitRight) {
                    unifiedX = Math.max(minX, stackLimitRight - commonWidth);
                }
                for (int idx : group) {
                    PreparedRegion p = list.get(idx);
                    list.set(
                            idx,
                            new PreparedRegion(
                                    p.region,
                                    unifiedX,
                                    p.boxY,
                                    commonWidth,
                                    p.boxH,
                                    stackLimitRight,
                                    commonFont,
                                    List.of(p.region.text()),
                                    p.paddingX,
                                    p.paddingY,
                                    p.localScale,
                                    p.centerText));
                }
            }
        }

        return list;
    }

    private void paintPreparedRegion(Graphics2D g, PreparedRegion item) {
        int arc = Math.max(6, (int) Math.round(3.0 * item.localScale));
        g.setColor(BOX_BACKGROUND);
        g.fillRoundRect(item.boxX, item.boxY, item.boxW, item.boxH, arc, arc);
        g.setColor(BOX_BORDER);
        g.setStroke(new BasicStroke(Math.max(1f, (float) (item.localScale * 0.45))));
        g.drawRoundRect(item.boxX, item.boxY, item.boxW, item.boxH, arc, arc);

        g.setFont(item.font);
        FontMetrics metrics = g.getFontMetrics();
        g.setColor(TEXT_COLOR);
        int lineHeight = metrics.getHeight();
        int totalTextHeight = item.lines.size() * lineHeight;
        int startY =
                item.boxY
                        + Math.max(item.paddingY, (item.boxH - totalTextHeight) / 2)
                        + metrics.getAscent();
        int maxTextBottom = item.boxY + item.boxH - item.paddingY;
        for (String line : item.lines) {
            if (startY - metrics.getAscent() + lineHeight
                    > maxTextBottom + metrics.getDescent()) {
                break;
            }
            int textX =
                    item.centerText
                            ? item.boxX + Math.max(item.paddingX, (item.boxW - metrics.stringWidth(line)) / 2)
                            : item.boxX + item.paddingX;
            g.drawString(line, textX, startY);
            startY += lineHeight;
        }
    }

    private record PreparedRegion(
            Region region,
            int boxX,
            int boxY,
            int boxW,
            int boxH,
            int limitRight,
            Font font,
            List<String> lines,
            int paddingX,
            int paddingY,
            double localScale,
            boolean centerText) {}

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
