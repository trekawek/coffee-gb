package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

public class DarkFaderThreadsDemoTest {

    @Test
    public void classifiesPaletteLessDemoAsDmgOnly() throws IOException {
        Rom rom = new Rom(threadsDemoRom());

        assertEquals(Rom.GameboyColorFlag.NON_CGB, rom.getGameboyColorFlag());
        assertEquals(GameboyType.DMG, new Gameboy.GameboyConfiguration(rom).getGameboyType());
    }

    @Test
    public void doesNotReclassifyAnotherUniversalMbc1Rom() throws IOException {
        byte[] data = threadsDemoRom();
        data[0x0102] = 0;
        Rom rom = new Rom(data);

        assertEquals(Rom.GameboyColorFlag.UNIVERSAL, rom.getGameboyColorFlag());
        assertEquals(GameboyType.CGB, new Gameboy.GameboyConfiguration(rom).getGameboyType());
    }

    private static byte[] threadsDemoRom() {
        byte[] data = new byte[0x10000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = (byte) 0x91;
        data[0x0103] = 0x09;
        data[0x0134] = 'O';
        data[0x0135] = 'S';
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x01;
        data[0x0149] = 0x00;
        data[0x014c] = 0x01;
        data[0x014e] = 0x7b;
        data[0x014f] = 0x1e;
        return data;
    }
}
