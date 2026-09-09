package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.TestDebugHooks;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.BarcodeBoySerialEndpoint;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class InfraredPerformanceSpanTest {

    @Test
    public void everyFullChangerScheduleMatchesScalarAtBothSpeeds() throws Exception {
        for (boolean doubleSpeed : new boolean[]{false, true}) {
            for (int character = 1; character <= FullChanger.CHARACTERS; character++) {
                try (Fixture scalar = new Fixture(doubleSpeed); Fixture bulk = new Fixture(doubleSpeed)) {
                    scalar.bus.post(new FullChanger.TransformEvent(character));
                    bulk.bus.post(new FullChanger.TransformEvent(character));
                    assertEquals("armed input must wait quietly for the first RP read", 54,
                            bulk.port.performanceEventHorizon(54));
                    runPair(scalar, bulk, 57);
                    assertEquals(scalar.port.getByte(0xff56), bulk.port.getByte(0xff56));
                    int batched = runPair(scalar, bulk, 12_000);
                    assertTrue("IR pulse countdowns must not force whole-transmission fallback",
                            batched > 11_900);
                }
            }
        }
    }

    @Test
    public void simultaneousRemoteAndFullChangerPreservePulseEdgesAndMidPulseRestore() throws Exception {
        for (boolean doubleSpeed : new boolean[]{false, true}) {
            try (Fixture scalar = new Fixture(doubleSpeed); Fixture bulk = new Fixture(doubleSpeed)) {
                scalar.bus.post(new TvRemote.SendSignalEvent());
                bulk.bus.post(new TvRemote.SendSignalEvent());
                scalar.bus.post(new FullChanger.TransformEvent(17));
                bulk.bus.post(new FullChanger.TransformEvent(17));
                assertEquals(scalar.port.getByte(0xff56), bulk.port.getByte(0xff56));
                runPair(scalar, bulk, 131);
                ComponentState<InfraredPort> saved = bulk.port.captureState();
                int horizon = bulk.port.performanceEventHorizon(54);
                bulk.port.tick();
                bulk.port.restoreState(saved);
                assertEquals(horizon, bulk.port.performanceEventHorizon(54));
                assertStateEquals(scalar.port.captureState(), bulk.port.captureState());
                int batched = runPair(scalar, bulk, 350_000);
                assertTrue("constant IR pulses should retain sustained batching", batched > 349_800);
            }
        }
    }

    @Test
    public void endpointTopologyDebugAndInputPinCapabilitiesFailClosed() throws Exception {
        try (Fixture fixture = new Fixture(false)) {
            fixture.port.setSerialEndpoint(new BarcodeBoySerialEndpoint());
            assertEquals("barcode framed transfers leave the software-UART pin high", 54,
                    fixture.port.performanceEventHorizon(54));
            fixture.port.init(fixture.bus, new InfraredEndpoint() {
                @Override
                public void setLightOn(boolean lightOn) {
                }

                @Override
                public boolean isLightOn() {
                    return false;
                }
            });
            assertEquals("unknown fixed-looking endpoints must explicitly opt in", 0,
                    fixture.port.performanceEventHorizon(54));
            fixture.port.init(fixture.bus, InfraredEndpoint.NULL_ENDPOINT);
            fixture.port.setDebugHooks(new TestDebugHooks());
            assertEquals(0, fixture.port.performanceEventHorizon(54));
            fixture.port.setDebugHooks(null);
            assertEquals(54, fixture.port.performanceEventHorizon(54));
        }
    }

    private static int runPair(Fixture scalar, Fixture bulk, int ticks) throws Exception {
        int batched = 0;
        for (int elapsed = 0; elapsed < ticks;) {
            int span = bulk.port.performanceEventHorizon(Math.min(54, ticks - elapsed));
            if (span == 0) {
                scalar.port.tick();
                bulk.port.tick();
                span = 1;
            } else {
                var before = scalar.port.captureDebugInfraredInspection(true);
                for (int tick = 0; tick < span; tick++) {
                    scalar.port.tick();
                    assertEquals("an admitted interval crossed a light edge", before,
                            scalar.port.captureDebugInfraredInspection(true));
                }
                bulk.port.tickPerformanceEventSpanTrusted(span);
                batched += span;
            }
            elapsed += span;
            assertEquals(scalar.port.captureDebugInfraredInspection(true),
                    bulk.port.captureDebugInfraredInspection(true));
            assertStateEquals(scalar.port.captureState(), bulk.port.captureState());
        }
        return batched;
    }

    private static void assertStateEquals(Object expected, Object actual) throws Exception {
        if (expected instanceof int[] expectedArray) {
            assertArrayEquals(expectedArray, (int[]) actual);
        } else if (expected != null && expected.getClass().isRecord()) {
            assertEquals(expected.getClass(), actual.getClass());
            for (var component : expected.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                assertStateEquals(accessor.invoke(expected), accessor.invoke(actual));
            }
        } else {
            assertEquals(expected, actual);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final EventBusImpl bus = new EventBusImpl(null, null, false);
        final InfraredPort port;

        Fixture(boolean doubleSpeed) throws Exception {
            SpeedMode speed = new SpeedMode(true);
            if (doubleSpeed) {
                speed.setByte(0xff4d, 1);
                var onStop = SpeedMode.class.getDeclaredMethod("onStop");
                onStop.setAccessible(true);
                assertTrue((boolean) onStop.invoke(speed));
            }
            port = new InfraredPort(true, speed);
            port.init(bus);
            port.setByte(0xff56, 0xc0);
        }

        @Override
        public void close() {
            port.close();
            bus.close();
        }
    }
}
