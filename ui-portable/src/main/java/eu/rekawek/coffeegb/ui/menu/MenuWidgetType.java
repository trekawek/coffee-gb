package eu.rekawek.coffeegb.ui.menu;

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
}
