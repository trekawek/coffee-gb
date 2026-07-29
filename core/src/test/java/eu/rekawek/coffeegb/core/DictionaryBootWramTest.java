package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DictionaryBootWramTest {

    @Test
    public void clearsOnlyTheUninitializedJoypadState() throws IOException {
        Rom rom = new Rom(dictionaryRom());
        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_DICTIONARY_JOYPAD_STATE));

        Gameboy compatible = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();

        byte[] nearMatch = dictionaryRom();
        nearMatch[0x014f] ^= 1;
        Gameboy ordinary = new Gameboy.GameboyConfiguration(new Rom(nearMatch))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();

        for (int address = 0xc000; address < 0xe000; address++) {
            if (address >= 0xd8d0 && address <= 0xd8d4) {
                assertEquals(Integer.toHexString(address), 0,
                        compatible.getAddressSpace().getByte(address));
            } else {
                assertEquals(Integer.toHexString(address),
                        ordinary.getAddressSpace().getByte(address),
                        compatible.getAddressSpace().getByte(address));
            }
        }
    }

    @Test
    public void nearMatchKeepsTheHardwarePowerOnValues() throws IOException {
        byte[] data = dictionaryRom();
        data[0x014f] ^= 1;

        Rom rom = new Rom(data);

        assertFalse(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.CLEAR_DICTIONARY_JOYPAD_STATE));
        Gameboy gb = new Gameboy.GameboyConfiguration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setSupportBatterySave(false)
                .build();
        assertEquals(0x66, gb.getAddressSpace().getByte(0xd8d0));
        assertEquals(0x7d, gb.getAddressSpace().getByte(0xd8d1));
    }

    private static byte[] dictionaryRom() {
        byte[] data = new byte[0x100000];
        byte[] title = "DICTIONARY".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x1b;
        data[0x0148] = 0x05;
        data[0x0149] = 0x01;
        data[0x014c] = 0x01;
        data[0x014d] = 0x4f;
        data[0x014e] = (byte) 0xa9;
        data[0x014f] = (byte) 0x84;
        return data;
    }
}
