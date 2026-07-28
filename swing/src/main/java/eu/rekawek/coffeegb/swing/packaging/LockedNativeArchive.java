package eu.rekawek.coffeegb.swing.packaging;

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipMethod;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Strict reader for the deterministic target-native archive embedded in an application image.
 *
 * <p>Keeping locked JNI payloads in an archive lets platform code signing seal the application
 * without rewriting their hardcoded upstream bytes. Runtime extraction still independently
 * verifies every size and SHA-256 through {@link NativeBundleResolver}.
 */
final class LockedNativeArchive {

    private static final long MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024L * 1024L;
    private static final long MAX_ENTRY_BYTES = 64L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 64;

    private LockedNativeArchive() {
    }

    static void verify(Path archive, NativeBundleManifest manifest) throws IOException {
        Path source = requireArchive(archive);
        Objects.requireNonNull(manifest, "manifest");
        Map<String, NativeBundleEntry> expected = new HashMap<>();
        long expectedExpandedBytes = 0;
        for (NativeBundleEntry entry : manifest.entries()) {
            if (expected.put(entry.resourcePath(), entry) != null) {
                throw new IOException(
                        "Locked native manifest contains a duplicate resource: "
                                + entry.resourcePath());
            }
            if (!NativeBundleResolver.isSafeRelativePath(entry.resourcePath())
                    || entry.byteSize() <= 0
                    || entry.byteSize() > MAX_ENTRY_BYTES) {
                throw new IOException(
                        "Locked native manifest contains an invalid entry: "
                                + entry.resourcePath());
            }
            try {
                expectedExpandedBytes =
                        Math.addExact(expectedExpandedBytes, entry.byteSize());
            } catch (ArithmeticException overflow) {
                throw new IOException("Locked native manifest expanded size overflow", overflow);
            }
        }
        if (expected.isEmpty()
                || expected.size() > MAX_ENTRIES
                || expectedExpandedBytes > MAX_EXPANDED_BYTES) {
            throw new IOException("Locked native manifest exceeds archive bounds");
        }

        Set<String> seen = new HashSet<>();
        List<String> physicalOrder = new ArrayList<>();
        long expandedBytes = 0;
        int entryCount = 0;
        try (ZipFile zip = openZip(source)) {
            Enumeration<ZipArchiveEntry> entries = zip.getEntriesInPhysicalOrder();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    throw new IOException(
                            "Locked native archive exceeds " + MAX_ENTRIES + " entries");
                }
                String name = entry.getName();
                if (!NativeBundleResolver.isSafeRelativePath(name)) {
                    throw new IOException(
                            "Locked native archive contains an unsafe path: " + name);
                }
                if (entry.isDirectory() || entry.isUnixSymlink() || !isRegularEntry(entry)) {
                    throw new IOException(
                            "Locked native archive contains a non-regular entry: " + name);
                }
                if (!zip.canReadEntryData(entry)) {
                    throw new IOException(
                            "Locked native archive uses an unsupported entry format: " + name);
                }
                if (!seen.add(name)) {
                    throw new IOException(
                            "Locked native archive contains a duplicate entry: " + name);
                }
                physicalOrder.add(name);
                NativeBundleEntry locked = expected.get(name);
                if (locked == null) {
                    throw new IOException(
                            "Locked native archive contains an unexpected entry: " + name);
                }
                if (entry.getMethod() != ZipMethod.STORED.getCode()
                        || entry.getSize() != locked.byteSize()
                        || entry.getCompressedSize() != locked.byteSize()) {
                    throw new IOException(
                            "Locked native archive entry is not stored at its exact size: "
                                    + name);
                }
                expandedBytes = Math.addExact(expandedBytes, entry.getSize());
                if (expandedBytes > MAX_EXPANDED_BYTES) {
                    throw new IOException(
                            "Locked native archive exceeds "
                                    + MAX_EXPANDED_BYTES
                                    + " expanded bytes");
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    verifyContents(input, locked);
                }
            }
        } catch (ArithmeticException overflow) {
            throw new IOException("Locked native archive expanded size overflow", overflow);
        }
        if (!seen.equals(expected.keySet())) {
            Set<String> missing = new HashSet<>(expected.keySet());
            missing.removeAll(seen);
            throw new IOException(
                    "Locked native archive is missing entries: " + missing);
        }
        List<String> expectedOrder = manifest.entries().stream()
                .map(NativeBundleEntry::resourcePath)
                .toList();
        if (!physicalOrder.equals(expectedOrder)) {
            throw new IOException(
                    "Locked native archive entries are not in manifest order");
        }
    }

    static Optional<InputStream> open(
            Path archive, NativeBundleManifest manifest, String resourcePath)
            throws IOException {
        if (!NativeBundleResolver.isSafeRelativePath(resourcePath)
                || manifest.entries().stream()
                        .noneMatch(entry -> entry.resourcePath().equals(resourcePath))) {
            return Optional.empty();
        }
        Path source = requireArchive(archive);
        NativeBundleEntry locked = manifest.entries().stream()
                .filter(entry -> entry.resourcePath().equals(resourcePath))
                .findFirst()
                .orElseThrow();
        ZipFile zip = openZip(source);
        try {
            List<ZipArchiveEntry> matches = new ArrayList<>();
            for (ZipArchiveEntry entry : zip.getEntries(resourcePath)) {
                matches.add(entry);
            }
            if (matches.size() != 1
                    || matches.get(0).isDirectory()
                    || matches.get(0).isUnixSymlink()
                    || !isRegularEntry(matches.get(0))
                    || !zip.canReadEntryData(matches.get(0))
                    || matches.get(0).getMethod() != ZipMethod.STORED.getCode()
                    || matches.get(0).getSize() != locked.byteSize()
                    || matches.get(0).getCompressedSize() != locked.byteSize()) {
                zip.close();
                return Optional.empty();
            }
            InputStream input = zip.getInputStream(matches.get(0));
            return Optional.of(new FilterInputStream(input) {
                @Override
                public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        zip.close();
                    }
                }
            });
        } catch (IOException | RuntimeException failure) {
            zip.close();
            throw failure;
        }
    }

    private static Path requireArchive(Path archive) throws IOException {
        Path source = archive.toAbsolutePath().normalize();
        long size = Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                ? Files.size(source)
                : -1;
        if (Files.isSymbolicLink(source)
                || !Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                || size <= 0
                || size > MAX_ARCHIVE_BYTES) {
            throw new IOException(
                    "Locked native archive is not a bounded regular file: " + source);
        }
        return source;
    }

    private static ZipFile openZip(Path source) throws IOException {
        return ZipFile.builder().setPath(source).get();
    }

    private static boolean isRegularEntry(ZipArchiveEntry entry) {
        int mode = entry.getUnixMode();
        return mode == 0 || (mode & 0170000) == 0100000;
    }

    private static void verifyContents(InputStream input, NativeBundleEntry expected)
            throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[64 * 1024];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total = Math.addExact(total, read);
            if (total > expected.byteSize()) {
                throw new IOException(
                        "Locked native archive entry exceeds expected size: "
                                + expected.resourcePath());
            }
            digest.update(buffer, 0, read);
        }
        if (total != expected.byteSize()
                || !hex(digest.digest()).equals(expected.sha256())) {
            throw new IOException(
                    "Locked native archive entry digest differs: "
                            + expected.resourcePath());
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        char[] value = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int current = bytes[index] & 0xff;
            value[index * 2] = Character.forDigit(current >>> 4, 16);
            value[index * 2 + 1] = Character.forDigit(current & 0x0f, 16);
        }
        return new String(value);
    }
}
