package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.text.Normalizer;

/** Produces an encoding-independent installer license from the canonical UTF-8 license. */
final class NativePackageLicense {

    static final String MACOS_LICENSE_TEMPLATE =
            NativePackageMetadata.APPLICATION_NAME + "-license.plist";
    static final String RTF_FILE_NAME = "coffee-gb-license.rtf";
    private static final String RTF_HEADER =
            "{\\rtf1\\ansi\\ansicpg1252\\deff0"
                    + "{\\fonttbl{\\f0\\fnil\\fcharset0 Arial;}}\n"
                    + "\\viewkind4\\uc1\\pard\\f0\\fs20 ";

    private NativePackageLicense() {
    }

    static Path prepare(
            Path canonicalLicense,
            Path stageRoot,
            Path jpackageResources,
            NativePackageMetadata.HostOs hostOs)
            throws IOException {
        String contents = readCanonicalUtf8(canonicalLicense);
        if (hostOs == NativePackageMetadata.HostOs.LINUX) {
            return canonicalLicense;
        }
        if (hostOs == NativePackageMetadata.HostOs.MACOS) {
            verifyMacOsTemplate(jpackageResources.resolve(MACOS_LICENSE_TEMPLATE));
        }
        Path licenseDirectory = Files.createDirectory(stageRoot.resolve("installer-license"));
        Path rtf = licenseDirectory.resolve(RTF_FILE_NAME);
        Files.writeString(rtf, toRtf(contents), StandardCharsets.US_ASCII);
        return rtf;
    }

    static String readCanonicalUtf8(Path license) throws IOException {
        if (Files.isSymbolicLink(license)
                || !Files.isRegularFile(license, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(
                    "Canonical license is not a regular non-symlink file: " + license);
        }
        byte[] bytes = Files.readAllBytes(license);
        if (bytes.length == 0 || bytes.length > 2L * 1024L * 1024L) {
            throw new IOException("Canonical license has invalid size " + bytes.length);
        }
        String contents;
        try {
            CharBuffer decoded = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes));
            contents = decoded.toString();
        } catch (CharacterCodingException malformed) {
            throw new IOException("Canonical license is not valid UTF-8", malformed);
        }
        if (!Normalizer.isNormalized(contents, Normalizer.Form.NFC)) {
            throw new IOException("Canonical license must use NFC-normalized Unicode");
        }
        if (!contents.contains(NativePackageMetadata.AUTHOR_NAME)) {
            throw new IOException(
                    "Canonical license does not contain the exact author name "
                            + NativePackageMetadata.AUTHOR_NAME);
        }
        if (contents.indexOf('\0') >= 0) {
            throw new IOException("Canonical license contains a NUL character");
        }
        return contents;
    }

    static String toRtf(String contents) {
        String normalizedLineEndings = contents.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder rtf = new StringBuilder(RTF_HEADER.length() + contents.length() * 2);
        rtf.append(RTF_HEADER);
        for (int index = 0; index < normalizedLineEndings.length(); index++) {
            char character = normalizedLineEndings.charAt(index);
            switch (character) {
                case '\\', '{', '}' -> rtf.append('\\').append(character);
                case '\n' -> rtf.append("\\line\n");
                case '\t' -> rtf.append("\\tab ");
                default -> {
                    if (character >= 0x20 && character <= 0x7e) {
                        rtf.append(character);
                    } else {
                        rtf.append("\\u").append((int) (short) character).append('?');
                    }
                }
            }
        }
        return rtf.append("}\n").toString();
    }

    private static void verifyMacOsTemplate(Path template) throws IOException {
        String contents = readAscii(template, "macOS RTF license template");
        if (!contents.contains("<key>RTF </key>")
                || !contents.contains("<data>APPLICATION_LICENSE_TEXT</data>")) {
            throw new IOException("macOS license template does not declare an RTF resource");
        }
    }

    private static String readAscii(Path file, String description) throws IOException {
        if (Files.isSymbolicLink(file)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(description + " is missing: " + file);
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length == 0 || bytes.length > 2L * 1024L * 1024L) {
            throw new IOException(description + " has invalid size " + bytes.length);
        }
        for (byte value : bytes) {
            if ((value & 0x80) != 0) {
                throw new IOException(description + " must contain ASCII bytes only");
            }
        }
        return new String(bytes, StandardCharsets.US_ASCII);
    }
}
