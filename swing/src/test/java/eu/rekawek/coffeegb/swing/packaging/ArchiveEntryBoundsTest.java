package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class ArchiveEntryBoundsTest {

    @Test
    public void postPreflightConsumersRejectExpansionBeyondForgedDeclaredSizes()
            throws Exception {
        byte[] expected = "locked bytes".getBytes(StandardCharsets.UTF_8);
        byte[] expanded = "locked bytes plus hidden expansion".getBytes(StandardCharsets.UTF_8);

        assertThrows(
                java.io.IOException.class,
                () -> ThirdPartyNoticeInventory.embeddedLegalSha256(
                        new ByteArrayInputStream(expanded),
                        expected.length,
                        "licenses/example.txt"));

        NativeBundleEntry nativeEntry = new NativeBundleEntry(
                NativeComponent.JNA_DISPATCH,
                "native/test/library.so",
                "lib/library.so",
                expected.length,
                NativeBundleManifest.sha256(expected));
        assertThrows(
                java.io.IOException.class,
                () -> NativeReleaseTool.verifyPortableEntry(
                        new ByteArrayInputStream(expanded), nativeEntry));

        byte[] manifest = ("Manifest-Version: 1.0\r\n"
                        + "Implementation-Version: 1.7.15\r\n"
                        + "Main-Class: eu.rekawek.coffeegb.swing.Main\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8);
        byte[] expandedManifest = java.util.Arrays.copyOf(manifest, manifest.length + 1);
        assertThrows(
                java.io.IOException.class,
                () -> NativePackageStager.parseManifest(
                        new ByteArrayInputStream(expandedManifest),
                        manifest.length,
                        "forged manifest"));

        assertEquals(
                "1.7.15",
                NativePackageStager.parseManifest(
                                new ByteArrayInputStream(manifest),
                                manifest.length,
                                "bounded manifest")
                        .getMainAttributes()
                        .getValue("Implementation-Version"));
    }

    @Test
    public void portableInventoryRejectsDuplicateLockedNativePaths() {
        String path = "native/test/library.so";
        var entries = Collections.enumeration(
                List.of(new JarEntry(path), new JarEntry(path)));

        assertThrows(
                java.io.IOException.class,
                () -> NativeReleaseTool.verifyPortableEntryInventory(entries, Set.of(path)));
    }

    @Test
    public void manifestInventoryRejectsCaseInsensitiveAliases() {
        var entries = Collections.enumeration(List.of(
                new JarEntry("META-INF/MANIFEST.MF"),
                new JarEntry("meta-inf/manifest.mf")));

        assertThrows(
                java.io.IOException.class,
                () -> NativePackageStager.requireCanonicalManifest(entries, "portable JAR"));
    }
}
