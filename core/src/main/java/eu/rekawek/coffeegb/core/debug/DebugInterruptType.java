package eu.rekawek.coffeegb.core.debug;

/** Platform-neutral Game Boy interrupt identities shared by breakpoints and trace events. */
public enum DebugInterruptType {
    VBLANK,
    LCD_STATUS,
    TIMER,
    SERIAL,
    JOYPAD
}
