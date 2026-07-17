package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class Mbc2Test {

    @Test
    public void regularMbc2RequiresAddressBit8ForRomBankWrites() throws IOException {
        byte[] data = rom();
        data[4 * 0x4000] = 0x24;
        data[12 * 0x4000] = 0x12;
        Mbc2 mbc = new Mbc2(new Rom(data), Battery.NULL_BATTERY);

        mbc.setByte(0x2000, 12);

        assertEquals(0, mbc.getByte(0x4000));
        mbc.setByte(0x2100, 12);
        assertEquals(0x12, mbc.getByte(0x4000));
        mbc.setByte(0x2100, 20);
        assertEquals(0x24, mbc.getByte(0x4000));
    }

    @Test
    public void gokuuHishoudenTranslationUsesLegacyExtendedBanking() throws IOException {
        byte[] data = rom();
        byte[] title = "GB DBZ GOKOU".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        int[] entry = {0x00, 0xc3, 0xf0, 0x3f};
        int[] bankStub = {0xf5, 0x3e, 0x0c, 0xea, 0x00, 0x20, 0xf1, 0xc3, 0x00, 0x70};
        copy(entry, data, 0x0100);
        copy(bankStub, data, 0x3ff0);
        data[12 * 0x4000] = 0x34;
        data[20 * 0x4000] = 0x56;
        Mbc2 mbc = new Mbc2(new Rom(data), Battery.NULL_BATTERY);

        mbc.setByte(0x2000, 12);

        assertEquals(0x34, mbc.getByte(0x4000));
        mbc.setByte(0x2000, 20);
        assertEquals(0x56, mbc.getByte(0x4000));
    }

    private static byte[] rom() {
        byte[] data = new byte[0x80000];
        data[0x0147] = 0x06;
        data[0x0148] = 0x04;
        return data;
    }

    private static void copy(int[] source, byte[] target, int offset) {
        for (int i = 0; i < source.length; i++) {
            target[offset + i] = (byte) source[i];
        }
    }
}
