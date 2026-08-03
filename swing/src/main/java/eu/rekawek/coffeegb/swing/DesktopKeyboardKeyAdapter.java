package eu.rekawek.coffeegb.swing;

import eu.rekawek.coffeegb.controller.properties.ApplicationSettings;
import eu.rekawek.coffeegb.controller.properties.ControllerProperties;

import java.awt.event.KeyEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Resolves portable persisted keyboard tokens at the desktop boundary. */
public final class DesktopKeyboardKeyAdapter {

    private static final Map<Integer, String> CANONICAL_NAMES_BY_CODE =
            java.util.Arrays.stream(KeyEvent.class.getFields())
                    .filter(field -> field.getName().startsWith("VK_"))
                    .filter(field -> field.getType() == int.class)
                    .filter(field -> Modifier.isStatic(field.getModifiers()))
                    .collect(Collectors.groupingBy(
                            field -> getInt(field),
                            Collectors.collectingAndThen(
                                    Collectors.toList(),
                                    fields -> fields.stream()
                                            .map(Field::getName)
                                            .min(String::compareTo)
                                            .orElseThrow())));

    private DesktopKeyboardKeyAdapter() {
    }

    public static ApplicationSettings.KeyboardKey fromKeyCode(int keyCode) {
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            throw new IllegalArgumentException("Undefined desktop keys cannot be bound");
        }
        String name = CANONICAL_NAMES_BY_CODE.get(keyCode);
        if (name == null) {
            throw new IllegalArgumentException("Unsupported desktop key code " + keyCode);
        }
        return ApplicationSettings.KeyboardKey.Companion.parse(name, "desktop keyboard capture");
    }

    public static int keyCode(ApplicationSettings.KeyboardKey key) {
        Field field;
        try {
            field = KeyEvent.class.getField(key.getPropertyName());
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Unknown desktop keyboard key " + key.getPropertyName());
        }
        if (field.getType() != int.class || !Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException("Invalid desktop keyboard key " + key.getPropertyName());
        }
        int keyCode = getInt(field);
        if (keyCode == KeyEvent.VK_UNDEFINED) {
            throw new IllegalArgumentException("Undefined desktop keys cannot be bound");
        }
        return keyCode;
    }

    public static List<Integer> keyCodes(Collection<ApplicationSettings.KeyboardKey> keys) {
        return keys.stream().map(DesktopKeyboardKeyAdapter::keyCode).collect(Collectors.toList());
    }

    /** Converts the portable mapping just before Swing receives host key events. */
    public static Map<Integer, ControllerProperties.PlayerButton> resolveMapping(
            Map<ApplicationSettings.KeyboardKey, ControllerProperties.PlayerButton> mapping) {
        Map<Integer, ControllerProperties.PlayerButton> resolved = new LinkedHashMap<>();
        mapping.forEach((key, binding) -> {
            int keyCode = keyCode(key);
            ControllerProperties.PlayerButton previous = resolved.put(keyCode, binding);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Desktop key " + KeyEvent.getKeyText(keyCode) + " is assigned to both P"
                                + (previous.getPlayer() + 1) + " " + previous.getButton() + " and P"
                                + (binding.getPlayer() + 1) + " " + binding.getButton());
            }
        });
        return resolved;
    }

    private static int getInt(Field field) {
        try {
            return field.getInt(null);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Unable to read desktop keyboard constant", e);
        }
    }
}
