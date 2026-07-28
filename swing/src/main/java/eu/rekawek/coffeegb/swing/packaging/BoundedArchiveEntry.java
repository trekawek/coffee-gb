package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Exact-length readers for archive entries after the central-directory preflight. */
final class BoundedArchiveEntry {

    private static final int BUFFER_BYTES = 64 * 1024;

    private BoundedArchiveEntry() {
    }

    static byte[] readExact(
            InputStream input,
            long expectedSize,
            long maximumSize,
            String description)
            throws IOException {
        requireBoundedSize(expectedSize, maximumSize, description);
        if (expectedSize > Integer.MAX_VALUE) {
            throw new IOException(description + " is too large to read in memory");
        }
        byte[] contents = new byte[(int) expectedSize];
        int offset = 0;
        while (offset < contents.length) {
            int read = input.read(contents, offset, contents.length - offset);
            if (read < 0) {
                throw new IOException(description + " ended before its declared size");
            }
            if (read == 0) {
                throw new IOException(description + " made no progress while reading");
            }
            offset += read;
        }
        requireEndOfEntry(input, description);
        return contents;
    }

    static String sha256Exact(
            InputStream input,
            long expectedSize,
            long maximumSize,
            String description)
            throws IOException {
        requireBoundedSize(expectedSize, maximumSize, description);
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[BUFFER_BYTES];
        long remaining = expectedSize;
        while (remaining > 0) {
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new IOException(description + " ended before its declared size");
            }
            if (read == 0) {
                throw new IOException(description + " made no progress while reading");
            }
            digest.update(buffer, 0, read);
            remaining -= read;
        }
        requireEndOfEntry(input, description);
        return hex(digest.digest());
    }

    private static void requireBoundedSize(
            long expectedSize, long maximumSize, String description) throws IOException {
        if (expectedSize < 0 || expectedSize > maximumSize) {
            throw new IOException(
                    description + " has invalid declared size " + expectedSize);
        }
    }

    private static void requireEndOfEntry(InputStream input, String description)
            throws IOException {
        if (input.read() != -1) {
            throw new IOException(description + " expands beyond its declared size");
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] digest) {
        char[] result = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            int value = digest[i] & 0xff;
            result[i * 2] = Character.forDigit(value >>> 4, 16);
            result[i * 2 + 1] = Character.forDigit(value & 0x0f, 16);
        }
        return new String(result);
    }
}
