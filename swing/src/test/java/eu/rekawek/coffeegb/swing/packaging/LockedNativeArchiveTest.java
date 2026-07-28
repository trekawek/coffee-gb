package eu.rekawek.coffeegb.swing.packaging;

import org.apache.commons.compress.archivers.zip.UnixStat;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipMethod;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

public class LockedNativeArchiveTest {

    private static final byte[] CONTENTS =
            "locked-native".getBytes(StandardCharsets.UTF_8);
    private static final String RESOURCE = "native/linux/libexample.so";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void verifiesAndOpensAnExactStoredArchive() throws Exception {
        NativeBundleManifest manifest = manifest(List.of(entry(RESOURCE, CONTENTS)));
        Path archive = temporaryFolder.newFile("native-source.zip").toPath();
        writeArchive(
                archive,
                List.of(storedFile(RESOURCE, CONTENTS)));

        NativeResourceSource source = NativeResourceSource.archive(archive, manifest);

        try (InputStream input = source.open(RESOURCE).orElseThrow()) {
            assertArrayEquals(CONTENTS, input.readAllBytes());
        }
        assertFalse(source.open("native/linux/missing.so").isPresent());
    }

    @Test
    public void rejectsUnsafeDuplicateUnexpectedAndCompressedEntries() throws Exception {
        NativeBundleManifest manifest = manifest(List.of(entry(RESOURCE, CONTENTS)));

        Path unsafe = temporaryFolder.newFile("unsafe.zip").toPath();
        writeArchive(unsafe, List.of(storedFile("../escape.so", CONTENTS)));
        assertThrows(IOException.class, () -> LockedNativeArchive.verify(unsafe, manifest));

        Path duplicate = temporaryFolder.newFile("duplicate.zip").toPath();
        writeArchive(
                duplicate,
                List.of(
                        storedFile(RESOURCE, CONTENTS),
                        storedFile(RESOURCE, CONTENTS)));
        assertThrows(IOException.class, () -> LockedNativeArchive.verify(duplicate, manifest));

        Path unexpected = temporaryFolder.newFile("unexpected.zip").toPath();
        writeArchive(
                unexpected,
                List.of(
                        storedFile(RESOURCE, CONTENTS),
                        storedFile("native/linux/foreign.so", CONTENTS)));
        assertThrows(IOException.class, () -> LockedNativeArchive.verify(unexpected, manifest));

        Path compressed = temporaryFolder.newFile("compressed.zip").toPath();
        writeArchive(
                compressed,
                List.of(new TestArchiveEntry(
                        RESOURCE,
                        CONTENTS,
                        ZipMethod.DEFLATED.getCode(),
                        UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM)));
        assertThrows(IOException.class, () -> LockedNativeArchive.verify(compressed, manifest));
    }

    @Test
    public void rejectsSymlinksWrongDigestsAndNonManifestOrder() throws Exception {
        NativeBundleEntry first = entry(RESOURCE, CONTENTS);
        byte[] secondContents = "second-native".getBytes(StandardCharsets.UTF_8);
        NativeBundleEntry second =
                entry("native/linux/libsecond.so", secondContents);
        NativeBundleManifest manifest = manifest(List.of(first, second));

        Path symlink = temporaryFolder.newFile("symlink.zip").toPath();
        writeArchive(
                symlink,
                List.of(
                        new TestArchiveEntry(
                                RESOURCE,
                                CONTENTS,
                                ZipMethod.STORED.getCode(),
                                UnixStat.LINK_FLAG | UnixStat.DEFAULT_LINK_PERM),
                        storedFile(second.resourcePath(), secondContents)));
        assertThrows(IOException.class, () -> LockedNativeArchive.verify(symlink, manifest));

        Path altered = temporaryFolder.newFile("altered.zip").toPath();
        byte[] wrong = CONTENTS.clone();
        wrong[0] ^= 1;
        writeArchive(
                altered,
                List.of(
                        storedFile(RESOURCE, wrong),
                        storedFile(second.resourcePath(), secondContents)));
        assertThrows(IOException.class, () -> LockedNativeArchive.verify(altered, manifest));

        Path reordered = temporaryFolder.newFile("reordered.zip").toPath();
        writeArchive(
                reordered,
                List.of(
                        storedFile(second.resourcePath(), secondContents),
                        storedFile(RESOURCE, CONTENTS)));
        assertThrows(IOException.class, () -> LockedNativeArchive.verify(reordered, manifest));
    }

    @Test
    public void rejectsManifestEntryCountAndExpandedSizeBeyondBounds() throws Exception {
        List<NativeBundleEntry> tooMany = new ArrayList<>();
        List<TestArchiveEntry> archived = new ArrayList<>();
        for (int index = 0; index < 65; index++) {
            byte[] contents = {(byte) index};
            String resource = "native/linux/entry-" + index + ".so";
            tooMany.add(entry(resource, contents));
            archived.add(storedFile(resource, contents));
        }
        Path many = temporaryFolder.newFile("many.zip").toPath();
        writeArchive(many, archived);
        assertThrows(
                IOException.class,
                () -> LockedNativeArchive.verify(many, manifest(tooMany)));

        NativeBundleEntry oversized = new NativeBundleEntry(
                NativeComponent.JNA_DISPATCH,
                RESOURCE,
                "lib/libexample.so",
                64L * 1024L * 1024L + 1,
                NativeBundleManifest.sha256(CONTENTS));
        Path oversizedArchive = temporaryFolder.newFile("oversized.zip").toPath();
        writeArchive(
                oversizedArchive,
                List.of(storedFile(RESOURCE, CONTENTS)));
        assertThrows(
                IOException.class,
                () -> LockedNativeArchive.verify(
                        oversizedArchive, manifest(List.of(oversized))));
    }

    private static NativeBundleManifest manifest(List<NativeBundleEntry> entries) {
        return new NativeBundleManifest(
                NativeTarget.LINUX_X86_64,
                GamepadNativeSupport.BUNDLED,
                entries);
    }

    private static NativeBundleEntry entry(String resource, byte[] contents) {
        String fileName = resource.substring(resource.lastIndexOf('/') + 1);
        return new NativeBundleEntry(
                NativeComponent.JNA_DISPATCH,
                resource,
                "lib/" + fileName,
                contents.length,
                NativeBundleManifest.sha256(contents));
    }

    private static TestArchiveEntry storedFile(String name, byte[] contents) {
        return new TestArchiveEntry(
                name,
                contents,
                ZipMethod.STORED.getCode(),
                UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
    }

    private static void writeArchive(Path output, List<TestArchiveEntry> entries)
            throws Exception {
        try (ZipArchiveOutputStream archive = new ZipArchiveOutputStream(output)) {
            for (TestArchiveEntry testEntry : entries) {
                CRC32 crc = new CRC32();
                crc.update(testEntry.contents());
                ZipArchiveEntry entry = new ZipArchiveEntry(testEntry.name());
                entry.setMethod(testEntry.method());
                entry.setSize(testEntry.contents().length);
                entry.setCrc(crc.getValue());
                entry.setUnixMode(testEntry.unixMode());
                entry.setTime(946684800000L);
                archive.putArchiveEntry(entry);
                archive.write(testEntry.contents());
                archive.closeArchiveEntry();
            }
        }
    }

    private record TestArchiveEntry(
            String name, byte[] contents, int method, int unixMode) {
    }
}
