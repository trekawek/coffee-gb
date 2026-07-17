package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CgbTrainerBootVramTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    private static final int[] TRAINER_SIGNATURE = {
            0x7d, 0xea, 0xa1, 0xc0, 0xe6, 0x03, 0x6f, 0x01,
            0xe0, 0x01, 0xcb, 0x25, 0xcb, 0x25, 0x09, 0xe9
    };

    @Test
    public void clearsOnlyTheUninitializedBlankTileAfterCgbBoot() throws IOException {
        Rom rom = new Rom(trainerRom());
        assertTrue(rom.requiresBlankCgbBootTile());

        Gameboy gb = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                .setSupportBatterySave(false)
                .build();

        long limit = 40_000_000;
        while (!isBlankTile(gb) && limit-- > 0) {
            gb.tick();
        }
        assertTrue("boot did not reach the cartridge handoff", limit > 0);
        for (int address = 0x80a0; address < 0x80b0; address++) {
            assertEquals(Integer.toHexString(address), 0,
                    gb.getGpu().getVideoRam0().getByte(address));
        }
        // Another boot-ROM tile is deliberately left intact.
        assertTrue(gb.getGpu().getVideoRam0().getByte(0x816c) != 0);
    }

    @Test
    public void nearMatchDoesNotEnableTrainerCompatibility() throws IOException {
        byte[] data = trainerRom();
        data[0xddea4] ^= 1;

        assertFalse(new Rom(data).requiresBlankCgbBootTile());
    }

    private static boolean isBlankTile(Gameboy gb) {
        for (int address = 0x80a0; address < 0x80b0; address++) {
            if (gb.getGpu().getVideoRam0().getByte(address) != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] trainerRom() {
        byte[] data = new byte[0x100000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x00;
        data[0x0103] = 0x01;
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        data[0x0143] = (byte) 0xc0;
        data[0x0144] = 0x37;
        data[0x0145] = 0x30;
        data[0x0147] = 0x1b;
        data[0x0148] = 0x05;
        for (int i = 0; i < TRAINER_SIGNATURE.length; i++) {
            data[0xddea4 + i] = (byte) TRAINER_SIGNATURE[i];
        }
        return data;
    }
}
