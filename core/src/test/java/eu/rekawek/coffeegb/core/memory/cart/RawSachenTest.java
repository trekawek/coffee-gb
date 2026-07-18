package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memory.Mmu;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.SachenMmc;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class RawSachenTest {

    private static final int[] NINTENDO_LOGO = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89,
            0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63,
            0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E};

    @Test
    public void parsesLogicalHeaderWithoutModifyingRawImage() throws IOException {
        byte[] data = rawMmc2Rom();
        int physicalChecksum = data[unscramble(0x14d)] & 0xff;

        Rom rom = new Rom(data);

        assertEquals(Rom.GameboyColorFlag.UNIVERSAL, rom.getGameboyColorFlag());
        assertEquals(128, rom.getRomBanks());
        assertEquals(physicalChecksum, rom.getRom()[unscramble(0x14d)]);
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY).getSachenMmc() instanceof SachenMmc);
    }

    @Test
    public void cgbWramWriteEnablesNintendoLogoCopy() throws IOException {
        SachenMmc mapper = new SachenMmc(new Rom(rawMmc2Rom()), true);
        Mmu mmu = new Mmu(true);
        mmu.setBusListener(mapper);
        mmu.indexSpaces();

        mmu.setByte(0xc000, 0x42);

        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            assertEquals("logo byte " + i, NINTENDO_LOGO[i], mapper.getByte(0x0104 + i));
        }
    }

    @Test
    public void mmc2ThresholdReadUsesTheNewLockState() throws IOException {
        SachenMmc mapper = new SachenMmc(new Rom(rawMmc2Rom()), true);

        for (int i = 0; i < 0x30; i++) {
            assertEquals(NINTENDO_LOGO[0] ^ 0xff, mapper.getByte(0x0104));
        }
        // The 49th read changes DMG-locked to CGB-locked and must already use the
        // A7-high Nintendo-logo copy, matching the real mapper's combinational output.
        assertEquals(NINTENDO_LOGO[0], mapper.getByte(0x0104));

        for (int i = 0; i < 0x30; i++) {
            assertEquals(NINTENDO_LOGO[0], mapper.getByte(0x0104));
        }
        // The next threshold read unlocks the header and exposes the primary copy.
        assertEquals(NINTENDO_LOGO[0] ^ 0xff, mapper.getByte(0x0104));
    }

    @Test
    public void derivesBankCountFromRawDumpSize() throws IOException {
        assertEquals(32, new Rom(rawMmc2Rom(0x80000)).getRomBanks());
        assertEquals(128, new Rom(rawMmc2Rom(0x200000)).getRomBanks());
    }

    @Test
    public void outOfRangeBanksReadAsOpenBusRatherThanMirroring() throws IOException {
        byte[] data = rawMmc2Rom(0x80000); // 32 physical banks
        data[0x0200] = 0x42;
        SachenMmc mapper = new SachenMmc(new Rom(data), true, true, true);
        assertEquals(0x42, mapper.getByte(0x0200));

        // The Rocman X Gold startup probes banks 0x20 and 0x40 to detect the MMC2
        // board's solder options. These addresses are beyond its 32-bank ROM and must
        // see the pulled-up bus; wrapping them to bank zero makes the probe loop forever.
        mapper.setByte(0x2000, 0xff); // enable base/mask register writes
        mapper.setByte(0x0000, 0x20);
        mapper.setByte(0x4000, 0x20);
        assertEquals(0xff, mapper.getByte(0x0200));
        assertEquals(0xff, mapper.getByte(0x4200));
    }

    private static byte[] rawMmc2Rom() {
        return rawMmc2Rom(0x200000);
    }

    private static byte[] rawMmc2Rom(int size) {
        byte[] data = new byte[size];
        // The low half contains the pirate logo; the CGB-selected A7-high copy contains
        // the Nintendo logo. Header fields are identical in both halves.
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[unscramble(0x0104 + i)] = (byte) (NINTENDO_LOGO[i] ^ 0xff);
            data[unscramble(0x0184 + i)] = (byte) NINTENDO_LOGO[i];
        }
        for (int address = 0x0134; address <= 0x0142; address++) {
            data[unscramble(address)] = 0x20;
            data[unscramble(address | 0x80)] = 0x20;
        }
        data[unscramble(0x0143)] = (byte) 0x80;
        data[unscramble(0x01c3)] = (byte) 0x80;
        int checksum = 0;
        for (int address = 0x0134; address <= 0x014c; address++) {
            checksum = (checksum - (data[unscramble(address)] & 0xff) - 1) & 0xff;
        }
        data[unscramble(0x014d)] = (byte) checksum;
        data[unscramble(0x01cd)] = (byte) checksum;
        return data;
    }

    private static int unscramble(int address) {
        int result = address & 0xffac;
        result |= (address & 0x40) >> 6;
        result |= (address & 0x10) >> 3;
        result |= (address & 0x02) << 3;
        result |= (address & 0x01) << 6;
        return result;
    }
}
