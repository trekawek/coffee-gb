package eu.rekawek.coffeegb.swing.packaging;

import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;

/** Linux installer metadata that must remain consistent with the release build floor. */
final class LinuxPackagePolicy {

    static final String DESKTOP_CATEGORY = "Game;";
    static final String PACKAGE_SECTION = "games";
    static final String REQUIRED_DEBIAN_AUDIO_PACKAGE = "libasound2t64";
    static final String RELEASE_BUILD_BASELINE = "Ubuntu 24.04 LTS";

    private LinuxPackagePolicy() {
    }

    static void verifyDesktopEntry(String contents) throws IOException {
        String category = uniqueValue(contents, "Categories");
        if (!DESKTOP_CATEGORY.equals(category)) {
            throw new IOException(
                    "Linux desktop Categories must be "
                            + DESKTOP_CATEGORY
                            + ", found "
                            + category);
        }
        if (contents.contains("DEPLOY_BUNDLE_CATEGORY")) {
            throw new IOException("Linux desktop entry retained an unexpanded category token");
        }
    }

    static void verifyDebianMetadata(String metadata) throws IOException {
        String section = uniqueField(metadata, "Section");
        if (!PACKAGE_SECTION.equals(section)) {
            throw new IOException(
                    "DEB Section must be " + PACKAGE_SECTION + ", found " + section);
        }
        String depends = uniqueField(metadata, "Depends");
        boolean hasRequiredAudioPackage =
                Arrays.stream(depends.split(","))
                        .map(String::strip)
                        .map(value -> value.split("\\s|\\(", 2)[0])
                        .map(value -> value.toLowerCase(Locale.ROOT))
                        .anyMatch(REQUIRED_DEBIAN_AUDIO_PACKAGE::equals);
        if (!hasRequiredAudioPackage) {
            throw new IOException(
                    "DEB built for "
                            + RELEASE_BUILD_BASELINE
                            + " must depend on "
                            + REQUIRED_DEBIAN_AUDIO_PACKAGE
                            + ", found "
                            + depends);
        }
    }

    private static String uniqueValue(String contents, String key) throws IOException {
        String prefix = key + "=";
        var values =
                contents.lines()
                        .filter(line -> line.startsWith(prefix))
                        .map(line -> line.substring(prefix.length()).strip())
                        .toList();
        if (values.size() != 1) {
            throw new IOException(
                    "Linux desktop entry must contain exactly one " + key + " value");
        }
        return values.get(0);
    }

    private static String uniqueField(String contents, String field) throws IOException {
        String prefix = field + ":";
        var values =
                contents.lines()
                        .filter(line -> line.startsWith(prefix))
                        .map(line -> line.substring(prefix.length()).strip())
                        .toList();
        if (values.size() != 1 || values.get(0).isBlank()) {
            throw new IOException("DEB metadata must contain exactly one " + field + " field");
        }
        return values.get(0);
    }
}
