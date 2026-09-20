package eu.rekawek.coffeegb.core.state.bess;

/** Conversion at the portable-state boundary, including BESS's zero-fill/truncate rule. */
public final class BessMemory {
    private BessMemory() {
    }

    public static byte[] copy(int[] source) {
        byte[] result = new byte[source.length];
        for (int i = 0; i < source.length; i++) {
            result[i] = (byte) source[i];
        }
        return result;
    }

    public static void restore(int[] target, byte[] source, int offset) {
        for (int i = 0; i < target.length; i++) {
            target[i] = i + offset < source.length ? source[i + offset] & 0xff : 0;
        }
    }
}
