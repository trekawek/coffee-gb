package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertThrows;

public class NativeReleaseToolTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void completeReleaseMatrixAndChecksumsAreRequired() throws Exception {
        Path release = temporaryFolder.newFolder("release").toPath();
        Map<String, String> matrix = new LinkedHashMap<>();
        matrix.put("schema", "4");
        matrix.put("app.version", "1.7.15");
        matrix.put("source.commit", "0123456789abcdef0123456789abcdef01234567");
        addFile(release, matrix, "portable", "coffee-gb-1.7.15.jar");
        matrix.put("sbom.sha256", "0".repeat(64));
        for (NativeTarget target : NativeTarget.values()) {
            String prefix = "target." + target.id();
            String suffix =
                    NativePackageMetadata.target(target).defaultPackageType().id();
            String artifactName =
                    "coffee-gb-1.7.15-" + target.id() + "." + suffix;
            addFile(release, matrix, prefix, artifactName);
            Path artifact = release.resolve(artifactName);
            String signing = target == NativeTarget.LINUX_X86_64
                    ? "verified-detached"
                    : "unsigned";
            matrix.put(prefix + ".native-sbom.sha256", "1".repeat(64));
            matrix.put(prefix + ".signing", signing);
            if (target == NativeTarget.LINUX_X86_64) {
                addFile(
                        release,
                        matrix,
                        prefix + ".signature",
                        artifactName + ".asc");
            }
        }
        writeProperties(release.resolve(NativeReleaseTool.MATRIX_FILE), matrix);
        writeChecksums(release);

        NativeReleaseTool.verifyReleaseDirectory(release, "1.7.15");

        Path unexpectedJson = Files.writeString(
                release.resolve("unexpected.json"), "{}", StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativeReleaseTool.verifyReleaseDirectory(release, "1.7.15"));
        Files.delete(unexpectedJson);

        Files.writeString(
                release.resolve(matrix.get("target.macos-aarch64.path")),
                "corrupt",
                StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativeReleaseTool.verifyReleaseDirectory(release, "1.7.15"));
    }

    @Test
    public void releaseChecksumMetadataIsBoundedBeforeParsing() throws Exception {
        Path release = temporaryFolder.newFolder("oversized-release-checksums").toPath();
        Path checksums = release.resolve("SHA256SUMS");
        try (var channel = Files.newByteChannel(
                checksums,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.position(NativePackageVerifier.MAX_METADATA_BYTES);
            channel.write(ByteBuffer.wrap(new byte[] {1}));
        }

        assertThrows(
                java.io.IOException.class,
                () -> NativeReleaseTool.verifyReleaseChecksums(release));
    }

    @Test
    public void releaseDirectoryEntryEnumerationIsBounded() throws Exception {
        Path release = temporaryFolder.newFolder("excess-release-entries").toPath();
        for (int i = 0; i <= NativeReleaseTool.MAX_RELEASE_DIRECTORY_ENTRIES; i++) {
            Files.writeString(
                    release.resolve("entry-" + i), "bounded", StandardCharsets.UTF_8);
        }

        assertThrows(
                java.io.IOException.class,
                () -> NativeReleaseTool.listBoundedReleaseEntries(release));
    }

    private static void addFile(
            Path root, Map<String, String> matrix, String prefix, String filename)
            throws Exception {
        Path file = Files.writeString(
                root.resolve(filename), "synthetic " + prefix, StandardCharsets.UTF_8);
        matrix.put(prefix + ".path", filename);
        matrix.put(prefix + ".sha256", NativePackageStager.sha256(file));
    }

    private static void writeProperties(Path file, Map<String, String> values)
            throws Exception {
        StringBuilder contents = new StringBuilder();
        values.forEach((key, value) ->
                contents.append(key).append('=').append(value).append('\n'));
        Files.writeString(file, contents.toString(), StandardCharsets.UTF_8);
    }

    private static void writeChecksums(Path root) throws Exception {
        StringBuilder contents = new StringBuilder();
        try (Stream<Path> paths = Files.list(root)) {
            for (Path file : paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList()) {
                contents.append(NativePackageStager.sha256(file))
                        .append("  ")
                        .append(file.getFileName())
                        .append('\n');
            }
        }
        Files.writeString(
                root.resolve("SHA256SUMS"), contents.toString(), StandardCharsets.UTF_8);
    }
}
