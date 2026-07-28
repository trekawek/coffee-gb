package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class NativePackageVerifierTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void distributionResultAndExactChecksumCoverageRoundTrip() throws Exception {
        Path dist = temporaryFolder.newFolder("dist").toPath();
        Path installer = Files.writeString(
                dist.resolve("coffee-gb.deb"), "synthetic installer", StandardCharsets.UTF_8);
        Path sbom = Files.writeString(
                dist.resolve("coffee-gb-1.7.15-sbom.cdx.json"),
                "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
                        + "\"metadata\":{\"component\":{\"version\":\"1.7.15\"}}}",
                StandardCharsets.UTF_8);
        NativePackageVerifier.writeBuildResult(
                dist,
                NativeTarget.LINUX_X86_64,
                NativePackageMetadata.PackageType.DEB,
                "1.7.15",
                installer,
                sbom,
                false);
        writeChecksums(dist);

        assertEquals(
                "unsigned",
                NativePackageVerifier.verifyDistribution(
                                dist,
                                NativeTarget.LINUX_X86_64,
                                NativePackageMetadata.PackageType.DEB,
                                "1.7.15")
                        .get("signing"));

        Files.writeString(dist.resolve("unlisted.key"), "secret", StandardCharsets.UTF_8);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.verifyDistribution(
                        dist,
                        NativeTarget.LINUX_X86_64,
                        NativePackageMetadata.PackageType.DEB,
                        "1.7.15"));
    }

    @Test
    public void strictPropertiesRejectDuplicateKeys() throws Exception {
        Path properties = temporaryFolder.newFile("duplicate.properties").toPath();
        Files.writeString(
                properties,
                "schema=1\nschema=2\n",
                StandardCharsets.UTF_8);

        assertThrows(
                java.io.IOException.class,
                () -> NativePackageVerifier.readStrictProperties(properties));
    }

    private static void writeChecksums(Path dist) throws Exception {
        List<Path> files;
        try (Stream<Path> paths = Files.list(dist)) {
            files = paths.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        StringBuilder contents = new StringBuilder();
        for (Path file : files) {
            contents.append(NativePackageStager.sha256(file))
                    .append("  ")
                    .append(file.getFileName())
                    .append('\n');
        }
        Files.writeString(
                dist.resolve("SHA256SUMS"), contents.toString(), StandardCharsets.UTF_8);
    }
}
