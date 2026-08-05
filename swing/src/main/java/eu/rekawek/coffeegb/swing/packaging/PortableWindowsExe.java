package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Objects;

/**
 * Produces the single-file Windows release artifact from a verified jpackage application image.
 *
 * <p>The standard 7-Zip SFX module extracts the image to a temporary directory and immediately
 * starts the regular GUI launcher. It is deliberately not an installer: it creates no shortcuts,
 * uninstall entry, or file association.
 */
final class PortableWindowsExe {

    static final String SEVEN_ZIP_COMMAND = "COFFEE_GB_7ZIP_COMMAND";
    private static final String SFX_MODULE = "7z.sfx";
    private static final byte[] CONFIGURATION = (
            ";!@Install@!UTF-8!\r\n"
                    + "Title=\"Coffee GB\"\r\n"
                    + "RunProgram=\"Coffee GB\\Coffee GB.exe\"\r\n"
                    + ";!@InstallEnd@!\r\n")
            .getBytes(StandardCharsets.UTF_8);

    private PortableWindowsExe() {
    }

    static SevenZip requireSevenZip(Map<String, String> environment) throws IOException {
        Objects.requireNonNull(environment, "environment");
        String configured = environment.get(SEVEN_ZIP_COMMAND);
        if (configured == null || configured.isBlank()) {
            throw new IOException(
                    "Windows portable EXE packaging requires " + SEVEN_ZIP_COMMAND
                            + " to name 7z.exe");
        }
        Path executable = Path.of(configured).toAbsolutePath().normalize();
        requireRegularFile(executable, SEVEN_ZIP_COMMAND);
        Path module = executable.resolveSibling(SFX_MODULE);
        requireRegularFile(module, "7-Zip SFX module");
        return new SevenZip(executable, module);
    }

    static void assemble(Path sfxModule, Path archive, Path output) throws IOException {
        requireRegularFile(sfxModule, "7-Zip SFX module");
        requireRegularFile(archive, "portable application archive");
        Path destination = output.toAbsolutePath().normalize();
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Portable Windows EXE already exists: " + destination);
        }
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Portable Windows EXE parent is not a directory: " + parent);
        }
        try (OutputStream target = Files.newOutputStream(
                        destination,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                InputStream module = Files.newInputStream(sfxModule);
                InputStream payload = Files.newInputStream(archive)) {
            module.transferTo(target);
            target.write(CONFIGURATION);
            payload.transferTo(target);
        }
        requireRegularFile(destination, "portable Windows EXE");
    }

    private static void requireRegularFile(Path file, String description) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is not a regular non-symlink file: " + file);
        }
        if (Files.size(file) == 0) {
            throw new IOException(description + " is empty: " + file);
        }
    }

    record SevenZip(Path executable, Path sfxModule) {
    }
}
