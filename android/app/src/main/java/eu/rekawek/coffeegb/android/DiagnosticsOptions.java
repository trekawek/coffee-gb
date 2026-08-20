package eu.rekawek.coffeegb.android;

import android.content.Intent;

import java.util.Locale;

/**
 * Process-local launch options for the minified benchmark APK.
 *
 * <p>The parser deliberately accepts only a small, typed allow-list.  The release APK compiles
 * this entire path with {@link BuildConfig#DIAGNOSTICS_ENABLED} set to {@code false}, so ordinary
 * launches cannot enable the benchmark behavior.  Options are kept in memory and are never
 * copied into emulator settings or preferences.</p>
 */
final class DiagnosticsOptions {

    static final String EXTRA_BENCHMARK = "coffee_gb_benchmark";
    static final String EXTRA_HARDWARE = "coffee_gb_hardware";
    static final String EXTRA_AUDIO = "coffee_gb_audio";
    static final String EXTRA_RENDER = "coffee_gb_render";
    static final String EXTRA_WARMUP = "coffee_gb_warmup";
    static final String EXTRA_RECENT = "coffee_gb_recent";

    enum Hardware {
        AUTO,
        DMG
    }

    enum Render {
        PRESENTATION,
        FRAME_SINK
    }

    private static final DiagnosticsOptions DISABLED = new DiagnosticsOptions(
            false, Hardware.AUTO, true, Render.PRESENTATION, false, false);

    final boolean enabled;
    final Hardware hardware;
    final boolean audioOutput;
    final Render render;
    final boolean runtimeWarmup;
    final boolean launchRecent;

    private DiagnosticsOptions(boolean enabled, Hardware hardware, boolean audioOutput,
            Render render, boolean runtimeWarmup, boolean launchRecent) {
        this.enabled = enabled;
        this.hardware = hardware;
        this.audioOutput = audioOutput;
        this.render = render;
        this.runtimeWarmup = runtimeWarmup;
        this.launchRecent = launchRecent;
    }

    static DiagnosticsOptions disabled() {
        return DISABLED;
    }

    static DiagnosticsOptions fromIntent(Intent intent) {
        return parse(BuildConfig.DIAGNOSTICS_ENABLED, intent);
    }

    static DiagnosticsOptions parse(boolean diagnosticsEnabled, Intent intent) {
        if (!diagnosticsEnabled) {
            return DISABLED;
        }

        // The benchmark variant is diagnostic by default so a bare `am start` is repeatable.
        // --ez coffee_gb_benchmark false remains a useful escape hatch for UI smoke checks.
        boolean enabled = booleanExtra(intent, EXTRA_BENCHMARK, true);
        if (!enabled) {
            return DISABLED;
        }
        return parseValues(true, stringExtra(intent, EXTRA_HARDWARE),
                extraValue(intent, EXTRA_AUDIO), stringExtra(intent, EXTRA_RENDER),
                booleanExtra(intent, EXTRA_WARMUP, false),
                booleanExtra(intent, EXTRA_RECENT, true),
                booleanExtra(intent, "coffee_gb_frame_sink", false));
    }

    static DiagnosticsOptions parseValues(boolean diagnosticsEnabled, String hardwareValue,
            Object audioValue, String renderValue, boolean warmup, boolean recent,
            boolean frameSink) {
        if (!diagnosticsEnabled) {
            return DISABLED;
        }
        Hardware hardware = parseHardware(hardwareValue);
        boolean audio = booleanValue(audioValue, true);
        Render render = parseRender(renderValue);
        if (frameSink) {
            render = Render.FRAME_SINK;
        }
        return new DiagnosticsOptions(true, hardware, audio, render, warmup, recent);
    }

    private static Hardware parseHardware(String value) {
        if (value == null || value.isBlank() || "auto".equalsIgnoreCase(value)) {
            return Hardware.AUTO;
        }
        return "dmg".equalsIgnoreCase(value) || "forced-dmg".equalsIgnoreCase(value)
                ? Hardware.DMG : Hardware.AUTO;
    }

    private static Render parseRender(String value) {
        if (value == null || value.isBlank()) {
            return Render.PRESENTATION;
        }
        return "sink".equalsIgnoreCase(value) || "diagnostic".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value) ? Render.FRAME_SINK
                : Render.PRESENTATION;
    }

    private static String stringExtra(Intent intent, String key) {
        if (intent == null || !intent.hasExtra(key)) {
            return null;
        }
        Object value = extraValue(intent, key);
        return value == null ? null : String.valueOf(value).trim().toLowerCase(Locale.ROOT);
    }

    private static Object extraValue(Intent intent, String key) {
        return intent == null || intent.getExtras() == null ? null : intent.getExtras().get(key);
    }

    private static boolean booleanExtra(Intent intent, String key, boolean defaultValue) {
        if (intent == null || !intent.hasExtra(key)) {
            return defaultValue;
        }
        Object value = extraValue(intent, key);
        return booleanValue(value, defaultValue);
    }

    private static boolean booleanValue(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value != null) {
            String text = String.valueOf(value).trim();
            if ("true".equalsIgnoreCase(text) || "on".equalsIgnoreCase(text)
                    || "yes".equalsIgnoreCase(text) || "1".equals(text)) {
                return true;
            }
            if ("false".equalsIgnoreCase(text) || "off".equalsIgnoreCase(text)
                    || "no".equalsIgnoreCase(text) || "0".equals(text)) {
                return false;
            }
        }
        return defaultValue;
    }
}
