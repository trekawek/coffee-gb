package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import org.junit.Test;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpsReceiverSerialEndpointTest {

    @Test
    public void sendsTwoStartupBurstsForReceiverDetection() {
        GpsReceiverSerialEndpoint gps = new GpsReceiverSerialEndpoint();

        tick(gps, GpsReceiverSerialEndpoint.STARTUP_DELAY_TICKS - 1);
        assertTrue(gps.isSerialInputHigh());
        gps.tick();
        assertFalse(gps.isSerialInputHigh());
        assertEquals("GPS\r", readAscii(gps, 4, false));

        waitForStart(gps, GpsReceiverSerialEndpoint.STARTUP_BEACON_INTERVAL_TICKS);
        assertEquals("GPS\r", readAscii(gps, 4, false));
    }

    @Test
    public void answersGpsBoyTaipPositionRequest() {
        GpsReceiverSerialEndpoint gps = new GpsReceiverSerialEndpoint();

        sendAscii(gps, ">QPV<");

        String expected = ">RPV00000+3738500-0059750000000012;*00<";
        assertEquals(39, expected.length());
        assertEquals(expected, readAscii(gps, expected.length(), true));
    }

    @Test
    public void liveSourceFormatsPhoneFixAsTrimbleTaip() {
        long fixTime = Instant.parse("2026-08-31T16:45:23.456Z").toEpochMilli();
        GpsFix fix = new GpsFix(fixTime, 52.22970, 21.01220, 123.6,
                10.0, 275.6, -1.0);
        GpsDataSource source = new GpsDataSource() {
            @Override
            public GpsFix currentFix() {
                return fix;
            }

            @Override
            public long currentTimeMillis() {
                return fixTime;
            }
        };
        GpsReceiverSerialEndpoint gps = new GpsReceiverSerialEndpoint(ClockSpec.LEGACY, source);

        assertResponse(gps, ">QST<", ">RST00015A0200;*00<");
        assertResponse(gps, ">QPV<", ">RPV60323+5222970+0210122002227612;*00<");
        assertResponse(gps, ">QAL<", ">RAL60323+00124-00212;*00<");
        assertResponse(gps, ">QTM<", ">RTM1645234563108202618104100000;*00<");
    }

    @Test
    public void liveSourceReportsMissingOrStaleFixInsteadOfFixtureLocation() {
        long now = Instant.parse("2026-08-31T16:45:23Z").toEpochMilli();
        GpsDataSource source = new GpsDataSource() {
            @Override
            public GpsFix currentFix() {
                return new GpsFix(now - 121_000L, 52.0, 21.0, 100.0,
                        3.0, 90.0, 0.0);
            }

            @Override
            public long currentTimeMillis() {
                return now;
            }
        };
        GpsReceiverSerialEndpoint gps = new GpsReceiverSerialEndpoint(ClockSpec.LEGACY, source);

        assertResponse(gps, ">QST<", ">RST08015A0200;*00<");
        assertResponse(gps, ">QPV<", ">RPV60323+0000000+0000000000000090;*00<");
        assertResponse(gps, ">QAL<", ">RAL60323+00000+00090;*00<");
    }

    @Test
    public void snapshotPreservesAnInFlightUartFrame() {
        GpsReceiverSerialEndpoint original = new GpsReceiverSerialEndpoint();
        sendAscii(original, ">QST<");
        waitForStart(original, GpsReceiverSerialEndpoint.RESPONSE_TURNAROUND_TICKS + 10);
        tick(original, 123);

        ComponentState<SerialEndpoint> memento = original.captureState();
        GpsReceiverSerialEndpoint restored = new GpsReceiverSerialEndpoint();
        restored.restoreState(memento);

        for (int i = 0; i < 20_000; i++) {
            assertEquals(original.isSerialInputHigh(), restored.isSerialInputHigh());
            original.tick();
            restored.tick();
        }
    }

    @Test
    public void startupAndUartTimingUseTheOwningCustomClock() {
        ClockSpec clock = new ClockSpec(96_000, 60, 1);
        GpsReceiverSerialEndpoint gps = new GpsReceiverSerialEndpoint(clock);

        tick(gps, 23_999);
        assertTrue(gps.isSerialInputHigh());
        gps.tick();
        assertFalse(gps.isSerialInputHigh());

        // 96,000 / 9,600 is exactly ten master ticks for every UART bit.
        tick(gps, 9);
        assertFalse(gps.isSerialInputHigh());
        gps.tick();
        assertTrue(gps.isSerialInputHigh());
    }

    @Test
    public void performanceSpansMatchScalarStartupAndUartTicks() {
        ClockSpec clock = new ClockSpec(96_000, 60, 1);
        GpsReceiverSerialEndpoint scalar = new GpsReceiverSerialEndpoint(clock);
        GpsReceiverSerialEndpoint bulk = new GpsReceiverSerialEndpoint(clock);

        for (int elapsed = 0; elapsed < 150_000;) {
            int requested = Math.min(54, 150_000 - elapsed);
            int span = bulk.performanceQuietSpanLimit(requested);
            if (span == 0) {
                scalar.tick();
                bulk.tick();
                elapsed++;
            } else {
                tick(scalar, span);
                assertTrue(bulk.tickPerformanceQuietSpan(span));
                elapsed += span;
            }
            assertStateEquals(scalar, bulk);
            assertEquals(scalar.isSerialInputHigh(), bulk.isSerialInputHigh());
        }
    }

    @Test
    public void performanceSpanStopsBeforeEachUartEdge() {
        ClockSpec clock = new ClockSpec(96_000, 60, 1);
        GpsReceiverSerialEndpoint scalar = new GpsReceiverSerialEndpoint(clock);
        GpsReceiverSerialEndpoint bulk = new GpsReceiverSerialEndpoint(clock);

        tick(scalar, 24_000);
        tick(bulk, 24_000);
        assertFalse(bulk.isSerialInputHigh());
        assertEquals(9, bulk.performanceQuietSpanLimit(54));

        tick(scalar, 9);
        assertTrue(bulk.tickPerformanceQuietSpan(9));
        assertStateEquals(scalar, bulk);
        assertEquals(0, bulk.performanceQuietSpanLimit(54));

        scalar.tick();
        bulk.tick();
        assertStateEquals(scalar, bulk);
        assertEquals(scalar.isSerialInputHigh(), bulk.isSerialInputHigh());
    }

    static void assertStateEquals(GpsReceiverSerialEndpoint expected,
                                  GpsReceiverSerialEndpoint actual) {
        Object expectedState = expected.captureState();
        Object actualState = actual.captureState();
        assertEquals(expectedState.getClass(), actualState.getClass());
        try {
            for (RecordComponent component : expectedState.getClass().getRecordComponents()) {
                var accessor = component.getAccessor();
                accessor.setAccessible(true);
                Object expectedValue = accessor.invoke(expectedState);
                Object actualValue = accessor.invoke(actualState);
                if (expectedValue instanceof int[] expectedArray) {
                    assertArrayEquals(component.getName(), expectedArray, (int[]) actualValue);
                } else {
                    assertEquals(component.getName(), expectedValue, actualValue);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Cannot compare GPS receiver state", e);
        }
    }

    private static void sendAscii(GpsReceiverSerialEndpoint gps, String value) {
        for (byte b : value.getBytes(StandardCharsets.US_ASCII)) {
            sendUartByte(gps, b & 0xff);
        }
    }

    private static void assertResponse(GpsReceiverSerialEndpoint gps, String query,
                                       String expected) {
        sendAscii(gps, query);
        assertEquals(expected, readAscii(gps, expected.length(), true));
    }

    private static void sendUartByte(GpsReceiverSerialEndpoint gps, int value) {
        sendUartBit(gps, 0);
        for (int bit = 0; bit < 8; bit++) {
            sendUartBit(gps, (value >>> bit) & 1);
        }
        sendUartBit(gps, (Integer.bitCount(value) & 1) == 0 ? 1 : 0);
        sendUartBit(gps, 1);
    }

    private static void sendUartBit(GpsReceiverSerialEndpoint gps, int bit) {
        gps.setSb(bit == 0 ? 0x00 : 0xff);
        gps.startSending();
    }

    private static String readAscii(GpsReceiverSerialEndpoint gps, int length,
                                    boolean waitForStart) {
        if (waitForStart) {
            waitForStart(gps, GpsReceiverSerialEndpoint.RESPONSE_TURNAROUND_TICKS + 10);
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < length; i++) {
            assertFalse("UART start bit", gps.isSerialInputHigh());
            tick(gps, GpsReceiverSerialEndpoint.UART_BIT_TICKS);

            int value = 0;
            for (int bit = 0; bit < 8; bit++) {
                if (gps.isSerialInputHigh()) {
                    value |= 1 << bit;
                }
                tick(gps, GpsReceiverSerialEndpoint.UART_BIT_TICKS);
            }

            int parity = gps.isSerialInputHigh() ? 1 : 0;
            assertEquals("UART odd parity", 1, (Integer.bitCount(value) + parity) & 1);
            tick(gps, GpsReceiverSerialEndpoint.UART_BIT_TICKS);
            assertTrue("UART stop bit", gps.isSerialInputHigh());
            tick(gps, GpsReceiverSerialEndpoint.UART_BIT_TICKS);
            result.append((char) value);
        }
        return result.toString();
    }

    private static void waitForStart(GpsReceiverSerialEndpoint gps, int maxTicks) {
        for (int i = 0; i < maxTicks && gps.isSerialInputHigh(); i++) {
            gps.tick();
        }
        assertFalse("GPS receiver did not start a UART frame", gps.isSerialInputHigh());
    }

    private static void tick(GpsReceiverSerialEndpoint gps, int ticks) {
        for (int i = 0; i < ticks; i++) {
            gps.tick();
        }
    }
}
