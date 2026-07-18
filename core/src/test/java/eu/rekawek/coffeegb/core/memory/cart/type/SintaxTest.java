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

public class SintaxTest {

    private static final int[] CRASH_II_SECONDARY_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0x99, 0x89, 0x93, 0x73, 0x22, 0x0d, 0x44, 0x0b,
            0xcc, 0xc3, 0xcd, 0xc3, 0x3f, 0x3c, 0x20, 0x2f,
            0x57, 0x78, 0x22, 0x87, 0x66, 0xe1, 0xdd, 0x1e
    };

    @Test
    public void detectsCrashIiSecondaryLogo() throws IOException {
        Rom rom = new Rom(sintaxRom());

        assertEquals(CartridgeProperties.Mapper.SINTAX,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY).getMemoryController() instanceof Sintax);
    }

    @Test
    public void appliesBankPermutationAndPerBankXor() throws IOException {
        byte[] data = sintaxRom();
        data[64 * 0x4000 + 0x0123] = 0x55;
        Sintax mapper = new Sintax(new Rom(data), Battery.NULL_BATTERY);

        mapper.setByte(0x7030, 0x22); // XOR value selected by unpermuted bank 1
        mapper.setByte(0x5010, 0x00); // mode 0
        mapper.setByte(0x2000, 0x01); // bank bit 0 is routed to bit 6

        assertEquals(0x77, mapper.getByte(0x4123));
    }

    @Test
    public void mementoRestoresProtectionState() throws IOException {
        byte[] data = sintaxRom();
        data[64 * 0x4000 + 0x0123] = 0x55;
        Sintax mapper = new Sintax(new Rom(data), Battery.NULL_BATTERY);
        mapper.setByte(0x7030, 0x22);
        mapper.setByte(0x5010, 0x00);
        mapper.setByte(0x2000, 0x01);
        Memento<MemoryController> memento = mapper.saveToMemento();

        mapper.setByte(0x7030, 0x00);
        mapper.setByte(0x2000, 0x02);
        mapper.restoreFromMemento(memento);
        assertEquals(0x77, mapper.getByte(0x4123));
    }

    private static byte[] sintaxRom() {
        byte[] rom = new byte[0x200000];
        rom[0x0147] = 0x19; // MBC5-compatible base mapper
        rom[0x0148] = 0x06;
        for (int i = 0; i < CRASH_II_SECONDARY_LOGO.length; i++) {
            rom[0x0184 + i] = (byte) CRASH_II_SECONDARY_LOGO[i];
        }
        return rom;
    }
}
