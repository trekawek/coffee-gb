package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TouchPressStateTest {
    @Test
    public void diagonalsAndActionChordCanBeHeldTogether() {
        TouchPressState state = new TouchPressState();
        assertTrue(state.update(4, List.of(Button.UP, Button.LEFT)));
        assertTrue(state.update(7, List.of(Button.A, Button.B)));
        assertEquals(mask(Button.UP, Button.LEFT, Button.A, Button.B), state.mask());
        assertTrue(state.release(4));
        assertEquals(mask(Button.A, Button.B), state.mask());
    }

    @Test
    public void releasingOneFingerRetainsButtonsHeldByAnother() {
        TouchPressState state = new TouchPressState();
        state.update(1, List.of(Button.A, Button.B));
        state.update(2, List.of(Button.B));
        assertTrue(state.release(1));
        assertEquals(mask(Button.B), state.mask());
        assertFalse(state.release(99));
        assertTrue(state.release(2));
        assertEquals(0, state.mask());
    }

    @Test
    public void slidingReplacesTheChordAndLeavingControlsClearsIt() {
        TouchPressState state = new TouchPressState();
        state.update(3, List.of(Button.UP, Button.LEFT));
        assertTrue(state.update(3, List.of(Button.UP, Button.RIGHT)));
        assertEquals(mask(Button.UP, Button.RIGHT), state.mask());
        assertFalse(state.update(3, List.of(Button.RIGHT, Button.UP)));
        assertTrue(state.update(3, List.of()));
        assertEquals(0, state.mask());
    }

    @Test
    public void cancellationClearsEveryPointerAndRepeatedClearDoesNotRedraw() {
        TouchPressState state = new TouchPressState();
        state.update(5, List.of(Button.SELECT));
        state.update(6, List.of(Button.START));
        assertTrue(state.clear());
        assertEquals(0, state.mask());
        assertFalse(state.clear());
        assertFalse(state.release(5));
        assertFalse(state.release(6));
    }

    private static int mask(Button... buttons) {
        int mask = 0;
        for (Button button : buttons) {
            mask |= TouchPressState.bit(button);
        }
        return mask;
    }
}
