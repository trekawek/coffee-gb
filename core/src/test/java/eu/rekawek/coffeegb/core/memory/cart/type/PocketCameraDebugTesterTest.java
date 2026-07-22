package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PocketCameraDebugTesterTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    private static final int[] ENTRY_STUB = {
            0xf3, 0xaf, 0xe0, 0x42, 0xe0, 0x43, 0xe0, 0xa0,
            0xe0, 0x41, 0xe0, 0x01, 0xe0, 0x02, 0xe0, 0x4a,
            0xe0, 0x4b, 0xe0, 0x06, 0x31, 0xff, 0xdf, 0xaf,
            0x21, 0x00, 0x80, 0x01, 0x00, 0x20, 0x22, 0x0d
    };

    @Test
    public void routesDebugTesterWithMbc1HeaderThroughPocketCameraHardware()
            throws IOException {
        Rom rom = new Rom(debugTesterRom());
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);
        MemoryController mapper = cartridge.getMemoryController();

        assertEquals(CartridgeProperties.Mapper.POCKET_CAMERA,
                rom.getCartridgeProperties().getMapper());
        assertTrue(mapper instanceof PocketCamera);

        // The Movie test starts a capture and polls camera register 0 until its busy bit
        // clears. MBC1 returns open bus here, leaving the test stuck on 0xff forever.
        mapper.setByte(0x4000, 0x10);
        mapper.setByte(0xa000, 0x01);
        assertEquals(0, mapper.getByte(0xa000) & 0x01);
    }

    @Test
    public void doesNotReclassifyAnUnrelatedUntitledMbc1Rom() throws IOException {
        byte[] data = debugTesterRom();
        data[0x0150] = 0x00;

        Rom rom = new Rom(data);
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);

        assertEquals(CartridgeProperties.Mapper.STANDARD,
                rom.getCartridgeProperties().getMapper());
        assertTrue(cartridge.getMemoryController() instanceof Mbc1);
    }

    private static byte[] debugTesterRom() {
        byte[] data = new byte[0x100000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        copy(data, 0x0104, NINTENDO_LOGO);
        data[0x0144] = 0x30;
        data[0x0145] = 0x31;
        data[0x0146] = 0x03;
        data[0x0147] = 0x01;
        data[0x0148] = 0x03;
        data[0x0149] = 0x00;
        data[0x014a] = 0x00;
        data[0x014b] = 0x33;
        data[0x014c] = 0x00;
        data[0x014d] = 0x4c;
        data[0x014e] = 0x00;
        data[0x014f] = 0x00;
        copy(data, 0x0150, ENTRY_STUB);
        return data;
    }

    private static void copy(byte[] target, int offset, int[] source) {
        for (int i = 0; i < source.length; i++) {
            target[offset + i] = (byte) source[i];
        }
    }
}
