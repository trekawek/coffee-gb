package eu.rekawek.coffeegb.core.serial;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class TurboFileSerialEndpointTest {
    public static int transfer(TurboFileSerialEndpoint device, int data) {
        device.setSb(data); device.startSending(); device.setExternalTransfer(true);
        int result = 0;
        for (int bit = 0; bit < 8; bit++) {
            for (int t = 0; t < TurboFileSerialEndpoint.BIT_TICKS; t++) device.tick();
            result = (result << 1) | device.recvBit();
            assertEquals(-1, device.recvBit()); // doubled CPU polling must not double the device clock
        }
        device.setExternalTransfer(false);
        return result;
    }
    public static int[] command(TurboFileSerialEndpoint device, int command, int... parameters) {
        assertEquals(0xc6, transfer(device, 0x6c));
        transfer(device, 0x5a); transfer(device, command);
        int sum = 0x5a + command;
        for (int value : parameters) { transfer(device, value); sum += value; }
        transfer(device, -sum & 255);
        assertEquals(0xa5, transfer(device, 0x7e)); // acknowledge it without finishing sync
        assertEquals(0xe7, transfer(device, 0xf1));
        assertEquals(0xa5, transfer(device, 0x7e));
        int[] response = new int[command == 0x10 ? 9 : command == 0x40 ? 68 : 4];
        for (int i = 0; i < response.length; i++) response[i] = transfer(device, 0xf2);
        assertEquals(command, response[0]);
        assertEquals(0, (0xa5 + Arrays.stream(response).sum()) & 255);
        return response;
    }

    @Test public void attachingToAnArmedExternalPortCompletesItsPendingSync() {
        var interrupts = new eu.rekawek.coffeegb.core.cpu.InterruptManager(true);
        var port = new SerialPort(interrupts, true, new eu.rekawek.coffeegb.core.cpu.SpeedMode(true));
        port.setByte(0xff01, 0x6c); port.setByte(0xff02, 0x80);
        for (int i = 0; i < 5000; i++) port.tick();
        assertTrue(port.isExternalClockTransferActive());
        port.init(new TurboFileSerialEndpoint());
        for (int i = 0; i < 4097; i++) port.tick();
        assertFalse(port.isExternalClockTransferActive());
        assertEquals(0xc6, port.getByte(0xff01));
    }

    @Test public void readsAndWritesBothFlashMemoriesAndReportsProtection() {
        var device = new TurboFileSerialEndpoint();
        assertArrayEquals(new int[]{0x10, 0, 3, 1, 0, 0, 0, 0, 0x47}, command(device, 0x10));
        device.setCardPresent(true);
        assertEquals(5, command(device, 0x10)[3]);
        command(device, 0x20, 0);
        int[] payload = new int[66];
        for (int i = 2; i < payload.length; i++) payload[i] = i + 17;
        for (int card = 0; card < 2; card++) {
            payload[0] = 0x1f; payload[1] = 0xc0;
            command(device, 0x22, card, 0x7f);
            assertEquals(card, command(device, 0x10)[4]);
            assertEquals(0x7f, command(device, 0x10)[5]);
            command(device, 0x30, payload);
            command(device, 0x23, card, 0x7f);
            assertArrayEquals(Arrays.copyOfRange(payload, 2, 66),
                    Arrays.copyOfRange(command(device, 0x40, 0x1f, 0xc0), 3, 67));
        }
        device.setWriteProtected(true);
        assertEquals(0x8b, command(device, 0x10)[2]);
        Arrays.fill(payload, 2, payload.length, 0);
        command(device, 0x30, payload);
        assertEquals(19, command(device, 0x40, 0x1f, 0xc0)[3]);
        command(device, 0x24);
    }

    @Test public void checksumsBoundsAndAbsentCardsCannotCorruptStorage() {
        var device = new TurboFileSerialEndpoint();
        command(device, 0x22, 0, 0);
        int[] payload = new int[66];
        transfer(device, 0x6c); transfer(device, 0x5a); transfer(device, 0x30);
        for (int value : payload) transfer(device, value);
        transfer(device, 0); // wrong checksum
        assertEquals(255, device.exportImage(false)[0] & 255);
        payload[0] = 0x1f; payload[1] = 0xe0;
        assertEquals(0, command(device, 0x30, payload)[2] & 1);
        command(device, 0x22, 1, 0);
        payload[0] = payload[1] = 0;
        assertEquals(0, command(device, 0x30, payload)[2] & 1);
        assertEquals(255, device.exportImage(true)[0] & 255);
        command(device, 0x10); // error leaves the protocol resynchronizable
    }

    @Test public void advanceAddsBlockFillAndGbRejectsIt() {
        var advance = new TurboFileSerialEndpoint(true);
        command(advance, 0x22, 0, 2);
        command(advance, 0x34, 0, 64, 0x37);
        command(advance, 0x23, 0, 2);
        int[] data = command(advance, 0x40, 0, 64);
        for (int i = 3; i < 67; i++) assertEquals(0x37, data[i]);
        var gb = new TurboFileSerialEndpoint();
        transfer(gb, 0x6c); transfer(gb, 0x5a); transfer(gb, 0x34);
        command(gb, 0x10);
        assertEquals(255, gb.exportImage(false)[64] & 255);
    }

    @Test public void partialClockAndPacketResumeWithoutDuplicatingWrites() {
        var device = new TurboFileSerialEndpoint();
        device.setSb(0x6c); device.startSending(); device.setExternalTransfer(true);
        for (int i = 0; i < 256; i++) device.tick();
        var state = device.captureState();
        for (int i = 0; i < 256; i++) device.tick();
        assertEquals(1, device.recvBit());
        device.restoreState(state);
        for (int i = 0; i < 255; i++) device.tick();
        assertEquals(-1, device.recvBit());
        device.tick(); assertEquals(1, device.recvBit());
        for (int i = 1; i < 8; i++) {
            for (int t = 0; t < 512; t++) device.tick();
            device.recvBit();
        }
        transfer(device, 0x5a); transfer(device, 0x10);
        var packet = device.captureState();
        transfer(device, 0); // abort with wrong checksum
        device.restoreState(packet);
        transfer(device, 0x96); transfer(device, 0xf1); transfer(device, 0x7e);
        assertEquals(0x10, transfer(device, 0xf2));
    }

    @Test public void failedPersistenceKeepsDataForRetryAndRestoreDoesNotWriteTheHost() {
        AtomicInteger attempts = new AtomicInteger();
        var device = new TurboFileSerialEndpoint(false, bytes -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("test failure");
        });
        device.importImage(new byte[TurboFileSerialEndpoint.IMAGE_BYTES], false);
        var snapshot = device.captureState();
        command(device, 0x24);
        assertTrue(device.hasUnsavedStorage());
        device.restoreState(snapshot);
        assertEquals(1, attempts.get());
        device.disconnect();
        assertEquals(2, attempts.get());
        assertFalse(device.hasUnsavedStorage());
    }
}
