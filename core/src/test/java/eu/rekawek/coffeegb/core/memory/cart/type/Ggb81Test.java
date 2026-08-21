package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class Ggb81Test {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    @Test
    public void detectsTheVastFameDigimonRelease() throws IOException {
        Rom rom = new Rom(gbb81Rom());
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);

        assertEquals(CartridgeProperties.Mapper.GGB81,
                rom.getCartridgeProperties().getMapper());
        assertTrue(cartridge.getMemoryController() instanceof Ggb81);
    }

    @Test
    public void reordersSwitchableRomDataAndRestoresTheMode() throws IOException {
        byte[] data = gbb81Rom();
        data[3 * 0x4000] = 0x22;
        Ggb81 mapper = new Ggb81(new Rom(data), Battery.NULL_BATTERY);

        mapper.setByte(0x2001, 0x03);
        assertEquals(0x06, mapper.getByte(0x4000));
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2001, 0x00);
        mapper.restoreState(state);

        assertEquals(0x06, mapper.getByte(0x4000));
    }

    private static byte[] gbb81Rom() {
        byte[] data = new byte[0x80000];
        byte[] title = "DIGIMON".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        data[0x0143] = (byte) 0x80;
        data[0x0144] = 'A';
        data[0x0145] = '7';
        data[0x0147] = 0x19;
        data[0x0148] = 0x06;
        data[0x0149] = 0x01;
        return data;
    }
}
