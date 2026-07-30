package eu.rekawek.coffeegb.core.joypad;

import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class JoypadButtonMaskTest {

    @Test
    public void usesTheExplicitPortableBitLayout() {
        assertEquals(0x01, JoypadButtonMask.bit(Button.RIGHT));
        assertEquals(0x02, JoypadButtonMask.bit(Button.LEFT));
        assertEquals(0x04, JoypadButtonMask.bit(Button.UP));
        assertEquals(0x08, JoypadButtonMask.bit(Button.DOWN));
        assertEquals(0x10, JoypadButtonMask.bit(Button.A));
        assertEquals(0x20, JoypadButtonMask.bit(Button.B));
        assertEquals(0x40, JoypadButtonMask.bit(Button.SELECT));
        assertEquals(0x80, JoypadButtonMask.bit(Button.START));
        assertEquals(0xff, JoypadButtonMask.ALL);
    }

    @Test
    public void convertsMasksWithoutDependingOnEnumOrdinals() {
        Set<Button> buttons = Set.of(Button.RIGHT, Button.DOWN, Button.A, Button.START);
        int mask = JoypadButtonMask.RIGHT | JoypadButtonMask.DOWN
                | JoypadButtonMask.A | JoypadButtonMask.START;

        assertEquals(mask, JoypadButtonMask.fromButtons(buttons));
        assertEquals(buttons, JoypadButtonMask.toButtons(mask));
        assertThrows(IllegalArgumentException.class,
                () -> JoypadButtonMask.toButtons(0x100));
    }
}
