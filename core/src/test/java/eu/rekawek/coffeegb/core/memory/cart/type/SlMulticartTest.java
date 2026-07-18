package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class SlMulticartTest {

    private static final int BANKS = 256;

    private static byte[] slRom() {
        byte[] rom = new byte[BANKS * 0x4000];
        byte[] title = "POKEMON_GLDAAUJ".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, rom, 0x0134, title.length);
        rom[0x0143] = (byte) 0x80;
        rom[0x0147] = 0x10;
        rom[0x0148] = 0x06;
        rom[0x0149] = 0x03;
        for (int bank = 0; bank < BANKS; bank++) {
            rom[bank * 0x4000 + 0x0200] = (byte) bank;
        }
        return rom;
    }

    private static Cartridge cartridge() throws IOException {
        return new Cartridge(new Rom(slRom()), Battery.NULL_BATTERY);
    }

    @Test
    public void detectsTheSl36In1BeforeTheBroadDuzHeuristic() throws IOException {
        byte[] rom = slRom();
        // An embedded game header is what caused the old Duz false positive.
        int[] logo = {
                0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
                0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
                0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
                0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
                0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
                0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
        };
        for (int i = 0; i < logo.length; i++) {
            rom[2 * 0x4000 + 0x0104 + i] = (byte) logo[i];
        }

        assertEquals(CartridgeProperties.Mapper.SL_MULTICART,
                new Rom(rom).getCartridgeProperties().getMapper());
    }

    @Test
    public void configurationRelocatesBothRomWindowsAndUsesMbc1ZeroRemap()
            throws IOException {
        Cartridge cart = cartridge();
        assertEquals(0, cart.getByte(0x0200));
        assertEquals(1, cart.getByte(0x4200));

        // Base bank 64, eight-bank window, MBC1 mode.
        cart.setByte(0x5000, 0xaa);
        cart.setByte(0x7000, 0x20);
        cart.setByte(0x5000, 0x55);
        cart.setByte(0x7000, 0x65);
        assertEquals(64, cart.getByte(0x0200));
        assertEquals(65, cart.getByte(0x4200));

        cart.setByte(0x2000, 3);
        assertEquals(67, cart.getByte(0x4200));
        cart.setByte(0x2000, 0);
        assertEquals(65, cart.getByte(0x4200));
        // In MBC1 mode the complete 0x2000-0x3fff range selects the bank.
        cart.setByte(0x3000, 4);
        assertEquals(68, cart.getByte(0x4200));
    }

    @Test
    public void mbc5ModeIgnoresTheCoarseBankRange() throws IOException {
        Cartridge cart = cartridge();
        cart.setByte(0x5000, 0x55);
        cart.setByte(0x7000, 0x05); // eight-bank window, MBC5 mode
        cart.setByte(0x2000, 2);
        cart.setByte(0x3000, 7);
        assertEquals(2, cart.getByte(0x4200));
    }

    @Test
    public void ramConfigurationAllocatesAnIndependentPartition() throws IOException {
        Cartridge cart = cartridge();
        cart.setByte(0x5000, 0xbb);
        cart.setByte(0x7000, 0x25); // RAM allowed, base bank 5, four-bank mask
        cart.setByte(0x5000, 0x55);
        cart.setByte(0x7000, 0x65);
        cart.setByte(0x0000, 0x0a);

        cart.setByte(0x4000, 2); // (5 & ~3) | 2 = bank 6
        cart.setByte(0xa123, 0x42);
        cart.setByte(0x4000, 1);
        assertEquals(0xff, cart.getByte(0xa123));
        cart.setByte(0x4000, 2);
        assertEquals(0x42, cart.getByte(0xa123));
    }

    @Test
    public void launchResetsBankAndRequestsTheSelectedConsoleMode() throws IOException {
        byte[] rom = slRom();
        // The selected game's header is monochrome.
        rom[64 * 0x4000 + 0x0143] = 0x00;
        Cartridge cart = new Cartridge(new Rom(rom), Battery.NULL_BATTERY);
        EventBusImpl bus = new EventBusImpl(null, null, false);
        AtomicReference<SlMulticart.ResetEvent> reset = new AtomicReference<>();
        cart.init(bus);
        bus.register(reset::set, SlMulticart.ResetEvent.class);

        cart.setByte(0x5000, 0xaa);
        cart.setByte(0x7000, 0x20);
        cart.setByte(0x2000, 7);
        cart.setByte(0x5000, 0x55);
        cart.setByte(0x7000, 0xe5); // MBC1 mode, eight-bank window, reset

        assertNotNull(reset.get());
        assertTrue(reset.get().nonCgbGame());
        assertEquals(64, cart.getByte(0x0200));
        assertEquals(65, cart.getByte(0x4200));

        // Configuration registers are no longer exposed after launch.
        cart.setByte(0x5000, 0xaa);
        cart.setByte(0x7000, 0x00);
        assertEquals(64, cart.getByte(0x0200));
    }
}
