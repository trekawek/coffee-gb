package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.content.SharedPreferences;

/** App-private preferences for touch controls; no external-storage permission is involved. */
final class TouchControlsPreferences {

    private static final String PREFERENCES = "coffee-gb-touch-controls";
    private static final String OPACITY = "opacity";
    private static final String SCALE = "scale";
    private static final String VERTICAL_POSITION = "vertical-position";
    private static final String LEFT_HANDED = "left-handed";
    private static final String HAPTICS = "haptics";

    private final SharedPreferences preferences;

    TouchControlsPreferences(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    TouchControlsLayout load() {
        return new TouchControlsLayout(
                preferences.getFloat(OPACITY, TouchControlsLayout.DEFAULT_OPACITY),
                preferences.getFloat(SCALE, TouchControlsLayout.DEFAULT_SCALE),
                preferences.getFloat(VERTICAL_POSITION, TouchControlsLayout.DEFAULT_VERTICAL_POSITION),
                preferences.getBoolean(LEFT_HANDED, false),
                preferences.getBoolean(HAPTICS, true));
    }

    void save(TouchControlsLayout layout) {
        preferences.edit()
                .putFloat(OPACITY, layout.opacity())
                .putFloat(SCALE, layout.scale())
                .putFloat(VERTICAL_POSITION, layout.verticalPosition())
                .putBoolean(LEFT_HANDED, layout.leftHanded())
                .putBoolean(HAPTICS, layout.haptics())
                .apply();
    }

    void reset() {
        preferences.edit().clear().apply();
    }
}
