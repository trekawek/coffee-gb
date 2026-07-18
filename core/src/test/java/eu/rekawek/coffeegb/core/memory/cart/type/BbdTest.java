package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BbdTest {

    private static final int[] GAROU_SECONDARY_LOGO = {
            0xf8, 0x8e, 0x54, 0x44, 0x88, 0x88, 0xd9, 0x99,
            0xe0, 0x0c, 0xf8, 0x8f, 0x08, 0x80, 0xf8, 0x8e,
            0x55, 0x55, 0xe1, 0x1e, 0x46, 0x65, 0x13, 0x35,
            0x88, 0x80, 0x44, 0x40, 0x55, 0x20, 0x11, 0x10,
            0x00, 0xe0, 0xa9, 0x80, 0x00, 0x80, 0x88, 0x80,
            0x55, 0x50, 0x42, 0x10, 0x54, 0x40, 0x59, 0x90
    };

    @Test
    public void detectsGarouSecondaryLogo() throws IOException {
        Rom rom = new Rom(bbdRom());

        assertEquals(CartridgeProperties.Mapper.BBD,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY).getMemoryController() instanceof Bbd);
    }

    @Test
    public void reordersBankAndDataBits() throws IOException {
        byte[] data = bbdRom();
        data[4 * 0x4000 + 0x0123] = 0x04;
        data[8 * 0x4000 + 0x0123] = 0x58;
        Bbd mapper = new Bbd(new Rom(data), Battery.NULL_BATTERY);

        mapper.setByte(0x2001, 0x04); // data mode 4; MBC5 also selects bank 4
        assertEquals(0x20, mapper.getByte(0x4123));

        mapper.setByte(0x2001, 0x00); // data mode 0
        mapper.setByte(0x2080, 0x03); // bank mode 3
        mapper.setByte(0x2000, 0x01); // bit 0 is routed to bank bit 3
        assertEquals(0x58, mapper.getByte(0x4123));
    }

    @Test
    public void mementoRestoresProtectionModes() throws IOException {
        byte[] data = bbdRom();
        data[4 * 0x4000 + 0x0123] = 0x04;
        Bbd mapper = new Bbd(new Rom(data), Battery.NULL_BATTERY);
        mapper.setByte(0x2001, 0x04);
        Memento<MemoryController> memento = mapper.saveToMemento();

        mapper.setByte(0x2001, 0x00);
        mapper.restoreFromMemento(memento);
        assertEquals(0x20, mapper.getByte(0x4123));
    }

    private static byte[] bbdRom() {
        byte[] rom = new byte[0x100000];
        rom[0x0147] = 0x19; // MBC5-compatible base mapper
        rom[0x0148] = 0x05;
        for (int i = 0; i < GAROU_SECONDARY_LOGO.length; i++) {
            rom[0x0184 + i] = (byte) GAROU_SECONDARY_LOGO[i];
        }
        return rom;
    }
}
