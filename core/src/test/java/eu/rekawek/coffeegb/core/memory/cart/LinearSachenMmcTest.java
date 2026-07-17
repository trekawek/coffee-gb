package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Alternate straight-through Sachen MMC2 header wiring used by issue #187. */
public class LinearSachenMmcTest {

    private static final int[] NINTENDO_LOGO = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89,
            0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
            0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E};

    private static byte[] linearSachenRom() {
        byte[] rom = new byte[0x10000];
        rom[0x100] = 0x00;
        rom[0x101] = (byte) 0xc3;
        rom[0x102] = 0x50;
        rom[0x103] = 0x01;

        // Sachen's replacement logo is stored linearly in the ordinary header window.
        rom[0x104] = 0x7c;
        rom[0x105] = (byte) 0xe7;
        rom[0x106] = (byte) 0xc0;
        rom[0x107] = 0x00;
        // In CGB-locked mode RA7 selects the genuine logo in the upper half of the page.
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            rom[0x184 + i] = (byte) NINTENDO_LOGO[i];
        }
        return rom;
    }

    private static Cartridge build() throws IOException {
        return new Cartridge(new Rom(linearSachenRom()), Battery.NULL_BATTERY);
    }

    @Test
    public void detectedAsSachenMmc() throws IOException {
        assertTrue(build().getSachenMmc() instanceof SachenMmc);
    }

    @Test
    public void cgbLockoutServesLinearNintendoLogoThenLinearGameHeader() throws IOException {
        Cartridge cart = build();
        SachenMmc mapper = cart.getSachenMmc();

        // The CGB boot ROM's WRAM access moves MMC2 directly to CGB-locked mode.
        mapper.onHighBusWrite();
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            assertEquals(NINTENDO_LOGO[i], cart.getByte(0x104 + i));
        }

        // The following header-page transition unlocks the mapper. The game entry point
        // remains linear; in particular, A0 is not swapped with A6 on this board.
        assertEquals(0x00, cart.getByte(0x100));
        assertEquals(0xc3, cart.getByte(0x101));
    }

    @Test
    public void usesSachenBankRegister() throws IOException {
        Cartridge cart = build();
        byte[] rom = linearSachenRom();
        rom[0x6000] = 0x11; // bank 1
        rom[0xe000] = 0x33; // bank 3
        cart = new Cartridge(new Rom(rom), Battery.NULL_BATTERY);

        assertEquals(0x11, cart.getByte(0x6000));
        cart.setByte(0x2000, 0x03);
        assertEquals(0x33, cart.getByte(0x6000));
    }
}
