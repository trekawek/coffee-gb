package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.Mbc1;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FreeArtIntroV2Test {

    @Test
    public void originalIntroCanWriteMbc1RamBeforeItsFirstEnableCommand() throws IOException {
        Rom rom = new Rom(freeArtIntroRom());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.MBC1_RAM_ENABLED_AT_POWER_ON));

        Mbc1 mapper = new Mbc1(rom, Battery.NULL_BATTERY);
        mapper.setByte(0xa000, 0x5a);
        assertEquals(0x5a, mapper.getByte(0xa000));
    }

    @Test
    public void ordinaryMbc1RamStillStartsFilledWithFf() throws IOException {
        byte[] data = freeArtIntroRom();
        data[0x0150] = 0x00;
        Rom rom = new Rom(data);

        assertFalse(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.MBC1_RAM_ENABLED_AT_POWER_ON));

        Mbc1 mapper = new Mbc1(rom, Battery.NULL_BATTERY);
        mapper.setByte(0xa000, 0x5a);
        assertEquals(0xff, mapper.getByte(0xa000));
    }

    private static byte[] freeArtIntroRom() {
        byte[] data = new byte[0x8000];
        byte[] title = "FreeArt Intro 2".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x02;
        data[0x0148] = 0x00;
        data[0x0149] = 0x03;
        data[0x014e] = (byte) 0xc1;
        data[0x014f] = 0x1d;
        int[] entryStub = {
                0xf3, 0x31, 0xff, 0xff, 0x97, 0xe0, 0x40, 0xe0,
                0x42, 0xe0, 0x43, 0x3c, 0xe0, 0x4d, 0xe0, 0xff,
                0x10, 0x00, 0xcd, 0x78, 0x16
        };
        for (int i = 0; i < entryStub.length; i++) {
            data[0x0150 + i] = (byte) entryStub[i];
        }
        return data;
    }
}
