package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BhgosMulticartTest {

    @Test
    public void detectsMulticartAndRelocatesBothRomWindows() throws IOException {
        Cartridge cartridge = new Cartridge(new Rom(multicartRom()), Battery.NULL_BATTERY);
        MemoryController mapper = cartridge.getMemoryController();

        assertTrue(mapper instanceof BhgosMulticart);
        assertEquals(0, mapper.getByte(0x0000));

        // The menu can inspect all appended banks before choosing a game.
        mapper.setByte(0x2000, 5);
        assertEquals(5, mapper.getByte(0x4000));

        mapper.setByte(0x6100, 0xd0); // unlock write
        mapper.setByte(0x6000, 2);    // second embedded 32 KiB block
        mapper.setByte(0x6000, 0);    // menu's final latch write

        assertEquals(4, mapper.getByte(0x0000));
        assertEquals(5, mapper.getByte(0x4000));

        // Bank requests made by the embedded game are relative to its new bank 0.
        mapper.setByte(0x2000, 2);
        assertEquals(6, mapper.getByte(0x4000));
    }

    @Test
    public void providesFourAlwaysWritableRamBanks() throws IOException {
        MemoryController mapper = new BhgosMulticart(new Rom(multicartRom()), Battery.NULL_BATTERY);

        mapper.setByte(0x4000, 3);
        mapper.setByte(0xa123, 0x5a);
        assertEquals(0x5a, mapper.getByte(0xa123));

        mapper.setByte(0x4000, 0);
        assertEquals(0xff, mapper.getByte(0xa123));
    }

    private static byte[] multicartRom() {
        byte[] data = new byte[8 * 0x4000];
        for (int bank = 0; bank < 8; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        byte[] title = "MultiCart".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x134, title.length);
        data[0x143] = (byte) 0x80;
        data[0x147] = 0x1a; // MBC5 + RAM
        data[0x148] = 0x00; // the menu only declares its own 32 KiB
        return data;
    }
}
