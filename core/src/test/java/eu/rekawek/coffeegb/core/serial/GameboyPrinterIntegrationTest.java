package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Drives a full print through the real {@link SerialPort} bit-clocking path (SB/SC register
 * writes, DIV-derived internal clock), rather than calling the endpoint directly, to prove the
 * SerialPort-to-endpoint wiring lines the packet bytes up correctly.
 */
public class GameboyPrinterIntegrationTest {

    private Gameboy gb;
    private AddressSpace mmu;

    private static byte[] loopRom() {
        byte[] rom = new byte[0x8000];
        rom[0x100] = 0x18; // JR -2: spin in place, leaving the serial registers to us
        rom[0x101] = (byte) 0xFE;
        rom[0x147] = 0x00; // ROM only
        return rom;
    }

    private int transfer(int b) {
        mmu.setByte(0xFF01, b & 0xff); // SB = byte to send
        mmu.setByte(0xFF02, 0x81); // SC = start, internal clock
        for (int t = 0; t < 100_000 && (mmu.getByte(0xFF02) & 0x80) != 0; t++) {
            gb.tick();
        }
        assertEquals("transfer did not finish", 0, mmu.getByte(0xFF02) & 0x80);
        return mmu.getByte(0xFF01);
    }

    private int[] sendPacket(int command, int[] data) {
        transfer(0x88);
        transfer(0x33);
        int checksum = command; // compression = 0
        transfer(command);
        transfer(0x00);
        int len = data.length;
        transfer(len & 0xff);
        transfer((len >> 8) & 0xff);
        checksum += (len & 0xff) + ((len >> 8) & 0xff);
        for (int b : data) {
            transfer(b);
            checksum += b;
        }
        transfer(checksum & 0xff);
        transfer((checksum >> 8) & 0xff);
        int alive = transfer(0x00);
        int status = transfer(0x00);
        return new int[] {alive, status};
    }

    @Test
    public void printThroughRealSerialPort() throws Exception {
        int[][] captured = new int[1][];
        int[] capturedHeight = new int[1];
        GameboyPrinterSerialEndpoint printer =
                new GameboyPrinterSerialEndpoint((argb, width, height, top, bottom, exposure) -> {
                    captured[0] = argb;
                    capturedHeight[0] = height;
                });

        GameboyConfiguration config =
                new GameboyConfiguration(new Rom(loopRom())).setSupportBatterySave(false);
        gb = config.build();
        gb.init(new EventBusImpl(null, null, false), printer, null);
        mmu = gb.getAddressSpace();

        int[] initReply = sendPacket(0x01, new int[0]); // INIT
        assertEquals("printer must report alive on a valid packet", 0x81, initReply[0]);

        int[] band = new int[0x280];
        band[0] = 0x80; // pixel (0,0) black plane low
        band[1] = 0x80; // pixel (0,0) black plane high
        sendPacket(0x04, band); // DATA

        sendPacket(0x02, new int[] {0x01, 0x00, 0xE4, 0x40}); // PRINT

        assertNotNull("print callback should have fired", captured[0]);
        assertEquals(16, capturedHeight[0]);
        assertEquals(0xFF000000, captured[0][0]); // black
        assertEquals(0xFFFFFFFF, captured[0][1]); // white
        assertTrue(captured[0].length == 160 * 16);
        gb.stop();
    }
}
