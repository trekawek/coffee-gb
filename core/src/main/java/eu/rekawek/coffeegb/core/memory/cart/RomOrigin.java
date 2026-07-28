package eu.rekawek.coffeegb.core.memory.cart;

import org.apache.commons.io.FilenameUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable identity of the exact ROM selected by the user.
 *
 * <p>A container path is deliberately not a ROM identity: two entries in the same archive must
 * have different identities and persistence anchors. In-memory images have an explicit stable
 * identity but no implicit permission to write beside an invented file path.
 */
public final class RomOrigin {

    public enum Kind {
        DIRECT_FILE,
        ARCHIVE_ENTRY,
        MEMORY
    }

    private static final String ARCHIVE_SAVE_SEPARATOR = "--";

    private final Kind kind;

    private final Path containerPath;

    private final String archiveEntry;

    private final int archiveEntryOccurrence;

    private final String memoryIdentity;

    private final String displayName;

    private final String stableIdentity;

    private final Path persistenceDirectory;

    private final String persistenceStem;

    private final boolean legacyBatteryMigrationUnambiguous;

    private RomOrigin(
            Kind kind,
            Path containerPath,
            String archiveEntry,
            int archiveEntryOccurrence,
            String memoryIdentity,
            String displayName,
            String stableIdentity,
            Path persistenceDirectory,
            String persistenceStem,
            boolean legacyBatteryMigrationUnambiguous) {
        this.kind = kind;
        this.containerPath = containerPath;
        this.archiveEntry = archiveEntry;
        this.archiveEntryOccurrence = archiveEntryOccurrence;
        this.memoryIdentity = memoryIdentity;
        this.displayName = displayName;
        this.stableIdentity = stableIdentity;
        this.persistenceDirectory = persistenceDirectory;
        this.persistenceStem = persistenceStem;
        this.legacyBatteryMigrationUnambiguous = legacyBatteryMigrationUnambiguous;
    }

    public static RomOrigin directFile(Path path) {
        Path normalized = normalizeContainer(path);
        String fileName = normalized.getFileName().toString();
        return new RomOrigin(
                Kind.DIRECT_FILE,
                normalized,
                null,
                0,
                null,
                fileName,
                "file:" + normalized.toUri().normalize(),
                normalized.getParent(),
                FilenameUtils.removeExtension(fileName),
                false);
    }

    public static RomOrigin archiveEntry(Path container, String entryName) {
        return archiveEntry(container, entryName, false);
    }

    /**
     * Creates an archive-entry origin.
     *
     * @param legacyBatteryMigrationUnambiguous true only after a bounded archive inventory proved
     *                                         that this is the sole ROM candidate
     */
    public static RomOrigin archiveEntry(
            Path container,
            String entryName,
            boolean legacyBatteryMigrationUnambiguous) {
        return archiveEntry(container, entryName, 0, legacyBatteryMigrationUnambiguous);
    }

    /**
     * Creates an origin for an exact occurrence of an archive name. Occurrence zero is the first
     * matching record; later duplicate records must use their zero-based duplicate occurrence.
     */
    public static RomOrigin archiveEntry(
            Path container,
            String entryName,
            int entryOccurrence,
            boolean legacyBatteryMigrationUnambiguous) {
        if (entryOccurrence < 0) {
            throw new IllegalArgumentException("entryOccurrence must not be negative");
        }
        Path normalizedContainer = normalizeContainer(container);
        String exactEntry = validateArchiveEntry(entryName);
        String containerIdentity = normalizedContainer.toUri().normalize().toString();
        String identity =
                "archive:v1:"
                        + lengthPrefixed(containerIdentity)
                        + lengthPrefixed(exactEntry)
                        + entryOccurrence;
        String containerFileName = normalizedContainer.getFileName().toString();
        String containerBase = safeFilePart(FilenameUtils.removeExtension(containerFileName));
        String entryFileName = archiveFileName(exactEntry);
        String entryBase =
                FilenameUtils.removeExtension(entryFileName);
        String safeEntryBase = safeFilePart(entryBase);
        // Keep sidecars portable when a folder is moved: the directory is already the namespace,
        // so only the exact container filename and exact entry name belong in the suffix.
        String persistenceIdentity =
                "archive-persistence:v1:"
                        + lengthPrefixed(containerFileName)
                        + lengthPrefixed(exactEntry)
                        + entryOccurrence;
        String suffix = digest(persistenceIdentity).substring(0, 32);
        String stem =
                containerBase
                        + ARCHIVE_SAVE_SEPARATOR
                        + safeEntryBase
                        + "-"
                        + suffix;
        return new RomOrigin(
                Kind.ARCHIVE_ENTRY,
                normalizedContainer,
                exactEntry,
                entryOccurrence,
                null,
                entryFileName,
                identity,
                normalizedContainer.getParent(),
                stem,
                legacyBatteryMigrationUnambiguous);
    }

