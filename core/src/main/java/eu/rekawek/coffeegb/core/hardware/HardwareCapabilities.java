package eu.rekawek.coffeegb.core.hardware;

/** Immutable, platform-neutral hardware feature set derived once during session construction. */
public record HardwareCapabilities(
        boolean colorDisplay,
        boolean cgbMode,
        boolean doubleSpeed,
        boolean infrared,
        boolean superGameboyCommands,
        boolean superGameboyBorder,
        boolean serialLink) {

    public HardwareCapabilities {
        if (cgbMode && !colorDisplay) {
            throw new IllegalArgumentException("CGB mode requires color-display capability");
        }
        if ((doubleSpeed || infrared) && !cgbMode) {
            throw new IllegalArgumentException("Double speed and infrared require CGB mode");
        }
        if (superGameboyCommands && cgbMode) {
            throw new IllegalArgumentException("Current SGB profile cannot simultaneously use CGB mode");
        }
        if (superGameboyBorder && !superGameboyCommands) {
            throw new IllegalArgumentException("SGB border output requires SGB command support");
        }
    }
}
