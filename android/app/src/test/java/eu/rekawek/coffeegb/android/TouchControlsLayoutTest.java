package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.PlayerInputHub;
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

    @Test
    public void enlargedSpaceBetweenActionButtonsOverlapsBothButtonsInBothOrientations() {
        TouchControlsLayout layout = new TouchControlsLayout(.5f, 1f, 0f, false, true);

        assertActionButtonBridge(layout, 1_080, 1_920);
        assertActionButtonBridge(layout, 1_920, 1_080);
    }

    @Test
    public void mappedViewTouchUsesNativeSkinGeometryAndLetterboxMoveReleasesIt() {
        TouchControlsLayout layout = new TouchControlsLayout(.5f, 1f, 0f, false, false);
        SkinTransform transform = SkinTransform.aspectFit(941, 1672, 920, 1884);
        float nativeX = layout.actionCenterX(941, 1672, true);
        float nativeY = layout.actionCenterY(941, 1672, true);
        SkinTransform.Point mapped = transform.mapPoint(nativeX, nativeY);
        PlayerInputHub hub = new PlayerInputHub();
        AndroidInputRouter router = new AndroidInputRouter(hub);
        try {
            router.updateTouchPointer(7,
                    layout.buttonsAtViewPoint(mapped.x(), mapped.y(), transform));
            assertEquals(java.util.Set.of(Button.A), hub.sample().buttons(0));

            router.updateTouchPointer(7, layout.buttonsAtViewPoint(
                    mapped.x(), transform.skinBounds().top() - 1f, transform));
            assertTrue(hub.sample().buttons(0).isEmpty());
        } finally {
            router.close();
        }
    }

    private static void assertActionButtonBridge(TouchControlsLayout layout,
                                                  int width, int height) {
        float middleX = (layout.actionCenterX(width, height, false)
                + layout.actionCenterX(width, height, true)) / 2f;
        float middleY = (layout.actionCenterY(width, height, false)
                + layout.actionCenterY(width, height, true)) / 2f;
        float bOverlapX = (middleX + layout.actionCenterX(width, height, false)) / 2f;
        float bOverlapY = (middleY + layout.actionCenterY(width, height, false)) / 2f;
        float aOverlapX = (middleX + layout.actionCenterX(width, height, true)) / 2f;
        float aOverlapY = (middleY + layout.actionCenterY(width, height, true)) / 2f;

        assertEquals(List.of(Button.A, Button.B),
                layout.buttonsAt(middleX, middleY, width, height));
        assertEquals(List.of(Button.A, Button.B),
                layout.buttonsAt(bOverlapX, bOverlapY, width, height));
        assertEquals(List.of(Button.A, Button.B),
                layout.buttonsAt(aOverlapX, aOverlapY, width, height));
        assertEquals(List.of(Button.B), layout.buttonsAt(
                layout.actionCenterX(width, height, false),
                layout.actionCenterY(width, height, false), width, height));
        assertEquals(List.of(Button.A), layout.buttonsAt(
                layout.actionCenterX(width, height, true),
                layout.actionCenterY(width, height, true), width, height));
    }
}
