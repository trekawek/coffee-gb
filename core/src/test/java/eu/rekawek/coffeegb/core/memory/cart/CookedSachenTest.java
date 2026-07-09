package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Detection and banking of "cooked" (post-unlock) Sachen dumps (issues #73/#75): the Rocket
 * Games / DAG carts (ATV Racing, Full Time Soccer, Pocket Smash Out, ...). They carry no
 * Nintendo logo, hold the Sachen descrambled boot header at 0x0104, and power on at base
 * bank 0 with their bank register at 0x2000-0x3FFF.
 */
public class CookedSachenTest {

    // the cart-independent descrambled header every cooked Sachen dump stores at 0x0104
    private static final int[] SACHEN_HEADER = {0x11, 0x23, 0xf1, 0x1e, 0x01, 0x22, 0xf0, 0x00,
            0x08, 0x99, 0x78, 0x00, 0x08, 0x11, 0x9a, 0x48};

    private static final int LOGO_FIRST_BYTE = 0xCE; // first Nintendo logo byte

    private static byte[] cookedSachenRom() {
        return cookedSachenRom(0x40000); // 256 KB, 16 banks
    }

    private static byte[] cookedSachenRom(int size) {
        byte[] rom = new byte[size];
        rom[0x100] = 0x00;
        rom[0x101] = (byte) 0xc3;
        rom[0x102] = 0x50;
        rom[0x103] = 0x01; // JP 0x0150
        for (int i = 0; i < SACHEN_HEADER.length; i++) {
            rom[0x104 + i] = (byte) SACHEN_HEADER[i];
        }
        rom[0x147] = (byte) 0x99; // bogus Sachen mapper byte
        rom[0x148] = 0x04;
        // a distinct marker in the middle of every 16 KB bank so we can tell which is mapped
        for (int bank = 0; bank < size / 0x4000; bank++) {
            rom[bank * 0x4000 + 0x2000] = (byte) (0xA0 + bank);
        }
        return rom;
    }

    private static Cartridge build() throws IOException {
        return build(0x40000);
    }

    private static Cartridge build(int size) throws IOException {
        return new Cartridge(new Rom(cookedSachenRom(size)), Battery.NULL_BATTERY);
    }

    @Test
    public void detectedAsSachenMmc() throws IOException {
        assertTrue(build().getSachenMmc() instanceof SachenMmc);
    }

    @Test
    public void bootRomSeesTheNintendoLogo() throws IOException {
        // before the game runs, the logo window returns the Nintendo logo so the authentic
        // boot ROM's logo check passes even though the dump stores code there
        Cartridge cart = build();
        assertEquals(LOGO_FIRST_BYTE, cart.getByte(0x0104));
        assertEquals(0x3E, cart.getByte(0x0133)); // last logo byte
    }

    @Test
    public void firstWriteRevealsRealRomBytes() throws IOException {
        Cartridge cart = build();
        cart.setByte(0x2000, 0x01); // any cartridge write ends the boot-logo shim
        assertEquals(SACHEN_HEADER[0], cart.getByte(0x0104));
    }

    @Test
    public void bootsAtBaseBankZero() throws IOException {
        Cartridge cart = build();
        // 0x0000-0x3FFF maps base bank 0, the switchable window defaults to bank 1
        assertEquals(0xA0, cart.getByte(0x2000));
        assertEquals(0xA1, cart.getByte(0x6000));
    }

    @Test
    public void bankRegisterAt3F00SelectsSwitchableBank() throws IOException {
        Cartridge cart = build();
        cart.setByte(0x3F00, 0x06); // Sachen bank register (0x2000-0x3FFF)
        assertEquals(0xA6, cart.getByte(0x6000));
        assertEquals(0xA0, cart.getByte(0x2000)); // base window unaffected
    }

    @Test
    public void outerRegisterSelectsSecondGameOfMulticart() throws IOException {
        // the 2-in-1 carts remap to the second 256 KB game with a single write of 1 to a
        // 0x2000-0x3FFF address with A6 set (0x3FC0), from their HRAM launcher; base bank
        // becomes 16 and the game's own bank writes (0-15) resolve inside its half
        Cartridge cart = build(0x80000); // 512 KB, two 256 KB games
        cart.setByte(0x3FC0, 0x01);
        assertEquals(0xB0, cart.getByte(0x2000)); // 0x0000-0x3FFF -> bank 16 (second game)
        assertEquals(0xB1, cart.getByte(0x6000)); // switchable defaults to game-relative bank 1
        cart.setByte(0x3F00, 0x05); // the game selects its own bank 5
        assertEquals(0xB5, cart.getByte(0x6000)); // -> physical bank 21, still in its half
    }

    @Test
    public void normalBankRegisterIgnoresA6Address() throws IOException {
        // a plain bank write (A6 clear, e.g. 0x3F00) must not be mistaken for the outer select
        Cartridge cart = build(0x80000);
        cart.setByte(0x3F00, 0x06);
        assertEquals(0xA0, cart.getByte(0x2000)); // base window still game 1, bank 0
        assertEquals(0xA6, cart.getByte(0x6000)); // switchable bank 6
    }

    @Test
    public void mementoRoundTripPreservesBankAndLogoShim() throws IOException {
        Cartridge cart = build();
        cart.setByte(0x3F00, 0x05);
        Memento<Cartridge> memento = cart.saveToMemento();

        Cartridge other = build();
        other.setByte(0x3F00, 0x0a); // diverge
        other.restoreFromMemento(memento);
        assertEquals(0xA5, other.getByte(0x6000));
        // the logo shim was already off (we wrote before saving); it stays off after restore
        assertEquals(SACHEN_HEADER[0], other.getByte(0x0104));
    }
}
