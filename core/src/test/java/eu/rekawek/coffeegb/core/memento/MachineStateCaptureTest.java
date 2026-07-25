package eu.rekawek.coffeegb.core.memento;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class MachineStateCaptureTest {

    @Test
    public void verifiesTheSameLiveBackingArrayAcrossBothPasses() {
        int[] backing = new int[4096];

        Verified result = MachineStateCapture.withVerifiedView(
                capture -> capture.declareInts(backing),
                capture -> new ArrayView(capture.ints(backing)),
                (view, capture) -> {
                    assertSame(backing, view.values());
                    assertEquals(backing.length, capture.requireLength(view.values()));
                    return new Verified(
                            capture.getVerifiedPayloadArrays(),
                            capture.getVerifiedPayloadBytes());
                });

        assertEquals(1, result.arrays());
        assertEquals((long) backing.length * Integer.BYTES, result.bytes());
    }

    @Test
    public void rejectsACloneRegisteredByTheTokenAwarePath() {
        int[] backing = new int[4096];
        AtomicBoolean consumerCalled = new AtomicBoolean();

        assertThrows(
                IllegalStateException.class,
                () -> MachineStateCapture.withVerifiedView(
                        capture -> capture.declareInts(backing),
                        capture -> new ArrayView(capture.ints(backing.clone())),
                        (view, capture) -> {
                            consumerCalled.set(true);
                            return null;
                        }));

        assertFalse(consumerCalled.get());
    }

    @Test
    public void rejectsAnUnregisteredPrimitiveArrayFallback() {
        int[] backing = new int[4096];
        AtomicBoolean consumerCalled = new AtomicBoolean();

        assertThrows(
                IllegalStateException.class,
                () -> MachineStateCapture.withVerifiedView(
                        capture -> {},
                        capture -> new ArrayView(backing.clone()),
                        (view, capture) -> {
                            capture.requireLength(view.values());
                            consumerCalled.set(true);
                            return null;
                        }));

        assertFalse(consumerCalled.get());
    }

    private record ArrayView(int[] values) {}

    private record Verified(int arrays, long bytes) {}
}
