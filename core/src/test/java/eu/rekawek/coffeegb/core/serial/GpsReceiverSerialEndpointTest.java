package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.memento.Memento;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

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
    public void snapshotPreservesAnInFlightUartFrame() {
        GpsReceiverSerialEndpoint original = new GpsReceiverSerialEndpoint();
        sendAscii(original, ">QST<");
        waitForStart(original, GpsReceiverSerialEndpoint.RESPONSE_TURNAROUND_TICKS + 10);
        tick(original, 123);

        Memento<SerialEndpoint> memento = original.saveToMemento();
        GpsReceiverSerialEndpoint restored = new GpsReceiverSerialEndpoint();
        restored.restoreFromMemento(memento);

        for (int i = 0; i < 20_000; i++) {
            assertEquals(original.isSerialInputHigh(), restored.isSerialInputHigh());
            original.tick();
            restored.tick();
        }
    }

    private static void sendAscii(GpsReceiverSerialEndpoint gps, String value) {
        for (byte b : value.getBytes(StandardCharsets.US_ASCII)) {
            sendUartByte(gps, b & 0xff);
        }
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
