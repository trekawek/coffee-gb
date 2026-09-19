package eu.rekawek.coffeegb.swing.packaging;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

public class AppleTranslationBundleTest {

    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void stagesExecutableBundleAndRecordsItsInventoryForBothArchitectures() throws Exception {
        for (NativeTarget target : new NativeTarget[] {NativeTarget.MACOS_AARCH64, NativeTarget.MACOS_X86_64}) {
            Path input = temporary.newFolder(target.id()).toPath();
            Path source = helper(target);
            Map<String, String> inventory = new HashMap<>();
            AppleTranslationBundle.stage(source, input, target, inventory);
            Path staged = input.resolve(AppleTranslationBundle.RELATIVE_PATH);
            assertTrue(Files.isExecutable(staged.resolve(AppleTranslationBundle.EXECUTABLE)));
            assertEquals(NativePackageStager.sha256(source.resolve(AppleTranslationBundle.EXECUTABLE)),
                    inventory.get("translation.apple.staged-executable.sha256"));
            AppleTranslationBundle.verifyPackaged(input, target, inventory);
        }
    }

    @Test
    public void rejectsForeignArchitectureAndNonMacTarget() throws Exception {
        Path source = helper(NativeTarget.MACOS_AARCH64);
        assertThrows(IOException.class, () -> AppleTranslationBundle.verify(source, NativeTarget.MACOS_X86_64));
        assertThrows(IOException.class, () -> AppleTranslationBundle.verify(source, NativeTarget.LINUX_X86_64));
    }

    @Test
    public void rejectsMissingExecutableAndUnexpectedFiles() throws Exception {
        Path source = helper(NativeTarget.MACOS_AARCH64);
        Path executable = source.resolve(AppleTranslationBundle.EXECUTABLE);
        Files.delete(executable);
        assertThrows(IOException.class, () -> AppleTranslationBundle.verify(source, NativeTarget.MACOS_AARCH64));
        Path another = helper(NativeTarget.MACOS_AARCH64);
        Files.writeString(another.resolve("Contents/leaked.key"), "unexpected content");
        assertThrows(IOException.class, () -> AppleTranslationBundle.verify(another, NativeTarget.MACOS_AARCH64));
    }

    @Test
    public void rejectsNonExecutableOrSymlinkedContent() throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path source = helper(NativeTarget.MACOS_AARCH64);
        Path executable = source.resolve(AppleTranslationBundle.EXECUTABLE);
        assertTrue(executable.toFile().setExecutable(false, false));
        assertThrows(IOException.class, () -> AppleTranslationBundle.verify(source, NativeTarget.MACOS_AARCH64));
        assertTrue(executable.toFile().setExecutable(true));
        Files.createSymbolicLink(source.resolve("Contents/alias"), executable);
        assertThrows(IOException.class, () -> AppleTranslationBundle.verify(source, NativeTarget.MACOS_AARCH64));
    }

    @Test
    public void rejectsPlistTamperingAndUndeclaredOrMissingPackagedHelper() throws Exception {
        Path input = temporary.newFolder("input").toPath();
        Map<String, String> inventory = new HashMap<>();
        AppleTranslationBundle.stage(helper(NativeTarget.MACOS_AARCH64), input, NativeTarget.MACOS_AARCH64, inventory);
        assertThrows(IOException.class, () -> AppleTranslationBundle.verifyPackaged(input, NativeTarget.MACOS_AARCH64, Map.of()));
        Path empty = temporary.newFolder("empty").toPath();
        assertThrows(IOException.class, () -> AppleTranslationBundle.verifyPackaged(empty, NativeTarget.MACOS_AARCH64, inventory));
        Path plist = input.resolve(AppleTranslationBundle.RELATIVE_PATH).resolve("Contents/Info.plist");
        Files.writeString(plist, Files.readString(plist) + "<!-- changed -->");
        assertThrows(IOException.class, () -> AppleTranslationBundle.verifyPackaged(input, NativeTarget.MACOS_AARCH64, inventory));
    }

    @Test
    public void hostIndependentStagingCanOmitNativeHelper() throws Exception {
        Path input = temporary.newFolder("java-only").toPath();
        AppleTranslationBundle.verifyPackaged(input, NativeTarget.MACOS_AARCH64, Map.of());
        AppleTranslationBundle.verifyPackaged(input, NativeTarget.LINUX_X86_64, Map.of());
    }

    private Path helper(NativeTarget target) throws Exception {
        Path bundle = temporary.newFolder().toPath().resolve("CoffeeGBTranslation.app");
        Files.createDirectories(bundle.resolve("Contents/MacOS"));
        byte[] header = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xfeedfacf)
                .putInt(target == NativeTarget.MACOS_AARCH64 ? 0x0100000c : 0x01000007)
                .putInt(0).putInt(2).array();
        Path executable = bundle.resolve(AppleTranslationBundle.EXECUTABLE);
        Files.write(executable, header);
        assertTrue(executable.toFile().setExecutable(true));
        Files.writeString(bundle.resolve("Contents/Info.plist"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <plist version="1.0"><dict>
                <key>CFBundleExecutable</key><string>CoffeeGBTranslation</string>
                <key>CFBundleIdentifier</key><string>eu.rekawek.coffeegb.translation</string>
                <key>CFBundlePackageType</key><string>APPL</string>
                <key>LSMinimumSystemVersion</key><string>15.0</string>
                </dict></plist>
                """);
        return bundle;
    }
}
