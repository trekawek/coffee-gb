package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class Mbc3Test {

    private static Mbc3 buildMbc3() throws IOException {
        byte[] data = new byte[0x200000];
        data[0x147] = 0x13; // MBC3 + RAM + battery
        data[0x148] = 0x06; // 2 MiB / 128 ROM banks
        data[0x149] = 0x03; // 32 KiB / four RAM banks
        data[0x4000] = 0x24;
        return new Mbc3(new Rom(data), Battery.NULL_BATTERY);
    }

    private static Mbc3 buildMbc30() throws IOException {
        byte[] data = new byte[0x400000];
        data[0x147] = 0x13; // MBC3 + RAM + battery
        data[0x148] = 0x07; // 4 MiB / 256 ROM banks
        data[0x149] = 0x05; // 64 KiB / eight RAM banks
        data[0x80 * 0x4000] = 0x42;
        return new Mbc3(new Rom(data), Battery.NULL_BATTERY);
    }

    @Test
    public void mbc30UsesEightBitRomBankNumbers() throws IOException {
        Mbc3 mbc30 = buildMbc30();

        mbc30.setByte(0x2000, 0x80);

        assertEquals(0x42, mbc30.getByte(0x4000));
    }

    @Test
    public void regularMbc3IgnoresTheEighthRomBankBit() throws IOException {
        Mbc3 mbc3 = buildMbc3();

        mbc3.setByte(0x2000, 0x80);

        assertEquals(0x24, mbc3.getByte(0x4000));
    }

    @Test
    public void mbc30CanSelectAllEightRamBanks() throws IOException {
        Mbc3 mbc30 = buildMbc30();
        mbc30.setByte(0x0000, 0x0a);

        mbc30.setByte(0x4000, 7);
        mbc30.setByte(0xa000, 0x42);
        mbc30.setByte(0x4000, 3);
        assertEquals(0xff, mbc30.getByte(0xa000));
        mbc30.setByte(0x4000, 7);
        assertEquals(0x42, mbc30.getByte(0xa000));
    }
}
