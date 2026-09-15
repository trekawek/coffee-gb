package eu.rekawek.coffeegb.core.serial;

import org.junit.Test;
import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import static org.junit.Assert.*;

public class SewingMachineSerialEndpointTest {
    static int exchange(SewingMachineSerialEndpoint d, int value) {
        d.setSb(value); d.startSending(); d.setExternalTransfer(true);
        int result = 0;
        for (int bit = 0; bit < 8; bit++) {
            for (int i = 0; i < 512; i++) d.tick();
            result = (result << 1) | d.recvBit();
            assertEquals(-1, d.recvBit());
        }
        d.setExternalTransfer(false);
        return result;
    }
    static void begin(SewingMachineSerialEndpoint d) {
        exchange(d, 0x80); exchange(d, 0x80); exchange(d, 0x80); exchange(d, 0x86);
    }
    static void packet(SewingMachineSerialEndpoint d, int... bytes) {
        int sum = 0;
        for (int b : bytes) { exchange(d, b); sum += b; }
        exchange(d, sum & 255); exchange(d, sum >>> 8 & 255);
    }
    static void normal(SewingMachineSerialEndpoint d) {
        begin(d);
        packet(d, 0xb9, 0, 0, 0, 0, 0, 16, 0, 0xc1, 8, 16, 24, 16, 0xbc, 0, 0xbf);
    }
    @Test public void nativeInternalPayloadThenExternalStatusSequenceWorksThroughSerialPort() {
        var d = new SewingMachineSerialEndpoint(); d.setPedal(true);
        var port = new SerialPort(new InterruptManager(false), false, new SpeedMode(false));
        port.init(d);
        int[] data = {0x80, 0x80, 0x80, 0x86, 0xb9, 0, 0, 0, 0, 0, 16, 0, 0xc1, 8, 16, 0xbf};
        int sum = 0;
        for (int i = 0; i < data.length; i++) {
            if (i >= 4) sum += data[i];
            assertEquals(i < 3 ? 0x40 : 0, nativeByte(port, data[i]));
        }
        nativeByte(port, sum & 255); nativeByte(port, sum >>> 8);
        assertTrue(d.getPatternLength() > 0); assertEquals(0, d.getRejectedPackets());
    }
    private static int nativeByte(SerialPort port, int value) {
        port.setByte(0xff01, value); port.setByte(0xff02, 0x80);
        for (int i = 0; i < 32; i++) port.tick();
        port.setByte(0xff02, 0x81);
        for (int i = 0; i < 5000 && port.isInternalClockTransferActive(); i++) port.tick();
        assertFalse(port.isInternalClockTransferActive()); assertEquals(255, port.getByte(0xff01));
        port.setByte(0xff01, 0); port.setByte(0xff02, 0x80);
        for (int i = 0; i < 5000 && port.isExternalClockTransferActive(); i++) port.tick();
        assertFalse(port.isExternalClockTransferActive());
        return port.getByte(0xff01);
    }
    @Test public void normalStitchUsesVirtualFeedThenRepeatsRealCoordinates() {
        var d = new SewingMachineSerialEndpoint(); normal(d);
        d.stitchOnce(); d.stitchOnce(); d.stitchOnce();
        assertEquals(3, d.getStitchCount());
        int[] fabric = d.copyFabric();
        assertEquals(d.getThreadColor(), fabric[36 * 512 + 224]);
        assertEquals(d.getThreadColor(), fabric[40 * 512 + 288]);
        assertEquals(d.getThreadColor(), fabric[44 * 512 + 224]);
        assertFalse(d.isFinished());
        d.setPedal(true); d.setPaused(true);
        assertEquals(0xc0, exchange(d, 0x80));
        d.setModel(2); d.requestAdvance();
        assertEquals(0xa6, exchange(d, 0x80));
        assertEquals(0xa6, exchange(d, 0x80));
        for (int i = 0; i < 4194304; i++) d.tick();
        assertEquals(0x86, exchange(d, 0x80));
        d.setLargeHoop(true); assertEquals(0x87, exchange(d, 0x80));
        d.setModel(1); assertFalse(d.isArmAttached());
    }
    @Test public void embroideryHandlesPairsAndShiftParametersSplitAcrossPackets() {
        var d = new SewingMachineSerialEndpoint(); d.setModel(2); begin(d);
        packet(d, 0xb9, 0, 0, 0, 0, 0xff, 0xff, 0xff, 0xff, 0xc1, 0x48, 0xbb);
        int[] fragment = new int[126];
        fragment[0] = 0xb9; fragment[1] = 8;
        fragment[122] = 0xbe; fragment[123] = 0xfc; fragment[124] = 0xff; fragment[125] = 0xbb;
        packet(d, fragment);
        packet(d, 0xb9, 0xfc, 0xff, 0xbd, 0x48, 8, 0xbc, 0xbf);
        for (int i = 0; i < 65; i++) d.stitchOnce();
        assertEquals(62, d.getStitchCount()); assertTrue(d.isFinished());
        assertEquals(d.getThreadColor(), d.copyFabric()[12 * 512 + 388]);
    }
    @Test public void cancelledDummyAndInternalBytesDoNotEnterParser() {
        var d = new SewingMachineSerialEndpoint();
        d.setSb(0); d.startSending(); d.setExternalTransfer(true);
        for (int i = 0; i < 20; i++) d.tick();
        d.setSb(0x80);
        int response = 0;
        for (int bit = 0; bit < 8; bit++) {
            for (int i = 0; i < 512; i++) d.tick();
            response = response << 1 | d.recvBit();
        }
        assertEquals(0, response);
        d.setExternalTransfer(false);
        normal(d);
        d.setSb(0); d.startSending();
        for (int i = 0; i < 8; i++) assertEquals(1, d.sendBit());
        d.setExternalTransfer(false);
        d.stitchOnce(); assertEquals(1, d.getStitchCount());
        assertEquals(0, d.getRejectedPackets());
    }
    @Test public void invalidChecksumDoesNotInstallPatternAndCanResynchronize() {
        var d = new SewingMachineSerialEndpoint(); begin(d);
        for (int b : new int[]{0xb9,0,0,0,0,0,0,0,0xc1,4,16,0xbf,0,0}) exchange(d,b);
        assertEquals(0,d.getPatternLength());
        normal(d); d.stitchOnce(); assertEquals(1,d.getStitchCount());
        assertEquals(1,d.getRejectedPackets());
    }
    @Test public void pathControlsCanInterruptAnUnpairedCoordinate() {
        var d = new SewingMachineSerialEndpoint(); d.setModel(2); begin(d);
        packet(d, 0xb9,0,0,0,0,0xff,0xff,0xff,0xff,0,0xc0,0xcf,0xdf,8,8,0xbc,0xbf);
        d.stitchOnce(); d.stitchOnce();
        assertEquals(1, d.getStitchCount()); assertTrue(d.isFinished());
    }
    @Test public void finalPacketMayPadAfterTheTerminatorBeforeItsChecksum() {
        var d = new SewingMachineSerialEndpoint(); d.setModel(2); begin(d);
        int[] payload = new int[126];
        int[] header = {0xb9,0,0,0,0,0xff,0xff,0xff,0xff,0xc1,8,8,0xbf};
        System.arraycopy(header, 0, payload, 0, header.length);
        packet(d, payload);
        assertEquals(0, d.getRejectedPackets()); d.stitchOnce(); d.stitchOnce();
        assertEquals(1, d.getStitchCount()); assertTrue(d.isFinished());
    }
    @Test public void snapshotRestoresPartialClockPatternAndRenderedFabric() {
        var d = new SewingMachineSerialEndpoint(); normal(d); d.stitchOnce();
        d.setSb(0x80); d.startSending(); d.setExternalTransfer(true);
        for (int i = 0; i < 200; i++) d.tick();
        var state = d.captureState(); int[] expected = d.copyFabric();
        d.clearFabric(); d.setModel(2); d.restoreState(state);
        assertArrayEquals(expected, d.copyFabric());
        for (int i = 0; i < 311; i++) d.tick(); assertEquals(-1,d.recvBit());
        d.tick(); assertEquals(0,d.recvBit());
        d.copyFabric()[0] = 0; assertArrayEquals(expected,d.copyFabric());
        d.disconnect(); assertFalse(d.isPedalPressed());
    }
}
