package eu.rekawek.coffeegb.android;

import android.content.SharedPreferences;

/**
 * Defensive reader for the Android bootstrap preference.
 *
 * <p>Older installs did not persist a bootstrap choice.  Those installs should receive the
 * bounded fast-forward path, while an explicitly malformed value (including a value saved with
 * the wrong SharedPreferences type) fails closed to the safe SKIP path.</p>
 */
final class AndroidBootstrapPreference {

    static final String SKIP = "skip";
    static final String FAST_FORWARD = "fast-forward";
    static final String FULL = "full";

    private AndroidBootstrapPreference() {
    }

    static String read(SharedPreferences preferences, String key) {
        if (preferences == null || key == null || key.isBlank()) {
            return SKIP;
        }
        try {
            if (!preferences.contains(key)) {
                return FAST_FORWARD;
            }
            return parse(preferences.getString(key, null), true);
        } catch (RuntimeException error) {
            // A wrong-typed preference must not abort activity startup.  Treat it like an
            // explicitly invalid value and keep the emulator on its safe post-boot path.
            return SKIP;
        }
    }

    /** Pure parser seam used by JVM tests and by callers that already know key presence. */
    static String parse(String value, boolean present) {
        if (!present) {
            return FAST_FORWARD;
        }
        if (value == null) {
            return SKIP;
        }
        return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "skip", "skipped" -> SKIP;
            case "fast_forward", "fast-forward", "fastforward", "ff" -> FAST_FORWARD;
            case "full", "normal", "authentic" -> FULL;
            default -> SKIP;
        };
    }
}
