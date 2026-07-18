package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class HelitacBootWramTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    @Test
    public void clearsTheUninitializedOamShadowAfterCgbBoot() throws IOException {
        Rom rom = new Rom(helitacV001());
        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_CGB_BOOT_OAM_SHADOW));

        Gameboy gb = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setSupportBatterySave(false)
                .build();

        long limit = 40_000_000;
        while (!isOamShadowClear(gb) && limit-- > 0) {
            gb.tick();
        }
        assertTrue("boot did not reach the cartridge handoff", limit > 0);
        for (int address = 0xc000; address < 0xc0a0; address++) {
            assertEquals(Integer.toHexString(address), 0, gb.getAddressSpace().getByte(address));
        }
    }

    private static boolean isOamShadowClear(Gameboy gb) {
        for (int address = 0xc000; address < 0xc0a0; address++) {
            if (gb.getAddressSpace().getByte(address) != 0) {
                return false;
            }
        }
        return true;
    }

    @Test
    public void doesNotMatchAnotherHelitacBuild() throws IOException {
        byte[] data = helitacV001();
        data[0x014f] ^= 1;

        assertFalse(new Rom(data).getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_CGB_BOOT_OAM_SHADOW));
    }

    private static byte[] helitacV001() {
        byte[] data = new byte[0x100000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        byte[] title = "Helitac".getBytes();
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x1c;
        data[0x0148] = 0x05;
        data[0x014e] = 0x5e;
        data[0x014f] = (byte) 0xb8;
        return data;
    }
}
