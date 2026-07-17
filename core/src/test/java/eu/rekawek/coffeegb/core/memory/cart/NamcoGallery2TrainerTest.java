package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NamcoGallery2TrainerTest {

    @Test
    public void enablesKey1ExtensionWithoutChangingItsDmgRenderingMode() throws IOException {
        Rom rom = new Rom(trainerRom());

        assertEquals(Rom.GameboyColorFlag.NON_CGB, rom.getGameboyColorFlag());
        assertTrue(rom.isLegacySpeedSwitchRequired());
        assertEquals(GameboyType.DMG, new Gameboy.GameboyConfiguration(rom).getGameboyType());
    }

    @Test
    public void doesNotReclassifyAnotherDmgMbc1Cartridge() throws IOException {
        byte[] data = trainerRom();
        data[0x3f0fc] = 0;
        Rom rom = new Rom(data);

        assertEquals(Rom.GameboyColorFlag.NON_CGB, rom.getGameboyColorFlag());
        assertFalse(rom.isLegacySpeedSwitchRequired());
    }

    @Test
    public void mapsKey1ForTheTrainerOnItsDefaultDmgConsole() throws IOException {
        Rom rom = new Rom(trainerRom());
        Gameboy gameboy = new Gameboy.GameboyConfiguration(rom).build();

        assertEquals(0x7e, gameboy.getAddressSpace().getByte(0xff4d));
        gameboy.getAddressSpace().setByte(0xff4d, 0x01);
        assertEquals(0x7f, gameboy.getAddressSpace().getByte(0xff4d));
    }

    private static byte[] trainerRom() {
        byte[] data = new byte[0x80000];
        byte[] title = "GALLERY2 +4".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0147] = 0x01;
        data[0x0148] = 0x04;
        int[] speedSwitchStub = {0x3e, 0x01, 0xe0, 0x4d, 0x10, 0x00, 0x00, 0x00,
                0xcd, 0x0d, 0x71, 0xfa, 0xf0, 0xdf, 0xc3, 0x50, 0x01};
        for (int i = 0; i < speedSwitchStub.length; i++) {
            data[0x3f0fc + i] = (byte) speedSwitchStub[i];
        }
        return data;
    }
}
