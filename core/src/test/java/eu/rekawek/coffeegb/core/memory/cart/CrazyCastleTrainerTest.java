package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class CrazyCastleTrainerTest {

    @Test
    public void classifiesDmgOnlyTrainerAsNonCgb() throws IOException {
        Rom rom = new Rom(trainerRom());

        assertEquals(Rom.GameboyColorFlag.NON_CGB, rom.getGameboyColorFlag());
        assertEquals(GameboyType.DMG, new Gameboy.GameboyConfiguration(rom).getGameboyType());
    }

    @Test
    public void doesNotReclassifyOtherUniversalMbc5Carts() throws IOException {
        byte[] data = trainerRom();
        data[0x00e0] = 0;
        Rom rom = new Rom(data);

        assertEquals(Rom.GameboyColorFlag.UNIVERSAL, rom.getGameboyColorFlag());
        assertEquals(GameboyType.CGB, new Gameboy.GameboyConfiguration(rom).getGameboyType());
    }

    private static byte[] trainerRom() {
        byte[] data = new byte[0x100000];
        byte[] title = "BUGS CC3 CRACK".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x19;
        data[0x0148] = 0x05;
        int[] entryStub = {0xf5, 0x3e, 0x03, 0xea, 0x00, 0x21, 0xf1, 0xc3, 0x00, 0x68};
        for (int i = 0; i < entryStub.length; i++) {
            data[0x00e0 + i] = (byte) entryStub[i];
        }
        return data;
    }
}
