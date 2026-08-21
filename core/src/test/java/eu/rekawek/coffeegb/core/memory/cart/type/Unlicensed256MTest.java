package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Unlicensed256MTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    @Test
    public void detectsTheGbHiColBoardWithItsExactEntryPoint() throws IOException {
        byte[] data = multicartRom();
        Rom rom = new Rom(data);
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);

        assertEquals(CartridgeProperties.Mapper.UNLICENSED_256M,
                rom.getCartridgeProperties().getMapper());
        assertTrue(cartridge.getMemoryController() instanceof Unlicensed256M);

        data[0x0103] ^= 1;
        assertFalse(new Rom(data).getCartridgeProperties().getMapper()
                == CartridgeProperties.Mapper.UNLICENSED_256M);
    }

    @Test
    public void commitsTheBasePageAndInvertedSizeMaskAt7002() throws IOException {
        MemoryController mapper = new Unlicensed256M(
                new Rom(multicartRom()), Battery.NULL_BATTERY);

        mapper.setByte(0x2000, 2);
        assertEquals(2, mapper.getByte(0x4000));

        configure(mapper, 0x60, 0xe0, 0x91);

        assertEquals(0xc0, mapper.getByte(0x0000));
        assertEquals(0xc1, mapper.getByte(0x4000));
        mapper.setByte(0x2000, 0x21);
        assertEquals(0xe1, mapper.getByte(0x4000));
    }

    @Test
    public void restoresTheSelectedGameAndItsMapperState() throws IOException {
        Unlicensed256M mapper = new Unlicensed256M(
                new Rom(multicartRom()), Battery.NULL_BATTERY);
        configure(mapper, 0x60, 0xe0, 0x91);
        mapper.setByte(0x2000, 3);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2000, 7);
        mapper.restoreState(state);

        assertEquals(0xc0, mapper.getByte(0x0000));
        assertEquals(0xc3, mapper.getByte(0x4000));
    }

    private static void configure(MemoryController mapper, int page, int invertedMask, int flags) {
        mapper.setByte(0x7000, page);
        mapper.setByte(0x7001, invertedMask);
        mapper.setByte(0x7002, flags);
    }

    private static byte[] multicartRom() {
        byte[] data = new byte[0x400000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        putHeader(data, 0, "GB HiCol", 0x03, 0x01, 0x00);
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x00;
        data[0x0103] = 0x40;
        putHeader(data, 0xc0, "SELECTED", 0x19, 0x05, 0x00);
        return data;
    }

    private static void putHeader(
            byte[] data, int bank, String title, int type, int romSize, int ramSize) {
        int base = bank * 0x4000;
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[base + 0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        for (int i = 0; i < title.length(); i++) {
            data[base + 0x0134 + i] = (byte) title.charAt(i);
        }
        data[base + 0x0143] = (byte) 0x80;
        data[base + 0x0147] = (byte) type;
        data[base + 0x0148] = (byte) romSize;
        data[base + 0x0149] = (byte) ramSize;
    }
}
