package eu.rekawek.coffeegb.core.joypad;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/** Stable, format-safe eight-bit representation of the Game Boy buttons. */
public final class JoypadButtonMask {

    public static final int RIGHT = 1 << 0;
    public static final int LEFT = 1 << 1;
    public static final int UP = 1 << 2;
    public static final int DOWN = 1 << 3;
    public static final int A = 1 << 4;
    public static final int B = 1 << 5;
    public static final int SELECT = 1 << 6;
    public static final int START = 1 << 7;

    public static final int ALL = RIGHT | LEFT | UP | DOWN | A | B | SELECT | START;

    private JoypadButtonMask() {
    }

    /** Converts buttons to the explicit stable layout; enum declaration order is irrelevant. */
    public static int fromButtons(Collection<Button> buttons) {
        Objects.requireNonNull(buttons, "buttons");
        int result = 0;
        for (Button button : buttons) {
            result |= bit(Objects.requireNonNull(button, "buttons contains null"));
        }
        return result;
    }

    /** Converts a stable eight-bit mask to an immutable button set. */
    public static Set<Button> toButtons(int mask) {
        if ((mask & ~ALL) != 0) {
            throw new IllegalArgumentException("Button mask must be an unsigned eight-bit value: "
                    + mask);
        }
        EnumSet<Button> buttons = EnumSet.noneOf(Button.class);
        addIfSet(buttons, mask, RIGHT, Button.RIGHT);
        addIfSet(buttons, mask, LEFT, Button.LEFT);
        addIfSet(buttons, mask, UP, Button.UP);
        addIfSet(buttons, mask, DOWN, Button.DOWN);
        addIfSet(buttons, mask, A, Button.A);
        addIfSet(buttons, mask, B, Button.B);
        addIfSet(buttons, mask, SELECT, Button.SELECT);
        addIfSet(buttons, mask, START, Button.START);
        return Collections.unmodifiableSet(buttons);
    }

    /** Returns the stable bit for one button without consulting its ordinal. */
    public static int bit(Button button) {
        return switch (Objects.requireNonNull(button, "button")) {
            case RIGHT -> RIGHT;
            case LEFT -> LEFT;
            case UP -> UP;
            case DOWN -> DOWN;
            case A -> A;
            case B -> B;
            case SELECT -> SELECT;
            case START -> START;
        };
    }

    private static void addIfSet(
            EnumSet<Button> buttons, int mask, int bit, Button button) {
        if ((mask & bit) != 0) {
            buttons.add(button);
        }
    }
}
