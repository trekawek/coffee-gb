package eu.rekawek.coffeegb.android.menu;

/** Stable page identities used by the in-screen emulator menu. */
public enum MenuRoute {
    PAUSE_CONSOLE("PAUSE CONSOLE"),
    SAVE_STATES("SAVE STATES"),
    SETTINGS("SETTINGS"),
    AUDIO("AUDIO"),
    TOUCH_CONTROLS("TOUCH CONTROLS"),
    CONTROLLER_MAPPING("CONTROLLER MAPPING"),
    OPTIONAL_DEVICES("OPTIONAL DEVICES"),
    PRINTER_PAPER("PRINTER PAPER"),
    DATA_MEDIA("DATA & MEDIA"),
    LIBRARY("LIBRARY"),
    CHOOSE_ROM("CHOOSE ROM"),
    SYSTEM("SYSTEM"),
    ABOUT("ABOUT"),
    CONFIRM_ACTION("CONFIRM ACTION");

    private final String label;

    MenuRoute(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
