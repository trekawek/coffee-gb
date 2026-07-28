package eu.rekawek.coffeegb.core.memory.cart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Objects;

/** Exact, bounded ROM bytes paired with their immutable origin. */
public final class RomImage {

    public static final int MAX_ROM_BYTES = 64 * 1024 * 1024;

    private static final int COPY_BUFFER_SIZE = 32 * 1024;

    private final RomOrigin origin;

    private final byte[] bytes;

    public RomImage(RomOrigin origin, byte[] bytes) throws IOException {
        this.origin = Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(bytes, "bytes");
        validateLength(bytes.length);
        this.bytes = bytes.clone();
    }

    public static RomImage directFile(Path path) throws IOException {
        Path normalized = path.toAbsolutePath().normalize();
        long size = Files.size(normalized);
        try (InputStream input = Files.newInputStream(normalized)) {
            return new RomImage(
                    RomOrigin.directFile(normalized),
                    readBounded(input, size));
        }
    }

    public static RomImage memory(byte[] bytes, String displayName) throws IOException {
        Objects.requireNonNull(bytes, "bytes");
        validateLength(bytes.length);
        return new RomImage(
                RomOrigin.memory("sha256:" + sha256(bytes), displayName),
                bytes);
    }

    public RomOrigin origin() {
        return origin;
    }

    /** Returns a defensive copy of the exact selected bytes. */
    public byte[] bytes() {
        return bytes.clone();
    }

    public int size() {
        return bytes.length;
    }

    byte[] copyBytesForParser() {
        return bytes.clone();
    }

    byte[] copyHeaderForInspector() {
        return Arrays.copyOf(bytes, RomHeaderInspector.HEADER_LENGTH);
    }

    public static byte[] readBounded(InputStream input, long declaredSize) throws IOException {
        Objects.requireNonNull(input, "input");
        if (declaredSize > MAX_ROM_BYTES) {
            throw new RomSizeLimitException(declaredSize, MAX_ROM_BYTES);
        }
        int initialSize =
                declaredSize > 0
                        ? (int) Math.min(declaredSize, COPY_BUFFER_SIZE * 8L)
                        : COPY_BUFFER_SIZE;
        ByteArrayOutputStream output = new ByteArrayOutputStream(initialSize);
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        int total = 0;
        while (true) {
            int read = input.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                int value = input.read();
                if (value < 0) {
                    break;
                }
                if (total == MAX_ROM_BYTES) {
                    throw new RomSizeLimitException((long) total + 1, MAX_ROM_BYTES);
                }
                output.write(value);
                total++;
                continue;
            }
            if (read > MAX_ROM_BYTES - total) {
                throw new RomSizeLimitException((long) total + read, MAX_ROM_BYTES);
            }
            output.write(buffer, 0, read);
            total += read;
        }
        validateLength(total);
        return output.toByteArray();
    }

    private static void validateLength(long length) throws IOException {
        if (length > MAX_ROM_BYTES) {
            throw new RomSizeLimitException(length, MAX_ROM_BYTES);
        }
        if (length < RomHeaderInspector.HEADER_LENGTH) {
            throw new IOException(
                    "ROM is truncated: "
                            + length
                            + " bytes, expected at least "
                            + RomHeaderInspector.HEADER_LENGTH);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format("%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    public static final class RomSizeLimitException extends IOException {

        private final long observedBytes;

        private final long limitBytes;

        RomSizeLimitException(long observedBytes, long limitBytes) {
            super("ROM exceeds the " + limitBytes + "-byte safety limit");
            this.observedBytes = observedBytes;
            this.limitBytes = limitBytes;
        }

        public long observedBytes() {
            return observedBytes;
        }

        public long limitBytes() {
            return limitBytes;
        }
    }
}
