package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.state.ComponentState;
import java.util.Arrays;

/**
 * TAM's Barcode Taisen Bardigun reader, which samples optical bars on the GB's internal clock.
 * Unlike Namco's Barcode Boy, it has no handshake or ASCII frame. White is one, black is zero,
 * and the released button reads zero. Hardware measurements give about 15 samples per module.
 * See https://shonumi.github.io/articles/art6.html and its linked technical documentation.
 * All mutations are made on the emulation owner between ticks.
 */
public final class BardigunSerialEndpoint implements SerialEndpoint {
    public static final int SAMPLES_PER_MODULE = 15;
    public static final int SCAN_BYTES = (115 * SAMPLES_PER_MODULE + 7) / 8;

    private static final String[] LEFT = {
            "0001101", "0011001", "0010011", "0111101", "0100011",
            "0110001", "0101111", "0111011", "0110111", "0001011"
    };
    private static final String[] PARITY = {
            "LLLLLL", "LLGLGG", "LLGGLG", "LLGGGL", "LGLLGG",
            "LGGLLG", "LGGGLL", "LGLGLG", "LGLGGL", "LGGLGL"
    };

    private final int[] scan = new int[SCAN_BYTES];
    private final int[] pending = new int[SCAN_BYTES];
    private boolean pendingScan;
    private int byteIndex = SCAN_BYTES;
    private int bitIndex;

    /** Queues the printed EAN-13 digits; the game itself validates their checksum. */
    public void scan(String barcode) {
        int[] encoded = encode(barcode);
        System.arraycopy(encoded, 0, pending, 0, SCAN_BYTES);
        pendingScan = true;
    }

    public boolean isScanPending() {
        return pendingScan || byteIndex < SCAN_BYTES;
    }

    static int[] encode(String barcode) {
        if (barcode == null || barcode.length() != 13
                || !barcode.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("Barcode must contain exactly 13 ASCII digits");
        }
        StringBuilder modules = new StringBuilder("0000000000101");
        String parity = PARITY[barcode.charAt(0) - '0'];
        for (int i = 1; i <= 6; i++) {
            String digit = LEFT[barcode.charAt(i) - '0'];
            if (parity.charAt(i - 1) == 'G') digit = new StringBuilder(invert(digit)).reverse().toString();
            modules.append(digit);
        }
        modules.append("01010");
        for (int i = 7; i < 13; i++) modules.append(invert(LEFT[barcode.charAt(i) - '0']));
        modules.append("1010000000000");
        int[] result = new int[SCAN_BYTES];
        Arrays.fill(result, 0xff); // Quiet zones and final byte padding are white.
        for (int i = 0; i < modules.length(); i++) {
            if (modules.charAt(i) != '1') continue;
            for (int sample = i * SAMPLES_PER_MODULE; sample < (i + 1) * SAMPLES_PER_MODULE; sample++) {
                result[sample / 8] &= ~(1 << (7 - sample % 8));
            }
        }
        return result;
    }

    private static String invert(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) result.append(value.charAt(i) == '0' ? '1' : '0');
        return result.toString();
    }

    @Override public void setSb(int value) { }
    @Override public int recvBit() { return -1; }

    @Override
    public void startSending() {
        bitIndex = 0;
        if (pendingScan) {
            System.arraycopy(pending, 0, scan, 0, SCAN_BYTES);
            pendingScan = false;
            byteIndex = 0;
        }
    }

    @Override
    public int sendBit() {
        if (byteIndex == SCAN_BYTES) return 0;
        int value = (scan[byteIndex] >> (7 - bitIndex)) & 1;
        if (++bitIndex == 8) {
            bitIndex = 0;
            byteIndex++;
        }
        return value;
    }

    @Override public void disconnect() {
        pendingScan = false;
        byteIndex = SCAN_BYTES;
        bitIndex = 0;
    }

    @Override public int performanceInternalClockSpanLimit(int requested) { return Math.max(0, requested); }
    @Override public int performanceInputPinSpanLimit(int requested) { return Math.max(0, requested); }
    @Override public int performanceClockCapabilities() { return PERFORMANCE_CLOCK_INTERNAL; }
    @Override public void tickPerformanceInternalClockSpanTrusted(int ticks) { }

    @Override
    public ComponentState<SerialEndpoint> captureState() {
        return new BardigunState(scan.clone(), pending.clone(), pendingScan, byteIndex, bitIndex);
    }

    @Override
    public void restoreState(ComponentState<SerialEndpoint> state) {
        if (!(state instanceof BardigunState s) || s.scan == null || s.pending == null
                || s.scan.length != SCAN_BYTES || s.pending.length != SCAN_BYTES
                || s.byteIndex < 0 || s.byteIndex > SCAN_BYTES || s.bitIndex < 0 || s.bitIndex > 7
                || (s.byteIndex == SCAN_BYTES && s.bitIndex != 0)
                || Arrays.stream(s.scan).anyMatch(v -> v < 0 || v > 255)
                || Arrays.stream(s.pending).anyMatch(v -> v < 0 || v > 255)) {
            throw new IllegalArgumentException("Invalid Bardigun scanner state");
        }
        System.arraycopy(s.scan, 0, scan, 0, SCAN_BYTES);
        System.arraycopy(s.pending, 0, pending, 0, SCAN_BYTES);
        pendingScan = s.pendingScan;
        byteIndex = s.byteIndex;
        bitIndex = s.bitIndex;
    }

    private record BardigunState(int[] scan, int[] pending, boolean pendingScan, int byteIndex, int bitIndex)
            implements ComponentState<SerialEndpoint> { }
}
