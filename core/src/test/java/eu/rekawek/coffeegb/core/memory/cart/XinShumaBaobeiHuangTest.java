package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.Mbc5;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class XinShumaBaobeiHuangTest {

    @Test
    public void usesMbc5WiringDespiteTheMbc1Header() throws IOException {
        Rom rom = new Rom(xinShumaRom());

        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY).getMemoryController()
                instanceof Mbc5);
    }

    @Test
    public void ramBankWritesDoNotReplaceTheSelectedRomBank() throws IOException {
        MemoryController mapper = new Cartridge(
                new Rom(xinShumaRom()), Battery.NULL_BATTERY).getMemoryController();

        mapper.setByte(0x2000, 0x21);
        assertEquals(0x21, mapper.getByte(0x4123));

        mapper.setByte(0x4000, 0x02);
        assertEquals(0x21, mapper.getByte(0x4123));
    }

    private static byte[] xinShumaRom() {
        byte[] data = new byte[0x200000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000 + 0x0123] = (byte) bank;
        }
        byte[] title = "POKEMON YELLOW".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0144] = 0x30;
        data[0x0145] = 0x31;
        data[0x0146] = 0x03;
        data[0x0147] = 0x01; // misleading MBC1 header
        data[0x0148] = 0x01;
        data[0x0149] = 0x01;
        data[0x014a] = 0x00;
        data[0x014b] = 0x33;
        data[0x014c] = 0x00;
        data[0x014d] = 0x38;
        data[0x014e] = 0x33;
        data[0x014f] = (byte) 0xcd;
        return data;
    }
}
