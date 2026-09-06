package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.Mode;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class YugiohEarlyDaysCompatibilityTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    @Test
    public void detectsExpandedEarlyDaysImageWithoutMatchingOriginalSize() throws IOException {
        Rom expanded = new Rom(yugiohRom(0x200000));
        Rom originalSize = new Rom(yugiohRom(0x100000));

        assertTrue(expanded.getCartridgeProperties().has(
                CartridgeProperties.Feature.YUGIOH_EARLY_DAYS_CARD_VRAM_WRITES));
        assertFalse(originalSize.getCartridgeProperties().has(
                CartridgeProperties.Feature.YUGIOH_EARLY_DAYS_CARD_VRAM_WRITES));
    }

    @Test
    public void onlyExpandedImageAcceptsCardTileWritesDuringMode3() throws IOException {
        assertEquals(0x99, mode3VramValue(yugiohRom(0x200000)));
        assertEquals(0x42, mode3VramValue(yugiohRom(0x100000)));
    }

    private static int mode3VramValue(byte[] data) throws IOException {
        try (Gameboy gameboy = new Gameboy.GameboyConfiguration(new Rom(data))
                .setSupportBatterySave(false)
                .build()) {
            Gpu gpu = gameboy.getGpu();
            gpu.setByte(0x8000, 0x42);
            while (gpu.getMode() != Mode.PixelTransfer) {
                gpu.tick();
            }
            gpu.setByteFromCpu(0x8000, 0x99);
            return gpu.getVideoRam0().getByte(0x8000);
        }
    }

    private static byte[] yugiohRom(int size) {
        byte[] data = new byte[size];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        byte[] title = "YUGI TEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0144] = 'A';
        data[0x0145] = '4';
        data[0x0146] = 0x03;
        data[0x0147] = 0x03;
        data[0x0148] = 0x06;
        data[0x0149] = 0x02;
        data[0x014a] = 0x00;
        data[0x014c] = 0x00;
        return data;
    }
}
