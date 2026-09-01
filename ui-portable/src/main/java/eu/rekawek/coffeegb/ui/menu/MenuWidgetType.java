package eu.rekawek.coffeegb.ui.menu;

import java.util.Locale;

/**
 * Reusable visual and interaction roles for rows in the portable menu template.
 */
public enum MenuWidgetType {
    BUTTON,
    DROPDOWN,
    CHECKBOX,
    SLIDER;

    /**
     * Whether left and right input should adjust the row instead of moving focus.
     */
    public boolean adjustable() {
        return this == SLIDER;
    }

    /** Keeps legacy string-valued checkbox rows compatible with the typed checked state. */
    boolean checkedFrom(String detail) {
        if (this != CHECKBOX || detail == null) {
            return false;
        }
        String normalized = detail.toUpperCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        return normalized.equals("ON") || normalized.equals("YES")
                || normalized.equals("TRUE") || normalized.equals("ENABLED")
                || normalized.equals("CHECKED");
    }
}
