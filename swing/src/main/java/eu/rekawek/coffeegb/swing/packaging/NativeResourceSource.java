package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.Optional;

/** Injected source for locked native resources; it does not infer a host or package target. */
@FunctionalInterface
public interface NativeResourceSource {

    Optional<InputStream> open(String resourcePath) throws IOException;

    static NativeResourceSource classpath(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        return path -> Optional.ofNullable(classLoader.getResourceAsStream(path));
    }

    static NativeResourceSource directory(Path root) {
        Path normalizedRoot = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        return resourcePath -> {
            if (!NativeBundleResolver.isSafeRelativePath(resourcePath)) {
                return Optional.empty();
            }
            if (!Files.isDirectory(normalizedRoot, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(normalizedRoot)) {
                return Optional.empty();
            }
            Path candidate = normalizedRoot.resolve(resourcePath).normalize();
            if (!candidate.startsWith(normalizedRoot)) {
                return Optional.empty();
            }
            Path current = normalizedRoot;
            String[] segments = resourcePath.split("/");
            for (int i = 0; i < segments.length; i++) {
                current = current.resolve(segments[i]);
                if (Files.isSymbolicLink(current)) {
                    return Optional.empty();
                }
                if (i < segments.length - 1
                        && !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                    return Optional.empty();
                }
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return Optional.empty();
            }
            return Optional.of(
                    Files.newInputStream(candidate, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        };
    }

    static NativeResourceSource archive(Path archive, NativeBundleManifest manifest)
            throws IOException {
        Path normalizedArchive =
                Objects.requireNonNull(archive, "archive").toAbsolutePath().normalize();
        NativeBundleManifest locked = Objects.requireNonNull(manifest, "manifest");
        LockedNativeArchive.verify(normalizedArchive, locked);
        return resourcePath ->
                LockedNativeArchive.open(normalizedArchive, locked, resourcePath);
    }
}
