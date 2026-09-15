package eu.rekawek.coffeegb.core.ir;

import java.util.Arrays;

/** A validated GBKiss file; see https://gbkiss.org/tech/metadata. */
public final class GbfFile {
    public static final int MAX_SIZE = 0xffff;
    public static final int HISTORY_SIZE = 46;
    private final byte[] bytes;

    public GbfFile(byte[] bytes) {
        if (bytes == null || bytes.length < 7 || bytes.length > MAX_SIZE) {
            throw new IllegalArgumentException("GBF file must contain between 7 and 65535 bytes");
        }
        this.bytes = bytes.clone();
        if (word(this.bytes, 0) != bytes.length) {
            throw new IllegalArgumentException("GBF file size does not match its header");
        }
        int iconSize = (flags() & 0x10) == 0 ? 0 : (flags() & 8) == 0 ? 96 : 192;
        // The title/icon block includes the creator byte, followed by at least one title byte.
        if (titleIconSize() < iconSize + 2 || titleIconSize() > 245
                || metadataSize() > bytes.length) {
            throw new IllegalArgumentException("GBF title, icon, or history is truncated or invalid");
        }
    }

    public int flags() { return bytes[2] & 0xff; }
    public int cartridgeCode() { return bytes[3] & 0xff; }
    public int titleIconSize() { return bytes[4] & 0xff; }
    public int historySize() { return (flags() & 1) == 0 ? 0 : HISTORY_SIZE; }
    public int metadataSize() { return 5 + titleIconSize() + historySize(); }
    public int payloadSize() { return bytes.length - metadataSize(); }
    public byte[] bytes() { return bytes.clone(); }
    public byte[] titleIcon() { return Arrays.copyOfRange(bytes, 5, 5 + titleIconSize()); }
    public byte[] history() { return Arrays.copyOfRange(bytes, 5 + titleIconSize(), metadataSize()); }
    public byte[] payload() { return Arrays.copyOfRange(bytes, metadataSize(), bytes.length); }

    static int word(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }
}
