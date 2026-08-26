package eu.rekawek.coffeegb.core.memory.cart.battery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable battery destination plus a bounded, ordered set of import-only fallbacks.
 *
 * <p>A managed path carries the user-selected root that contains it. Existing components below
 * that root are checked without following links before every read or write. This is the same
 * portable-NIO boundary used by managed state storage: it prevents pre-existing symlink traversal,
 * while a hostile concurrent filesystem replacement remains outside Java's portable guarantees.
 */
public final class BatteryStorage {

    public static final int MAX_IMPORT_SOURCES = 8;

    private final Source target;

    private final List<Source> importSources;

    public BatteryStorage(Source target, List<Source> importSources) {
        this.target = Objects.requireNonNull(target, "target");
        Objects.requireNonNull(importSources, "importSources");
        if (importSources.size() > MAX_IMPORT_SOURCES) {
            throw new IllegalArgumentException(
                    "At most " + MAX_IMPORT_SOURCES + " battery import sources are allowed");
        }
        Map<Path, Source> unique = new LinkedHashMap<>();
        for (Source source : importSources) {
            Source checked = Objects.requireNonNull(source, "import source");
            if (!checked.path().equals(target.path())) {
                unique.putIfAbsent(checked.path(), checked);
            }
        }
        this.importSources = List.copyOf(unique.values());
    }

    public static BatteryStorage direct(Path target) {
        return new BatteryStorage(Source.direct(target), List.of());
    }

    public static BatteryStorage direct(Path target, List<Path> importSources) {
        Objects.requireNonNull(importSources, "importSources");
        return new BatteryStorage(
                Source.direct(target),
                importSources.stream()
                        .map(path -> Source.managed(path, path.toAbsolutePath().normalize().getParent()))
                        .toList());
    }

    public Path targetPath() {
        return target.path();
    }

    public List<Source> importSources() {
        return importSources;
    }

    void ensureTargetSafe() throws IOException {
        target.ensureSafe(true);
    }

    void ensureReadableTarget(Path expected) throws IOException {
        if (!target.path().equals(normalizeFile(expected))) {
            throw new IOException("Battery reader received an unexpected target");
        }
        target.ensureSafe(true);
        requireRegularFile(target.path(), "Battery target");
    }

    void ensureReadableImport(Path expected) throws IOException {
        Source source = importSource(expected);
        if (!source.ensureSafe(false)) {
            throw new IOException("Battery import source no longer exists");
        }
        requireRegularFile(source.path(), "Battery import source");
    }

