package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ControllerKeyCaptureTest {

    @Test
    public void consumesOneDownAndMatchingUpWithoutCompletingTwice() {
        ControllerKeyCapture capture = new ControllerKeyCapture();
        capture.begin(Button.B);

        assertEquals(ControllerKeyCapture.Result.CAPTURED_DOWN, capture.keyDown(4, 97));
        assertTrue(capture.waitingForRelease());
        assertEquals(ControllerKeyCapture.Result.CONSUMED, capture.keyDown(4, 97));
        assertEquals(ControllerKeyCapture.Result.CONSUMED, capture.keyUp(5, 97));
        assertEquals(ControllerKeyCapture.Result.COMPLETED, capture.keyUp(4, 97));
        assertFalse(capture.active());
        assertEquals(ControllerKeyCapture.Result.NONE, capture.keyUp(4, 97));
    }

    @Test
    public void cancelAndDisconnectDropPendingCapture() {
        List<String> writes = new ArrayList<>();
        ControllerKeyCapture capture = new ControllerKeyCapture((device, key, target) ->
                writes.add(device + ":" + key + ":" + target));
        capture.begin(Button.A);
        capture.keyDown(3, 96);
        capture.cancel();
        assertFalse(capture.active());
        assertTrue(writes.isEmpty());

        capture.begin(Button.START);
        capture.keyDown(8, 108);
        capture.disconnect(8);
        assertFalse(capture.active());
        assertTrue(writes.isEmpty());
    }

    @Test
    public void matchingUpCommitsExactlyOnceAndDownOrConflictsNeverWrite() {
        List<String> writes = new ArrayList<>();
        ControllerKeyCapture capture = new ControllerKeyCapture((device, key, target) ->
                writes.add(device + ":" + key + ":" + target));
        capture.begin(Button.B);

        assertEquals(ControllerKeyCapture.Result.CAPTURED_DOWN, capture.keyDown(4, 97));
        assertTrue(writes.isEmpty());
        assertEquals(ControllerKeyCapture.Result.CONSUMED, capture.keyDown(4, 98));
        assertEquals(ControllerKeyCapture.Result.CONSUMED, capture.keyUp(4, 98));
        assertTrue(writes.isEmpty());
        assertEquals(ControllerKeyCapture.Result.COMPLETED, capture.keyUp(4, 97));
        assertEquals(List.of("4:97:B"), writes);
        assertEquals(ControllerKeyCapture.Result.NONE, capture.keyUp(4, 97));
        assertEquals(1, writes.size());
    }
}
