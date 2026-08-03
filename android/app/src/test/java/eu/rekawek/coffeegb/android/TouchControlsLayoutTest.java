package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TouchControlsLayoutTest {

    @Test
    public void dpadAllowsDiagonalWhileSeparatePointersCanUseActionButtons() {
        TouchControlsLayout layout = new TouchControlsLayout(.5f, 1f, 0f, false, true);
        float radius = layout.controlRadius(1_000, 600);
        float x = layout.dpadCenterX(1_000) - radius * .70f;
        float y = layout.controlsCenterY(600, radius) - radius * .70f;

        assertTrue(layout.buttonsAt(x, y, 1_000, 600).containsAll(List.of(Button.LEFT, Button.UP)));
        assertEquals(List.of(Button.A), layout.buttonsAt(layout.actionsCenterX(1_000) + radius * .7f,
                layout.controlsCenterY(600, radius), 1_000, 600));
    }

    @Test
    public void handednessMovesDpadAndActionsWithoutChangingButtonMeaning() {
        TouchControlsLayout layout = new TouchControlsLayout(.5f, 1f, .2f, true, false);
        float radius = layout.controlRadius(1_000, 600);
        float y = layout.controlsCenterY(600, radius);

        assertTrue(layout.dpadCenterX(1_000) > layout.actionsCenterX(1_000));
        assertEquals(List.of(Button.RIGHT), layout.buttonsAt(layout.dpadCenterX(1_000) + radius,
                y, 1_000, 600));
        assertEquals(List.of(Button.START), layout.buttonsAt(560, y - radius * 1.75f,
                1_000, 600));
    }
}
