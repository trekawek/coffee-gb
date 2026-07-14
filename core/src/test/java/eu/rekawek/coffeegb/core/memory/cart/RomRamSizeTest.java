package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class RomRamSizeTest {

    @Test
    public void supportsMbc30RamSize() throws IOException {
        byte[] data = new byte[0x8000];
        data[0x147] = 0x13; // MBC3 + RAM + battery
        data[0x149] = 0x05; // 64 KiB / eight banks

        assertEquals(8, new Rom(data).getRamBanks());
    }
}
