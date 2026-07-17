package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.Mbc1;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class RomSizeTest {

    @Test
    public void physicalRomCanContainMoreBanksThanHeaderDeclares() throws IOException {
        byte[] data = mbc1Rom(8, 0x01); // eight physical banks, four declared
        data[7 * 0x4000 + 0x123] = 0x5a;
        Rom rom = new Rom(data);
        Mbc1 mapper = new Mbc1(rom, Battery.NULL_BATTERY);

        mapper.setByte(0x2000, 7);

        assertEquals(8, rom.getRomBanks());
        assertEquals(0x5a, mapper.getByte(0x4123));
    }

    @Test
    public void declaredCapacityIsRetainedForTruncatedRom() throws IOException {
        Rom rom = new Rom(mbc1Rom(2, 0x04)); // two physical banks, 32 declared

        assertEquals(32, rom.getRomBanks());
    }

    private static byte[] mbc1Rom(int physicalBanks, int sizeByte) {
        byte[] data = new byte[physicalBanks * 0x4000];
        data[0x147] = 0x01; // MBC1
        data[0x148] = (byte) sizeByte;
        return data;
    }
}
