package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Validates the reviewed legal inventory against the exact resolved Maven component set.
 *
 * <p>The CycloneDX Maven BOM remains authoritative for the dependency graph. This manifest makes
 * license retention independently reviewable and prevents fat-JAR resource collisions from
 * silently dropping a dependency's only notice.
 */
final class ThirdPartyNoticeInventory {

    static final String CATALOG = "THIRD-PARTY-COMPONENTS.txt";
    static final String NOTICES = "THIRD-PARTY-NOTICES.txt";
    static final String EMBEDDED_PREFIX = "META-INF/coffee-gb/legal/";
    static final int MAX_SBOM_BYTES = 8 * 1024 * 1024;
    private static final int MAX_LEGAL_ENTRIES = 128;
    private static final long MAX_LEGAL_FILE_BYTES = 2L * 1024L * 1024L;
    private static final String FIRST_PARTY_PREFIX = "pkg:maven/eu.rekawek.coffeegb/";

    private ThirdPartyNoticeInventory() {
    }

    static void validate(Path sbom, Path legalRoot) throws IOException {
        Map<String, Set<String>> catalog = readCatalog(legalRoot);
        Set<String> resolved = resolvedThirdPartyPurls(sbom);
        if (!catalog.keySet().equals(resolved)) {
            Set<String> missing = new TreeSet<>(resolved);
            missing.removeAll(catalog.keySet());
            Set<String> stale = new TreeSet<>(catalog.keySet());
            stale.removeAll(resolved);
            throw new IOException(
                    "Reviewed legal catalog does not match resolved Maven components; missing="
                            + missing + ", stale=" + stale);
        }

        String notices = readBounded(legalRoot.resolve(NOTICES), 1024 * 1024, NOTICES);
        for (Map.Entry<String, Set<String>> component : catalog.entrySet()) {
            if (!notices.contains(component.getKey())) {
                throw new IOException(
                        "Third-party notices omit reviewed component " + component.getKey());
            }
            for (String license : component.getValue()) {
                Path file = safeLegalFile(legalRoot, license);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        || Files.isSymbolicLink(file)
                        || Files.size(file) <= 0
                        || Files.size(file) > 1024 * 1024) {
                    throw new IOException(
                            "Reviewed component license is missing or invalid: " + license);
                }
            }
        }
    }

    static Set<String> expectedLegalFiles(Path legalRoot) throws IOException {
        Path root = legalRoot.toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)
                || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Authoritative legal root is not a non-symlink directory");
        }
        try (Stream<Path> paths = Files.walk(root)) {
            var entries = paths.limit(MAX_LEGAL_ENTRIES + 1L).toList();
            if (entries.size() > MAX_LEGAL_ENTRIES) {
                throw new IOException(
                        "Authoritative legal inventory exceeds " + MAX_LEGAL_ENTRIES + " entries");
            }
            for (Path entry : entries) {
                if (Files.isSymbolicLink(entry)) {
                    throw new IOException("Authoritative legal inventory contains a symlink");
                }
            }
            return entries.stream()
                    .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    static void verifyEmbeddedLegal(Path jar, Path legalRoot) throws IOException {
        Set<String> expected = expectedLegalFiles(legalRoot);
        Map<String, JarEntry> actual = new HashMap<>();
        BoundedZipPreflight.verify(
                jar,
                NativePackageStager.MAX_APP_JAR_BYTES,
                NativePackageStager.MAX_APP_JAR_ENTRIES,
                "Application JAR legal inventory");
        try (JarFile file = new JarFile(jar.toFile(), false)) {
            Enumeration<JarEntry> entries = file.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (!entry.isDirectory() && entry.getName().startsWith(EMBEDDED_PREFIX)) {
                    String relative = entry.getName().substring(EMBEDDED_PREFIX.length());
                    if (actual.putIfAbsent(relative, entry) != null) {
                        throw new IOException(
                                "Application JAR has duplicate embedded legal file " + relative);
                    }
                }
            }
            if (!actual.keySet().equals(expected)) {
                throw new IOException(
                        "Application JAR embedded legal inventory mismatch; expected="
                                + expected + ", actual=" + actual.keySet());
            }
            for (String relative : expected) {
                Path source = safeLegalFile(legalRoot, relative);
                JarEntry entry = actual.get(relative);
                long expectedSize = Files.size(source);
                if (expectedSize <= 0
                        || expectedSize > MAX_LEGAL_FILE_BYTES
                        || entry.getSize() != expectedSize) {
                    throw new IOException(
                            "Application JAR legal file has the wrong size: " + relative);
                }
                try (InputStream input = file.getInputStream(entry)) {
                    String embedded = embeddedLegalSha256(input, expectedSize, relative);
                    String sourceDigest = NativePackageStager.sha256(source);
                    if (!embedded.equals(sourceDigest)) {
                        throw new IOException(
                                "Application JAR legal file differs from source: " + relative);
                    }
                }
            }
        }
    }

    static String embeddedLegalSha256(
            InputStream input, long expectedSize, String relative) throws IOException {
        return BoundedArchiveEntry.sha256Exact(
                input,
                expectedSize,
                MAX_LEGAL_FILE_BYTES,
                "Application JAR legal file " + relative);
    }

    static Set<String> resolvedThirdPartyPurls(Path sbom) throws IOException {
        String contents = readBounded(sbom, MAX_SBOM_BYTES, "CycloneDX Maven SBOM");
        return resolvedThirdPartyPurls(contents);
    }

    static Set<String> resolvedThirdPartyPurls(String contents) throws IOException {
        Set<String> purls = NativePackageVerifier.directMavenComponentPurls(contents).stream()
                .filter(purl -> !purl.startsWith(FIRST_PARTY_PREFIX))
                .collect(Collectors.toUnmodifiableSet());
        if (purls.isEmpty()) {
            throw new IOException("CycloneDX Maven SBOM has no third-party component purls");
        }
        return purls;
    }

    private static Map<String, Set<String>> readCatalog(Path legalRoot) throws IOException {
        String contents = readBounded(
                legalRoot.resolve(CATALOG), 1024 * 1024, "reviewed legal catalog");
        Map<String, Set<String>> catalog = new LinkedHashMap<>();
        for (String line : contents.lines().toList()) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] columns = line.split("\\|", -1);
            if (columns.length != 2
                    || !columns[0].startsWith("pkg:maven/")
                    || columns[0].contains("\\")
                    || columns[1].isBlank()) {
                throw new IOException("Malformed reviewed legal catalog row: " + line);
            }
            Set<String> licenses = new TreeSet<>();
            for (String license : columns[1].split(",", -1)) {
                if (license.isBlank()
                        || !license.startsWith("licenses/")
                        || !license.endsWith(".txt")
                        || !licenses.add(license)) {
                    throw new IOException("Malformed reviewed license mapping: " + line);
                }
            }
            if (catalog.putIfAbsent(columns[0], Set.copyOf(licenses)) != null) {
                throw new IOException(
                        "Duplicate reviewed Maven component: " + columns[0]);
            }
        }
        if (catalog.isEmpty()) {
            throw new IOException("Reviewed legal catalog is empty");
        }
        return Map.copyOf(catalog);
    }

    private static Path safeLegalFile(Path legalRoot, String relative) throws IOException {
        if (!NativeBundleResolver.isSafeRelativePath(relative)) {
            throw new IOException("Unsafe reviewed legal path: " + relative);
        }
        Path root = legalRoot.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("Reviewed legal path escapes its root: " + relative);
        }
        return file;
    }

    static String readBounded(Path file, long maximum, String description)
            throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular non-symlink file: " + file);
        }
        long size = Files.size(file);
        if (size <= 0 || size > maximum) {
            throw new IOException(description + " has invalid size " + size);
        }
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
