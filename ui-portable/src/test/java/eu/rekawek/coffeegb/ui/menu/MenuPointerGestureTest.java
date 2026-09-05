package eu.rekawek.coffeegb.ui.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuPointerGestureTest {

    @Test
    public void matchingReleaseActivatesExactlyOnceAndKeepsOtherPointersIndependent() {
        MenuPointerGesture gesture = new MenuPointerGesture();
        MenuPointerTarget first = new MenuPointerTarget(MenuRoute.SETTINGS, "audio", MenuKey.A);
        MenuPointerTarget second = new MenuPointerTarget(MenuRoute.SETTINGS, "display", MenuKey.A);

        gesture.press(10, first);
        gesture.press(20, second);
        assertEquals(first, gesture.release(10, first).orElseThrow());
        assertFalse(gesture.release(10, first).isPresent());
        assertTrue(gesture.captured(20));
        assertEquals(second, gesture.release(20, second).orElseThrow());
    }

    @Test
    public void releaseOverAnotherRowOrAfterNavigationDoesNotActivate() {
        MenuPointerGesture gesture = new MenuPointerGesture();
        MenuPointerTarget first = new MenuPointerTarget(MenuRoute.SETTINGS, "audio", MenuKey.A);
        gesture.press(1, first);
        assertFalse(gesture.release(1,
                new MenuPointerTarget(MenuRoute.SETTINGS, "display", MenuKey.A)).isPresent());

        gesture.press(1, first);
        assertFalse(gesture.release(1,
                new MenuPointerTarget(MenuRoute.AUDIO, "audio", MenuKey.A)).isPresent());
        assertFalse(gesture.captured(1));
    }

    @Test
    public void cancellationAndReleaseOutsideTheMenuDiscardThePress() {
        MenuPointerGesture gesture = new MenuPointerGesture();
        MenuPointerTarget target = new MenuPointerTarget(MenuRoute.SETTINGS, "audio", MenuKey.A);

        gesture.press(1, target);
        assertFalse(gesture.release(1, null).isPresent());
        gesture.press(1, target);
        gesture.cancel();
        assertFalse(gesture.release(1, target).isPresent());
        gesture.press(1, null);
        assertFalse(gesture.release(1, target).isPresent());
    }
}
