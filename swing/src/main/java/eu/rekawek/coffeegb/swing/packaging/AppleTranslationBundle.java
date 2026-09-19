package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Stages the first-party Swift helper separately from third-party dependency natives. */
final class AppleTranslationBundle {

    static final String RELATIVE_PATH = "apple-translation/CoffeeGBTranslation.app";
    static final String EXECUTABLE = "Contents/MacOS/CoffeeGBTranslation";
    private static final String INFO = "Contents/Info.plist";
    private static final Set<String> FILES = Set.of(
            INFO, EXECUTABLE, "Contents/PkgInfo", "Contents/_CodeSignature/CodeResources");
    private static final Set<String> DIRECTORIES = Set.of(
            "", "Contents", "Contents/MacOS", "Contents/_CodeSignature");
    private static final long MAX_EXECUTABLE_BYTES = 32L * 1024 * 1024;

    private AppleTranslationBundle() {
    }

    static void stage(Path source, Path input, NativeTarget target, Map<String, String> inventory)
            throws IOException {
        verify(source, target);
        Path destination = input.resolve(RELATIVE_PATH);
        Files.createDirectories(destination.getParent());
        try (Stream<Path> stream = Files.walk(source)) {
            for (Path path : stream.sorted().toList()) {
                Path output = destination.resolve(source.relativize(path));
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(output);
                } else {
                    Files.copy(path, output, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
        verify(destination, target);
        inventory.put("translation.apple.bundle", RELATIVE_PATH);
        inventory.put("translation.apple.protocol", "1");
        inventory.put("translation.apple.minimum-macos", "15.0");
        inventory.put("translation.apple.architecture", architecture(target));
        inventory.put("translation.apple.plist.sha256", NativePackageStager.sha256(destination.resolve(INFO)));
        // codesign may replace the Mach-O signature during outer app packaging. The stage digest
        // records build provenance; the final distribution checksum seals the signed executable.
        inventory.put("translation.apple.staged-executable.sha256",
                NativePackageStager.sha256(destination.resolve(EXECUTABLE)));
    }

    static void verifyPackaged(Path input, NativeTarget target, Map<String, String> inventory)
            throws IOException {
        boolean present = Files.exists(input.resolve("apple-translation"), LinkOption.NOFOLLOW_LINKS);
        boolean declared = inventory.keySet().stream().anyMatch(key -> key.startsWith("translation.apple."));
        if (!present && !declared) {
            // Host-independent stage tests and legacy package inspection do not require a Swift build.
            return;
        }
        require(inventory, "translation.apple.bundle", RELATIVE_PATH);
        require(inventory, "translation.apple.protocol", "1");
        require(inventory, "translation.apple.minimum-macos", "15.0");
        require(inventory, "translation.apple.architecture", architecture(target));
        Path bundle = input.resolve(RELATIVE_PATH);
        verify(bundle, target);
        require(inventory, "translation.apple.plist.sha256", NativePackageStager.sha256(bundle.resolve(INFO)));
        if (!inventory.getOrDefault("translation.apple.staged-executable.sha256", "").matches("[0-9a-f]{64}")) {
            throw new IOException("Apple translation helper has no staged executable digest");
        }
    }

    static void verify(Path bundle, NativeTarget target) throws IOException {
        String architecture = architecture(target);
        if (!Files.isDirectory(bundle, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(bundle)) {
            throw new IOException("Apple translation helper must be a non-symlink app bundle");
        }
        try (Stream<Path> stream = Files.walk(bundle, 5)) {
            List<Path> paths = stream.limit(16).toList();
            if (paths.size() == 16) {
                throw new IOException("Apple translation helper contains too many entries");
            }
            for (Path path : paths) {
                String relative = bundle.relativize(path).toString().replace('\\', '/');
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Apple translation helper contains a symlink");
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    if (!DIRECTORIES.contains(relative)) {
                        throw new IOException("Unexpected Apple translation helper directory: " + relative);
                    }
                } else if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                        || !FILES.contains(relative)
                        || Files.size(path) < 1
                        || Files.size(path) > (relative.equals(EXECUTABLE) ? MAX_EXECUTABLE_BYTES : 64 * 1024)) {
                    throw new IOException("Invalid Apple translation helper file: " + relative);
                }
            }
        }
        Path executable = bundle.resolve(EXECUTABLE);
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS) || !Files.isExecutable(executable)) {
            throw new IOException("Apple translation helper executable is missing or not executable");
        }
        try (InputStream input = Files.newInputStream(executable)) {
            byte[] header = input.readNBytes(32);
            int cpu = architecture.equals("arm64") ? 0x0100000c : 0x01000007;
            if (header.length != 32) {
                throw new IOException("Truncated Apple translation Mach-O executable");
            }
            ByteBuffer buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
            if (buffer.getInt(0) != 0xfeedfacf || buffer.getInt(4) != cpu || buffer.getInt(12) != 2) {
                throw new IOException("Apple translation helper is not a " + architecture + " Mach-O executable");
            }
        }
        Path info = bundle.resolve(INFO);
        if (!Files.isRegularFile(info, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Apple translation helper Info.plist is missing");
        }
        String plist = Files.readString(info, StandardCharsets.UTF_8);
        requirePlist(plist, "CFBundleExecutable", "CoffeeGBTranslation");
        requirePlist(plist, "CFBundleIdentifier", "eu.rekawek.coffeegb.translation");
        requirePlist(plist, "LSMinimumSystemVersion", "15.0");
        requirePlist(plist, "CFBundlePackageType", "APPL");
    }

    private static void requirePlist(String plist, String key, String value) throws IOException {
        if (!Pattern.compile("<key>" + Pattern.quote(key) + "</key>\\s*<string>"
                + Pattern.quote(value) + "</string>").matcher(plist).find()) {
            throw new IOException("Apple translation helper Info.plist must declare " + key + "=" + value);
        }
    }

    private static void require(Map<String, String> inventory, String key, String value) throws IOException {
        if (!value.equals(inventory.get(key))) {
            throw new IOException("Apple translation helper manifest mismatch: " + key);
        }
    }

    static String architecture(NativeTarget target) throws IOException {
        return switch (target) {
            case MACOS_AARCH64 -> "arm64";
            case MACOS_X86_64 -> "x86_64";
            default -> throw new IOException("Apple translation helper is only supported in macOS packages");
        };
    }
}
