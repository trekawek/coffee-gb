package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AudioArtsBootTilemapTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    @Test
    public void clearsBootLogoMapWithoutClearingBootTileData() throws IOException {
        Rom rom = new Rom(audioArtsRom());
        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_BOOT_TILEMAP));

        Gameboy gb = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setSupportBatterySave(false)
                .build();

        long limit = 100;
        while (!isTilemapClear(gb) && limit-- > 0) {
            gb.tick();
        }
        assertTrue("boot did not reach the cartridge handoff", limit > 0);
        for (int address = 0x9800; address < 0xa000; address++) {
            assertEquals(Integer.toHexString(address), 0,
                    gb.getGpu().getVideoRam0().getByte(address));
        }
        assertTrue(gb.getGpu().getVideoRam0().getByte(0x8010) != 0);
    }

    @Test
    public void nearMatchDoesNotClearTheBootTilemap() throws IOException {
        byte[] data = audioArtsRom();
        data[0x0150] ^= 1;

        assertFalse(new Rom(data).getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_BOOT_TILEMAP));
    }

    private static boolean isTilemapClear(Gameboy gb) {
        for (int address = 0x9800; address < 0xa000; address++) {
            if (gb.getGpu().getVideoRam0().getByte(address) != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] audioArtsRom() {
        byte[] data = new byte[0x8000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        byte[] title = "Gameboy Music V1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0147] = 0x00;
        data[0x0148] = 0x00;
        data[0x0149] = 0x00;
        data[0x014a] = (byte) 0xde;
        data[0x014b] = (byte) 0xc0;
        data[0x014c] = 0x03;
        data[0x014d] = (byte) 0xba;
        data[0x014e] = 0x0f;
        data[0x014f] = (byte) 0x84;
        int[] entryStub = {
                0xf3, 0x31, 0xf4, 0xff, 0xc3, 0x69, 0x01, 0x33,
                0x15, 0x00, 0x40, 0x4b, 0x47, 0x6b, 0x48, 0x00
        };
        for (int i = 0; i < entryStub.length; i++) {
            data[0x0150 + i] = (byte) entryStub[i];
        }
        return data;
    }
}
