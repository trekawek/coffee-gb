package eu.rekawek.coffeegb.core.integration.daid;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import org.junit.Test;

import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;

/** The upstream ROM+RAM case is informational; this locks in its recommended behavior. */
public class DaidRomAndRamTest {

    @Test
    public void testAlwaysEnabledTwoKilobyteRam() throws Exception {
        Rom rom = new Rom(Paths.get("src/test/resources/roms/daid/rom_and_ram.gb").toFile());
        MemoryController cartridge = new Cartridge(rom, false).getMemoryController();

        cartridge.setByte(0xa000, 0x12);
        assertEquals(0x12, cartridge.getByte(0xa000));
        assertEquals("2 KiB RAM should wrap at A800", 0x12, cartridge.getByte(0xa800));

        cartridge.setByte(0x0000, 0x00);
        cartridge.setByte(0xa800, 0x34);
        assertEquals("plain ROM+RAM should always be enabled", 0x34, cartridge.getByte(0xa000));
    }
}
