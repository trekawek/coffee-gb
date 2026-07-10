package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The Pocket Voice V2.0 voice recorder (issue #71) carries the custom cartridge-type byte 0xBE.
 * It is MBC5-compatible for everything the Game Boy can observe - it banks 32x16 KB normally and
 * drives its external voice chip through write-only commands at 0x6000 (command) / 0x7000
 * (strobe), which MBC5 ignores. The audio lives inside an external analog voice IC and never
 * crosses the cartridge bus, so there is nothing further to emulate; these tests pin down that
 * the cart is recognised and behaves as MBC5.
 */
public class PocketVoiceTest {

    private static byte[] pocketVoiceRom() {
        byte[] rom = new byte[0x80000]; // 512 KB, 32 banks
        rom[0x100] = 0x00;
        rom[0x101] = (byte) 0xc3;
        rom[0x102] = 0x50;
        rom[0x103] = 0x01; // JP 0x0150
        for (int i = 0; i < TITLE.length(); i++) {
            rom[0x134 + i] = (byte) TITLE.charAt(i);
        }
        rom[0x143] = (byte) 0x80; // CGB-compatible (also the 16th title byte on the real cart)
        rom[0x147] = (byte) 0xBE; // Pocket Voice mapper byte
        rom[0x148] = 0x03; // 16 banks: the code lives in banks 0-15, 16-31 are blank voice space
        // a distinct marker in every 16 KB bank so we can tell which is mapped
        for (int bank = 0; bank < rom.length / 0x4000; bank++) {
            rom[bank * 0x4000 + 0x2000] = (byte) bank;
        }
        return rom;
    }

    // the real cart stamps exactly 15 chars, so the 16th title byte (0x143) is the CGB flag 0x80
    private static final String TITLE = "Pocket Voice2.0";

    private static Cartridge build() throws IOException {
        return new Cartridge(new Rom(pocketVoiceRom()), Battery.NULL_BATTERY);
    }

    @Test
    public void recognisedAndHandledAsMbc5() throws IOException {
        Rom rom = new Rom(pocketVoiceRom());
        assertTrue(rom.getTitle().startsWith(TITLE));
        assertTrue("Pocket Voice must fall back to MBC5 banking", rom.getType().isMbc5());
    }

    @Test
    public void banksLikeMbc5() throws IOException {
        Cartridge cart = build();
        // 0x0000-0x3FFF is fixed bank 0; the switchable window powers on at bank 1
        assertEquals(0x00, cart.getByte(0x2000));
        assertEquals(0x01, cart.getByte(0x6000));
        cart.setByte(0x2000, 0x0a); // MBC5 low bank register
        assertEquals(0x0a, cart.getByte(0x6000));
        cart.setByte(0x2000, 0x0f); // bank 15, the last program bank (header defines 16 banks)
        assertEquals(0x0f, cart.getByte(0x6000));
    }

    @Test
    public void voiceChipCommandWritesAreHarmless() throws IOException {
        Cartridge cart = build();
        cart.setByte(0x2000, 0x05); // select a known bank first
        // the voice chip protocol: command byte to 0x6000, then strobe 0x7000 - MBC5 ignores
        // both, so the mapped bank must be undisturbed and nothing may throw
        cart.setByte(0x6000, 0x0c);
        cart.setByte(0x7000, 0x00);
        assertEquals(0x05, cart.getByte(0x6000));
    }
}