    boolean ensureImportCandidateSafe(Path expected) throws IOException {
        Source source = importSource(expected);
        if (!source.ensureSafe(false)) {
            return false;
        }
        if (!Files.exists(source.path().getParent(), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        if (Files.exists(source.path(), LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(source.path(), "Battery import source");
        }
        return true;
    }

    /**
     * Revalidates one exact target returned by {@link #firstReadablePath()} immediately before a
     * bounded external read.
     */
    public void ensureReadablePath(Path expected) throws IOException {
        Path normalized = normalizeFile(expected);
        if (target.path().equals(normalized)) {
            ensureReadableTarget(normalized);
        } else {
            ensureReadableImport(normalized);
        }
    }

    private Source importSource(Path expected) throws IOException {
        Path normalized = normalizeFile(expected);
        return importSources.stream()
                .filter(candidate -> candidate.path().equals(normalized))
                .findFirst()
                .orElseThrow(
                        () -> new IOException("Battery reader received an unexpected import source"));
    }

    Optional<Path> firstReadableImport() throws IOException {
        IOException unsafeSource = null;
        for (Source source : importSources) {
            try {
                if (source.ensureSafe(false)
                        && Files.exists(source.path(), LinkOption.NOFOLLOW_LINKS)) {
                    requireRegularFile(source.path(), "Battery import source");
                    return Optional.of(source.path());
                }
            } catch (IOException failure) {
                if (unsafeSource == null) {
                    unsafeSource = failure;
                }
            }
        }
        if (unsafeSource != null) {
            throw unsafeSource;
        }
        return Optional.empty();
    }

    /**
     * Returns the active battery file or the first safe import fallback. This is used only to stage
     * a bounded netplay payload before a local cartridge has performed its ordinary import.
     */
    public Optional<Path> firstReadablePath() throws IOException {
        if (target.ensureSafe(true) && Files.exists(target.path(), LinkOption.NOFOLLOW_LINKS)) {
            requireRegularFile(target.path(), "Battery target");
            return Optional.of(target.path());
        }
        return firstReadableImport();
    }

    private static void requireRegularFile(Path path, String label) throws IOException {
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a regular file: " + path.getFileName());
        }
    }

    /** One exact source, optionally constrained below a managed root. */
    public static final class Source {

        private final Path path;

        private final Path managedRoot;

        private final boolean trustedManagedRootAncestors;

        private Source(Path path, Path managedRoot, boolean trustedManagedRootAncestors) {
            this.path = normalizeFile(path);
            this.managedRoot =
                    managedRoot == null ? null : normalizeDirectory(managedRoot, "managedRoot");
            this.trustedManagedRootAncestors = trustedManagedRootAncestors;
            if (this.managedRoot != null && !this.path.startsWith(this.managedRoot)) {
                throw new IllegalArgumentException("Battery path escapes its managed root");
            }
        }

        public static Source direct(Path path) {
            return new Source(path, null, false);
        }

        public static Source managed(Path path, Path managedRoot) {
            return new Source(path, Objects.requireNonNull(managedRoot, "managedRoot"), false);
        }

        /**
         * Constrains a target below a platform-created app-private directory.
         *
         * <p>Android deliberately allows an app to traverse its own package directory while
         * denying metadata access to shared ancestors such as {@code /data/user/0}. The ordinary
         * managed source validates every ancestor from the filesystem root and therefore cannot
         * distinguish that policy from an unsafe path. This variant treats only the supplied
         * managed root's ancestors as platform-trusted; the root itself and every existing
         * component below it are still checked without following symbolic links.
         */
        public static Source appPrivate(Path path, Path managedRoot) {
            return new Source(path, Objects.requireNonNull(managedRoot, "managedRoot"), true);
        }

        public Path path() {
            return path;
        }

        public Optional<Path> managedRoot() {
            return Optional.ofNullable(managedRoot);
        }

        /**
         * @return false only when an import-only managed root does not exist
         */
        private boolean ensureSafe(boolean target) throws IOException {
            if (managedRoot == null) {
                Path parent = path.getParent();
                Path root = path.getRoot();
                if (root == null) {
                    throw new IOException("Battery path must be absolute");
                }
                requireDirectory(root, "Filesystem root");
                Path cursor = root;
                for (Path component : parent) {
                    cursor = cursor.resolve(component);
                    if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                        if (target) {
                            throw new IOException("Battery directory does not exist");
                        }
                        return false;
                    }
                    requireDirectory(cursor, "Battery directory component");
                }
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                        && Files.isSymbolicLink(path)) {
                    throw new IOException("Battery file must not be a symbolic link");
                }
                return true;
            }

            if (!Files.exists(managedRoot, LinkOption.NOFOLLOW_LINKS)) {
                if (target) {
                    throw new IOException("Configured Saves directory does not exist");
                }
                return false;
            }
            Path root = managedRoot.getRoot();
            if (root == null) {
                throw new IOException("Managed battery root must be absolute");
            }
            if (trustedManagedRootAncestors) {
                requireDirectory(managedRoot, "App-private battery root");
            } else {
                requireDirectory(root, "Filesystem root");
                Path rootCursor = root;
                for (Path component : managedRoot) {
                    rootCursor = rootCursor.resolve(component);
                    requireDirectory(rootCursor, "Battery root component");
                }
            }

            Path parent = path.getParent();
            Path cursor = managedRoot;
            for (Path component : managedRoot.relativize(parent)) {
                cursor = cursor.resolve(component);
                if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                    break;
                }
                requireDirectory(cursor, "Battery directory component");
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    && Files.isSymbolicLink(path)) {
                throw new IOException("Battery file must not be a symbolic link");
            }
            return true;
        }

        private static void requireDirectory(Path path, String label) throws IOException {
            if (Files.isSymbolicLink(path)
                    || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(label + " is not a safe directory: " + path.getFileName());
            }
        }
    }

    private static Path normalizeFile(Path path) {
        Path normalized = Objects.requireNonNull(path, "path").toAbsolutePath().normalize();
        if (normalized.getFileName() == null || normalized.getParent() == null) {
            throw new IllegalArgumentException("Battery path must have a file name and parent");
        }
        return normalized;
    }

    private static Path normalizeDirectory(Path path, String name) {
        Path normalized = Objects.requireNonNull(path, name).toAbsolutePath().normalize();
        if (normalized.getFileName() == null || normalized.getParent() == null) {
            throw new IllegalArgumentException(name + " must have a name and parent");
        }
        return normalized;
    }
}
