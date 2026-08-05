package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TouchControlsLayoutTest {

    @Test
    public void portraitDpadAllowsDiagonalWhileActionButtonsUseTheRasterLocations() {
        TouchControlsLayout layout = new TouchControlsLayout(.5f, 1f, 0f, false, true);
        int width = 1_080;
        int height = 1_920;
        float radius = layout.controlRadius(width, height);
        float x = layout.dpadCenterX(width, height) - radius * .70f;
        float y = layout.dpadCenterY(width, height) - radius * .70f;

        assertTrue(layout.buttonsAt(x, y, width, height).containsAll(List.of(Button.LEFT, Button.UP)));
        assertEquals(List.of(Button.A), layout.buttonsAt(
                layout.actionCenterX(width, height, true),
                layout.actionCenterY(width, height, true), width, height));
    }

    @Test
    public void landscapeControlsStayInTheSideBays() {
        TouchControlsLayout layout = new TouchControlsLayout(.5f, 1f, 0f, false, false);
        int width = 1_920;
        int height = 1_080;

        assertTrue(layout.dpadCenterX(width, height) < width * .20f);
        assertTrue(layout.actionCenterX(width, height, false) > width * .80f);
        assertEquals(List.of(Button.B), layout.buttonsAt(
                layout.actionCenterX(width, height, false),
                layout.actionCenterY(width, height, false), width, height));
        assertEquals(List.of(Button.SELECT), layout.buttonsAt(
                layout.utilityCenterX(width, height, false), layout.utilityCenterY(width, height),
                width, height));
        assertEquals(List.of(Button.START), layout.buttonsAt(
                layout.utilityCenterX(width, height, true), layout.utilityCenterY(width, height),
                width, height));
    }
}