    public static RomOrigin memory(String stableId, String displayName) {
        String id = requireText(stableId, "stableId");
        String name = requireText(displayName, "displayName");
        return new RomOrigin(
                Kind.MEMORY,
                null,
                null,
                0,
                id,
                name,
                "memory:" + id,
                null,
                null,
                false);
    }

    public Kind kind() {
        return kind;
    }

    public Optional<Path> containerPath() {
        return Optional.ofNullable(containerPath);
    }

    public Optional<String> archiveEntry() {
        return Optional.ofNullable(archiveEntry);
    }

    public int archiveEntryOccurrence() {
        return archiveEntryOccurrence;
    }

    public Optional<String> memoryIdentity() {
        return Optional.ofNullable(memoryIdentity);
    }

    public String displayName() {
        return displayName;
    }

    public String stableIdentity() {
        return stableIdentity;
    }

    /** Resolves a ROM-owned sidecar suffix, such as {@code .sav} or {@code .sn0}. */
    public Optional<Path> persistencePath(String suffix) {
        String safeSuffix = validatePersistenceSuffix(suffix);
        if (persistenceDirectory == null) {
            return Optional.empty();
        }
        return Optional.of(persistenceDirectory.resolve(persistenceStem + safeSuffix));
    }

    public boolean legacyBatteryMigrationUnambiguous() {
        return legacyBatteryMigrationUnambiguous;
    }

    public Optional<Path> legacyArchivePersistencePath(String suffix) {
        String safeSuffix = validatePersistenceSuffix(suffix);
        if (kind != Kind.ARCHIVE_ENTRY || !legacyBatteryMigrationUnambiguous) {
            return Optional.empty();
        }
        String containerBase =
                FilenameUtils.removeExtension(containerPath.getFileName().toString());
        return Optional.of(containerPath.getParent().resolve(containerBase + safeSuffix));
    }

    private static Path normalizeContainer(Path path) {
        Objects.requireNonNull(path, "path");
        Path normalized = path.toAbsolutePath().normalize();
        if (normalized.getFileName() == null) {
            throw new IllegalArgumentException("ROM container must name a file");
        }
        return normalized;
    }

    private static String validateArchiveEntry(String entryName) {
        String raw = requireText(entryName, "entryName");
        String pathForValidation = raw.replace('\\', '/');
        if (raw.indexOf('\0') >= 0
                || pathForValidation.startsWith("/")
                || pathForValidation.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("Archive entry must be a relative safe path");
        }
        for (String component : pathForValidation.split("/", -1)) {
            if (component.equals(".") || component.equals("..")) {
                throw new IllegalArgumentException(
                        "Archive entry contains a traversal component");
            }
        }
        return raw;
    }

    private static String archiveFileName(String entryName) {
        int slash = Math.max(entryName.lastIndexOf('/'), entryName.lastIndexOf('\\'));
        return entryName.substring(slash + 1);
    }

    private static String validatePersistenceSuffix(String suffix) {
        Objects.requireNonNull(suffix, "suffix");
        if (suffix.isEmpty()
                || suffix.charAt(0) != '.'
                || suffix.indexOf('/') >= 0
                || suffix.indexOf('\\') >= 0
                || suffix.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(
                    "Persistence suffix must begin with '.' and contain no path separators");
        }
        return suffix;
    }

    private static String safeFilePart(String value) {
        String safe =
                value.replaceAll("[^A-Za-z0-9._-]+", "-")
                        .replaceAll("^-+|-+$", "")
                        .toLowerCase(Locale.ROOT);
        if (safe.isEmpty()) {
            return "rom";
        }
        return safe.length() <= 48 ? safe : safe.substring(0, 48);
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String digest(String value) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                result.append(String.format("%02x", b & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    private static String lengthPrefixed(String value) {
        return value.length() + ":" + value;
    }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof RomOrigin origin)) {
            return false;
        }
        return kind == origin.kind
                && Objects.equals(pathIdentity(containerPath), pathIdentity(origin.containerPath))
                && Objects.equals(archiveEntry, origin.archiveEntry)
                && archiveEntryOccurrence == origin.archiveEntryOccurrence
                && Objects.equals(memoryIdentity, origin.memoryIdentity);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                kind,
                pathIdentity(containerPath),
                archiveEntry,
                archiveEntryOccurrence,
                memoryIdentity);
    }

    @Override
    public String toString() {
        return stableIdentity;
    }

    private static String pathIdentity(Path path) {
        return path == null ? null : path.toUri().normalize().toString();
    }
}
