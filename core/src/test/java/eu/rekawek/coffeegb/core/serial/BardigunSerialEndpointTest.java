package eu.rekawek.coffeegb.core.serial;

import org.junit.Test;
import static org.junit.Assert.*;

public class BardigunSerialEndpointTest {
    @Test
    public void sendsOpticalEan13WithoutBarcodeBoyHandshake() {
        BardigunSerialEndpoint endpoint = new BardigunSerialEndpoint();
        assertEquals(0, readByte(endpoint));
        assertEquals(-1, endpoint.recvBit());
        endpoint.scan("4006381333931");
        int[] bytes = new int[BardigunSerialEndpoint.SCAN_BYTES];
        for (int i = 0; i < bytes.length; i++) bytes[i] = readByte(endpoint);
        StringBuilder modules = new StringBuilder();
        for (int module = 0; module < 115; module++) {
            int sample = module * 15;
            int optical = (bytes[sample / 8] >> (7 - sample % 8)) & 1;
            for (int j = 1; j < 15; j++) {
                int next = sample + j;
                assertEquals(optical, (bytes[next / 8] >> (7 - next % 8)) & 1);
            }
            modules.append(optical == 0 ? '1' : '0');
        }
        // EAN-13 example: leading 4 selects L G L L G G parity for 006381.
        assertEquals("0000000000" + "101" + "0001101" + "0100111" + "0101111"
                + "0111101" + "0001001" + "0110011" + "01010"
                + "1000010" + "1000010" + "1000010" + "1110100" + "1000010" + "1100110"
                + "101" + "0000000000", modules.toString());
        assertFalse(endpoint.isScanPending());
        assertEquals(0, readByte(endpoint));
    }

    @Test
    public void pendingSwipeAndMidByteProgressRestoreExactly() {
        BardigunSerialEndpoint endpoint = new BardigunSerialEndpoint();
        endpoint.scan("4902370501445");
        var pending = endpoint.captureState();
        int first = readByte(endpoint);
        endpoint.restoreState(pending);
        assertEquals(first, readByte(endpoint));
        for (int i = 0; i < 22; i++) readByte(endpoint);
        endpoint.startSending();
        for (int i = 0; i < 3; i++) endpoint.sendBit();
        var mid = endpoint.captureState();
        int[] tail = new int[100];
        for (int i = 0; i < tail.length; i++) tail[i] = endpoint.sendBit();
        endpoint.restoreState(mid);
        for (int value : tail) assertEquals(value, endpoint.sendBit());
        endpoint.disconnect();
        assertFalse(endpoint.isScanPending());
        assertEquals(0, readByte(endpoint));
    }

    @Test
    public void validatesBeforeReplacingThePendingScan() {
        BardigunSerialEndpoint endpoint = new BardigunSerialEndpoint();
        endpoint.scan("4902370501445");
        for (String invalid : new String[]{null, "123", "４９０２３７０５０１４４５", "490237050144x"}) {
            assertThrows(IllegalArgumentException.class, () -> endpoint.scan(invalid));
        }
        assertTrue(endpoint.isScanPending());
    }

    private static int readByte(BardigunSerialEndpoint endpoint) {
        endpoint.setSb(0xff);
        endpoint.startSending();
        int value = 0;
        for (int i = 0; i < 8; i++) value = (value << 1) | endpoint.sendBit();
        return value;
    }
}
