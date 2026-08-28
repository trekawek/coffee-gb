package eu.rekawek.coffeegb.swing.io;

/**
 * Defines how an emulated frame is scaled inside the Swing display component.
 */
public enum DisplayScaleMode {

    /** Use the largest whole-number scale that fits the component. */
    INTEGER_FIT(0),

    /** Use all available space while preserving the source aspect ratio. */
    ASPECT_FIT(0),

    EXPLICIT_1X(1),

    EXPLICIT_2X(2),

    EXPLICIT_3X(3),

    EXPLICIT_4X(4),

    EXPLICIT_5X(5);

    private final int explicitScale;

    DisplayScaleMode(int explicitScale) {
        this.explicitScale = explicitScale;
    }

    public boolean isExplicit() {
        return explicitScale != 0;
    }

    public int explicitScale() {
        if (!isExplicit()) {
            throw new IllegalStateException(name() + " does not have an explicit scale");
        }
        return explicitScale;
    }

    public static DisplayScaleMode explicit(int scale) {
        return switch (scale) {
            case 1 -> EXPLICIT_1X;
            case 2 -> EXPLICIT_2X;
            case 3 -> EXPLICIT_3X;
            case 4 -> EXPLICIT_4X;
            case 5 -> EXPLICIT_5X;
            default -> throw new IllegalArgumentException(
                    "Explicit display scale must be between 1 and 5");
        };
    }
}
