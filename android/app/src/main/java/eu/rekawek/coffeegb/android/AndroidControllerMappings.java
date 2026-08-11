package eu.rekawek.coffeegb.android;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;
import eu.rekawek.coffeegb.core.joypad.Button;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * App-private controller remaps keyed by the controller descriptor plus vendor and product ids.
 *
 * <p>The persisted key is a digest rather than the descriptor itself, so hardware identity never
 * appears in a preference backup or diagnostic string. A remap replaces another key targeting the
 * same logical button on that controller, making conflicts explicit and deterministic.
 */
final class AndroidControllerMappings {

    private static final String PREFERENCES = "coffee-gb-controller-mappings";
    private static final String KEYS = "keys.";
    private static final String BINDING = "binding.";
    private static final String INVERT_X = "invert-x.";
    private static final String INVERT_Y = "invert-y.";

    private final SharedPreferences preferences;

    AndroidControllerMappings(Context context) {
        preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    Button binding(InputDevice device, int keyCode, Button fallback) {
        String encoded = preferences.getString(BINDING + identity(device) + "." + keyCode, null);
        if (encoded == null) {
            return fallback;
        }
        if ("NONE".equals(encoded)) {
            return null;
        }
        try {
            return Button.valueOf(encoded);
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    void setBinding(InputDevice device, int keyCode, Button button,
                    Map<Integer, Button> defaults) {
        String identity = identity(device);
        Set<String> keys = new HashSet<>(preferences.getStringSet(KEYS + identity,
                Collections.emptySet()));
        SharedPreferences.Editor edit = preferences.edit();
        for (String existing : new HashSet<>(keys)) {
            String key = BINDING + identity + "." + existing;
            if (button.name().equals(preferences.getString(key, null))
                    && !existing.equals(Integer.toString(keyCode))) {
                edit.remove(key);
                keys.remove(existing);
            }
        }
        for (Map.Entry<Integer, Button> defaultBinding : defaults.entrySet()) {
            int defaultKeyCode = defaultBinding.getKey();
            if (defaultBinding.getValue() == button && defaultKeyCode != keyCode) {
                keys.add(Integer.toString(defaultKeyCode));
                edit.putString(BINDING + identity + "." + defaultKeyCode, "NONE");
            }
        }
        keys.add(Integer.toString(keyCode));
        edit.putString(BINDING + identity + "." + keyCode, button.name())
                .putStringSet(KEYS + identity, keys)
                .apply();
    }

    Integer keyCodeForButton(InputDevice device, Button button, Map<Integer, Button> defaults) {
        String deviceIdentity = identity(device);
        TreeSet<Integer> candidates = new TreeSet<>();
        for (String encoded : preferences.getStringSet(
                KEYS + deviceIdentity, Collections.emptySet())) {
            try {
                candidates.add(Integer.parseInt(encoded));
            } catch (NumberFormatException ignored) {
                // Ignore a malformed app-private preference and fall back to defaults.
            }
        }
        candidates.addAll(defaults.keySet());
        for (int keyCode : candidates) {
            if (binding(device, keyCode, defaults.get(keyCode)) == button) {
                return keyCode;
            }
        }
        return null;
    }

    boolean invertedX(InputDevice device) {
        return preferences.getBoolean(INVERT_X + identity(device), false);
    }

    boolean invertedY(InputDevice device) {
        return preferences.getBoolean(INVERT_Y + identity(device), false);
    }

    void toggleInvertedX(InputDevice device) {
        String key = INVERT_X + identity(device);
        preferences.edit().putBoolean(key, !preferences.getBoolean(key, false)).apply();
    }

    void toggleInvertedY(InputDevice device) {
        String key = INVERT_Y + identity(device);
        preferences.edit().putBoolean(key, !preferences.getBoolean(key, false)).apply();
    }

    void reset(InputDevice device) {
        String identity = identity(device);
        SharedPreferences.Editor edit = preferences.edit()
                .remove(KEYS + identity)
                .remove(INVERT_X + identity)
                .remove(INVERT_Y + identity);
        for (String keyCode : preferences.getStringSet(KEYS + identity, Collections.emptySet())) {
            edit.remove(BINDING + identity + "." + keyCode);
        }
        edit.apply();
    }

    private static String identity(InputDevice device) {
        if (device == null) {
            return "unknown";
        }
        String descriptor = device.getDescriptor();
        String raw = (descriptor == null ? "" : descriptor) + "|"
                + device.getVendorId() + "|" + device.getProductId();
        return sha256(raw);
    }

    private static String sha256(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder encoded = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                encoded.append(Character.forDigit((value >>> 4) & 0xf, 16));
                encoded.append(Character.forDigit(value & 0xf, 16));
            }
            return encoded.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
