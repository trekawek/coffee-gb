package eu.rekawek.coffeegb.android;

import java.util.Locale;

/** Filename filter shared by Android's ROM picker/import path and its regression tests. */
final class RomDocumentFilter {

    private RomDocumentFilter() {
    }

    static boolean accepts(String displayName) {
        String extension = extension(displayName);
        return extension.equals("gb") || extension.equals("gbc")
                || extension.equals("rom") || extension.equals("zip");
    }

    static String extension(String displayName) {
        if (displayName == null) {
            return "";
        }
        int separator = displayName.lastIndexOf('.');
        if (separator < 0 || separator == displayName.length() - 1) {
            return "";
        }
        return displayName.substring(separator + 1).toLowerCase(Locale.ROOT);
    }
}
