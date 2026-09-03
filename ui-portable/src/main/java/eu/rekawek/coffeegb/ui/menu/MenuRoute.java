package eu.rekawek.coffeegb.ui.menu;

/** Stable page identities used by the in-screen emulator menu. */
public enum MenuRoute {
    PAUSE_CONSOLE("PAUSE CONSOLE"),
    SAVE_STATES("SAVE STATES"),
    RECENT_GAMES("RECENT GAMES"),
    SETTINGS("SETTINGS"),
    AUDIO("AUDIO"),
    DISPLAY("DISPLAY"),
    TOUCH_CONTROLS("TOUCH CONTROLS"),
    CONTROLLER_MAPPING("CONTROLLER MAPPING"),
    OPTIONAL_DEVICES("PERIPHERALS"),
    OPTION_PICKER("OPTION PICKER"),
    PRINTER_PAPER("PRINTER PAPER"),
    DATA_MEDIA("DATA & MEDIA"),
    LIBRARY("LIBRARY"),
    FILE_BROWSER("FILE BROWSER"),
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
