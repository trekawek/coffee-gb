package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GameboyAchievementMemoryTest {

    @Test
    public void exposesFixedBaseBanksAndExtendedPhysicalBanks() throws Exception {
        byte[] data = new byte[0x8000];
        data[0x0143] = (byte) 0x80; // CGB-compatible
        data[0x0147] = 0x1a; // MBC5 + RAM
        data[0x0149] = 0x03; // four 8 KiB RAM banks

        try (Gameboy gameboy = new Gameboy(new Rom(data))) {
            var memory = gameboy.getAddressSpace();

            memory.setByte(0x0000, 0x0a); // enable cartridge RAM
            memory.setByte(0x4000, 0);
            memory.setByte(0xa000, 0x11);
            memory.setByte(0x4000, 1);
            memory.setByte(0xa000, 0x22);

            assertEquals(0x22, memory.getByte(0xa000));
            assertEquals(0x11, gameboy.readMemoryForAchievements(0xa000));
            assertEquals(0x22, gameboy.readMemoryForAchievements(0x16000));

            memory.setByte(0xff70, 1);
            memory.setByte(0xd000, 0x33);
            memory.setByte(0xff70, 2);
            memory.setByte(0xd000, 0x44);

            assertEquals(0x44, memory.getByte(0xd000));
            assertEquals(0x33, gameboy.readMemoryForAchievements(0xd000));
            assertEquals(0x44, gameboy.readMemoryForAchievements(0x10000));
        }
    }
}
