package eu.rekawek.coffeegb.android;

import android.graphics.Bitmap;
import eu.rekawek.coffeegb.controller.Controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Bounded, immutable Android-side retention for Game Boy Printer strips. The controller thread
 * copies every producer-owned print buffer before returning, so preview and SAF export never hold
 * live emulation memory.
 */
final class AndroidPrinterStore {

    static final int WIDTH = 160;
    private static final int MARGIN_PIXELS_PER_UNIT = 3;
    private static final long MAX_DECODED_PIXELS = 2L * 1024L * 1024L;
    private static final int PAPER_WHITE = 0xffffffff;

    private final List<Segment> segments = new ArrayList<>();
    private long decodedPixels;
    private long omittedStrips;

    synchronized boolean append(Controller.PrinterPrintEvent event) {
        if (event.getWidth() != WIDTH || event.getHeight() < 0 || event.getTopMargin() < 0
                || event.getBottomMargin() < 0) {
            omit();
            return false;
        }
        try {
            int sourcePixels = Math.multiplyExact(event.getWidth(), event.getHeight());
            if (event.getArgb().length != sourcePixels) {
                omit();
                return false;
            }
            int top = Math.multiplyExact(event.getTopMargin(), MARGIN_PIXELS_PER_UNIT);
            int bottom = Math.multiplyExact(event.getBottomMargin(), MARGIN_PIXELS_PER_UNIT);
            int height = Math.addExact(Math.addExact(top, event.getHeight()), bottom);
            if (height <= 0) {
                omit();
                return false;
            }
            long pixels = Math.multiplyExact((long) WIDTH, height);
            if (pixels > MAX_DECODED_PIXELS - decodedPixels) {
                omit();
                return false;
            }
            int[] copy = new int[(int) pixels];
            Arrays.fill(copy, PAPER_WHITE);
            System.arraycopy(event.getArgb(), 0, copy, top * WIDTH, sourcePixels);
            segments.add(new Segment(height, copy));
            decodedPixels += pixels;
            return true;
        } catch (ArithmeticException ignored) {
            omit();
            return false;
        }
    }

    synchronized Snapshot snapshot() {
        if (segments.isEmpty()) {
            return null;
        }
        int height = 0;
        for (Segment segment : segments) {
            height = Math.addExact(height, segment.height());
        }
        return new Snapshot(height, List.copyOf(segments));
    }

    synchronized void clear() {
        segments.clear();
        decodedPixels = 0;
        omittedStrips = 0;
    }

    synchronized long omittedStrips() {
        return omittedStrips;
    }

    private void omit() {
        if (omittedStrips < Long.MAX_VALUE) {
            omittedStrips++;
        }
    }

    record Snapshot(int height, List<Segment> segments) {
        int[] copyArgb() {
            int[] copy = new int[Math.multiplyExact(WIDTH, height)];
            int offset = 0;
            for (Segment segment : segments) {
                System.arraycopy(segment.argb(), 0, copy, offset, segment.argb().length);
                offset += segment.argb().length;
            }
            return copy;
        }

        Bitmap toBitmap() {
            Bitmap bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888);
            int top = 0;
            for (Segment segment : segments) {
                bitmap.setPixels(segment.argb(), 0, WIDTH, 0, top, WIDTH, segment.height());
                top += segment.height();
            }
            return bitmap;
        }
    }

    private record Segment(int height, int[] argb) {
    }
}
