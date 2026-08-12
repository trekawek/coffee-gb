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

public class HitekTest {

    private static final int[][] DATA_REORDERING = {
            {0, 1, 2, 3, 4, 5, 6, 7},
            {0, 6, 5, 3, 4, 1, 2, 7},
            {0, 5, 6, 3, 4, 2, 1, 7},
            {0, 6, 2, 3, 4, 5, 1, 7},
            {0, 6, 1, 3, 4, 5, 2, 7},
            {0, 1, 6, 3, 4, 5, 2, 7},
            {0, 2, 6, 3, 4, 1, 5, 7},
            {0, 6, 2, 3, 4, 1, 5, 7}
    };

    private static final int[][] BANK_REORDERING = {
            {0, 1, 2, 3, 4, 5, 6, 7},
            {3, 2, 1, 0, 4, 5, 6, 7},
            {2, 1, 0, 3, 4, 5, 6, 7},
            {1, 0, 3, 2, 4, 5, 6, 7},
            {0, 3, 2, 1, 4, 5, 6, 7},
            {2, 3, 0, 1, 4, 5, 6, 7},
            {3, 0, 1, 2, 4, 5, 6, 7},
            {2, 0, 3, 1, 4, 5, 6, 7}
    };

    @Test
    public void detectsHitekSecondaryLogo() throws IOException {
        Rom rom = new Rom(hitekRom());

        assertEquals(CartridgeProperties.Mapper.HITEK,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof Hitek);
    }

    @Test
    public void appliesPowerOnAndProgrammedPermutations() throws IOException {
        byte[] data = hitekRom();
        data[1 * 0x4000 + 0x0123] = 0x02;
        data[2 * 0x4000 + 0x0123] = 0x40;
        data[4 * 0x4000 + 0x0123] = 0x04;
        Hitek mapper = new Hitek(new Rom(data), Battery.NULL_BATTERY);

        // Power-on bank mode 7 routes bank bit 0 to bit 1, and data mode 7
        // routes input bit 6 to output bit 1.
        mapper.setByte(0x2000, 0x01);
        assertEquals(0x02, mapper.getByte(0x4123));

        // Programming the control ports also performs their underlying MBC5
        // bank writes, so mode 1 selects bank 1 before the next explicit write.
        mapper.setByte(0x2001, 0x01);
        assertEquals(0x20, mapper.getByte(0x4123));
        mapper.setByte(0x2080, 0x01);
        assertEquals(0x20, mapper.getByte(0x4123));
        mapper.setByte(0x2000, 0x02);
        assertEquals(0x40, mapper.getByte(0x4123));
    }

    @Test
    public void ignoresMbc5HighBankWrites() throws IOException {
        byte[] data = hitekRom(129, 0x06);
        data[0x4000] = 0x11;
        data[128 * 0x4000] = 0x22;
        Hitek mapper = new Hitek(new Rom(data), Battery.NULL_BATTERY);
        mapper.setByte(0x2001, 0x00);
        mapper.setByte(0x2080, 0x00);
        mapper.setByte(0x2000, 0x01);

        mapper.setByte(0x3001, 0x01);

        assertEquals(0x11, mapper.getByte(0x4000));
    }

    @Test
    public void supportsEveryDataAndBankPermutationMode() throws IOException {
        byte[] data = hitekRom(256, 0x07);
        int markerOffset = 0x0123;
        for (int mode = 0; mode < 8; mode++) {
            for (int bit = 0; bit < 8; bit++) {
                data[mode * 0x4000 + markerOffset + bit] = (byte) (1 << bit);
            }
        }
        int bankMarkerOffset = 0x0200;
        for (int bank = 0; bank < 256; bank++) {
            data[bank * 0x4000 + bankMarkerOffset] = (byte) bank;
        }
        Hitek mapper = new Hitek(new Rom(data), Battery.NULL_BATTERY);

        for (int mode = 0; mode < 8; mode++) {
            mapper.setByte(0x2001, mode);
            for (int bit = 0; bit < 8; bit++) {
                int oneHot = 1 << bit;
                assertEquals(reorderBits(oneHot, DATA_REORDERING[mode]),
                        mapper.getByte(0x4000 + markerOffset + bit));
            }
        }

        mapper.setByte(0x2001, 0x00);
        for (int mode = 0; mode < 8; mode++) {
            mapper.setByte(0x2080, mode);
            for (int bit = 0; bit < 8; bit++) {
                int oneHot = 1 << bit;
                mapper.setByte(0x2000, oneHot);
                assertEquals(reorderBits(oneHot, BANK_REORDERING[mode]),
                        mapper.getByte(0x4000 + bankMarkerOffset));
            }
        }
    }

    @Test
    public void stateRoundTripPreservesProtectionModes() throws IOException {
        byte[] data = hitekRom();
        data[4 * 0x4000 + 0x0123] = 0x02;
        Hitek mapper = new Hitek(new Rom(data), Battery.NULL_BATTERY);
        mapper.setByte(0x2001, 0x04);
        mapper.setByte(0x2080, 0x01);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2001, 0x00);
        mapper.setByte(0x2080, 0x00);
        mapper.restoreState(state);
        mapper.setByte(0x2000, 0x02);

        assertEquals(0x04, mapper.getByte(0x4123));
    }

    private static byte[] hitekRom() {
        return hitekRom(128, 0x06);
    }

    private static byte[] hitekRom(int banks, int sizeCode) {
        byte[] data = new byte[banks * 0x4000];
        data[0x0147] = 0x1b;
        data[0x0148] = (byte) sizeCode;
        data[0x0149] = 0x03;
        // Synthetic 48-byte block whose CRC-32 is the public HiTek secondary-logo
        // fingerprint. It contains no commercial ROM or logo bytes.
        byte[] prefix = "COFFEE GB HITEK CLEANROOM REGRESSION FIXTURE"
                .getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(prefix, 0, data, 0x0184, prefix.length);
        for (int i = prefix.length; i < 44; i++) {
            data[0x0184 + i] = '.';
        }
        data[0x01b0] = (byte) 0xa5;
        data[0x01b1] = 0x42;
        data[0x01b2] = (byte) 0xcf;
        data[0x01b3] = 0x17;
        return data;
    }

    private static int reorderBits(int value, int[] reorder) {
        int result = 0;
        for (int newBit = 0; newBit < 8; newBit++) {
            result |= ((value >> reorder[newBit]) & 1) << newBit;
        }
        return result;
    }
}
