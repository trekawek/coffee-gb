package eu.rekawek.coffeegb.core.state.bess;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource;
import eu.rekawek.coffeegb.core.ir.Peer2PeerInfraredEndpoint;
import org.junit.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class BessCartridgeStateTest {

    @Test
    public void preservesMbc1BanksInBothModesAndExportsSharedHardwareLatch() throws IOException {
        for (int mode = 0; mode <= 1; mode++) {
            Rom rom = rom(0x03, 128, 3);
            Cartridge source = cartridge(rom);
            source.setByte(0, 0x0a);
            source.setByte(0x6000, 1);
            fillRamBanks(source, 4);
            source.setByte(0x4000, 2);
            source.setByte(0x6000, 0);
            source.setByte(0x2000, 5);
            source.setByte(0x4000, 3);
            source.setByte(0x6000, mode);
            assertEquals(mode == 0 ? 101 : 69, source.getByte(0x4000));

            BessCartridgeState state = source.captureBessState();
            Cartridge restored = cartridge(rom);
            restore(restored, state);
            assertEquals(source.getByte(0x0000), restored.getByte(0x0000));
            assertEquals(source.getByte(0x4000), restored.getByte(0x4000));
            assertEquals(source.getByte(0xa123), restored.getByte(0xa123));

            // Independent hardware register model: the upper two bank bits are one shared
            // latch, regardless of which mode was selected when the write happened.
            int lower = 1;
            int upper = 0;
            int hardwareMode = 0;
            for (BessState.MbcWrite write : state.registers()) {
                int address = write.address();
                if (address >= 0x2000 && address < 0x4000) lower = write.value() & 31;
                if (address >= 0x4000 && address < 0x6000) upper = write.value() & 3;
                if (address >= 0x6000 && address < 0x8000) hardwareMode = write.value() & 1;
            }
            assertEquals(mode, hardwareMode);
            assertEquals(source.getByte(0x4000), upper * 32 + Math.max(lower, 1));
            restored.setByte(0x6000, 1);
            assertRamBanks(restored, 4);
        }
    }

    @Test
    public void preservesMbc2RomBankNibbleRamAndReadMask() throws IOException {
        Rom rom = rom(0x06, 16, 0);
        Cartridge source = cartridge(rom);
        source.setByte(0, 0x0a);
        source.setByte(0x2100, 12);
        source.setByte(0xa01d, 0xfb);
        source.setByte(0xa1ff, 0x34);
        BessCartridgeState state = source.captureBessState();

        assertEquals(512, state.ram().length);
        assertEquals(0x0b, state.ram()[0x1d]);
        assertTrue(state.registers().contains(new BessState.MbcWrite(0x2100, 12)));
        Cartridge restored = cartridge(rom);
        restore(restored, state);
        assertEquals(12, restored.getByte(0x4000));
        assertEquals(0xfb, restored.getByte(0xa01d));
        assertEquals(0xf4, restored.getByte(0xa1ff));

        // Other producers may store the bus-visible upper nibble; only four RAM bits exist.
        byte[] incoming = new byte[512];
        Arrays.fill(incoming, (byte) 0xe7);
        restored.restoreBessState(incoming, state.registers(), Map.of());
        assertEquals(0xf7, restored.getByte(0xa01d));
        assertEquals(7, restored.captureBessState().ram()[0x1d]);
    }

    @Test
    public void importsMbc1UpperBankWrittenBeforeRamBankingMode() throws IOException {
        Cartridge cartridge = cartridge(rom(0x03, 128, 3));
        byte[] ram = new byte[0x8000];
        ram[3 * 0x2000 + 0x123] = 0x57;
        // SameBoy and hardware use a shared upper latch. Mode is commonly restored last.
        cartridge.restoreBessState(ram, List.of(new BessState.MbcWrite(0, 10),
                new BessState.MbcWrite(0x2000, 5), new BessState.MbcWrite(0x4000, 3),
                new BessState.MbcWrite(0x6000, 1)), Map.of());
        assertEquals(96, cartridge.getByte(0));
        assertEquals(101, cartridge.getByte(0x4000));
        assertEquals(0x57, cartridge.getByte(0xa123));
        cartridge.setByte(0x6000, 0);
        assertEquals(101, cartridge.getByte(0x4000));
    }

    @Test
    public void preservesMbc3RomAndRamBanks() throws IOException {
        Rom rom = rom(0x10, 32, 3);
        Cartridge source = cartridge(rom);
        source.setByte(0, 0x0a);
        fillRamBanks(source, 4);
        source.setByte(0x4000, 3);
        source.setByte(0x2000, 19);

        Cartridge restored = cartridge(rom);
        restore(restored, source.captureBessState());
        assertEquals(19, restored.getByte(0x4000));
        assertEquals(0x43, restored.getByte(0xa123));
        assertRamBanks(restored, 4);
    }

    @Test
    public void preservesMbc3CurrentAndLatchedClockIndependently() throws IOException {
        Rom rom = rom(0x10, 4, 3);
        VirtualTimeSource time = new VirtualTimeSource();
        Cartridge source = new Cartridge(rom, Battery.NULL_BATTERY, time);
        source.setByte(0, 0x0a);
        int[] latched = {12, 34, 21, 0x56, 0xc1};
        int[] live = {45, 6, 7, 0x89, 0x80};
        writeRtc(source, latched);
        source.setByte(0x6000, 0);
        source.setByte(0x6000, 1);
        writeRtc(source, live);
        source.setByte(0x4000, 8);
        BessCartridgeState state = source.captureBessState();
        byte[] rtc = state.extensions().get("RTC ");
        assertNotNull(rtc);
        assertEquals(48, rtc.length);
        for (int i = 0; i < 5; i++) {
            assertEquals(live[i], rtc[i * 4] & 0xff);
            assertEquals(latched[i], rtc[20 + i * 4] & 0xff);
            assertEquals(0, rtc[i * 4 + 1]);
            assertEquals(0, rtc[i * 4 + 2]);
            assertEquals(0, rtc[i * 4 + 3]);
        }
        assertEquals(time.currentTimeMillis() / 1000,
                ByteBuffer.wrap(rtc).order(ByteOrder.LITTLE_ENDIAN).getLong(40));

        time.forward(3, TimeUnit.DAYS);
        Cartridge restored = new Cartridge(rom, Battery.NULL_BATTERY, time);
        // Replay a latch command as an external producer might. RTC must be restored afterward.
        restored.restoreBessState(state.ram(), List.of(new BessState.MbcWrite(0, 0x0a),
                new BessState.MbcWrite(0x6000, 0), new BessState.MbcWrite(0x6000, 1),
                new BessState.MbcWrite(0x4000, 8)), state.extensions());
        assertRtc(restored, latched);
        restored.setByte(0x6000, 0);
        restored.setByte(0x6000, 1);
        assertRtc(restored, live);
    }

    @Test
    public void preservesMbc5NinthRomBankBitAndAllRamBanks() throws IOException {
        Rom rom = rom(0x1b, 512, 4);
        Cartridge source = cartridge(rom);
        source.setByte(0, 0x0a);
        fillRamBanks(source, 16);
        source.setByte(0x2000, 0x23);
        source.setByte(0x3000, 1);
        source.setByte(0x4000, 14);
        BessCartridgeState state = source.captureBessState();
        assertTrue(state.registers().contains(new BessState.MbcWrite(0x3000, 1)));

        Cartridge restored = cartridge(rom);
        restore(restored, state);
        assertEquals(0x23, restored.getByte(0x4000));
        assertEquals(1, restored.getByte(0x4001));
        assertEquals(0x4e, restored.getByte(0xa123));
        assertRamBanks(restored, 16);
    }

    @Test
    public void preservesMbc5RumbleAlongsideRamBank() throws IOException {
        Rom rom = rom(0x1e, 8, 5);
        Cartridge source = cartridge(rom);
        source.setByte(0, 0x0a);
        source.setByte(0x4000, 0x0b);
        source.setByte(0xa123, 0x65);

        Cartridge restored = cartridge(rom);
        restore(restored, source.captureBessState());
        assertTrue(restored.isRumbleActive());
        assertEquals(0x65, restored.getByte(0xa123));
        restored.setByte(0x4000, 0x03);
        assertFalse(restored.isRumbleActive());
        assertEquals(0x65, restored.getByte(0xa123));
    }

    @Test
    public void preservesHuc1RamBankAndInfraredMode() throws IOException {
        Rom rom = rom(0xff, 8, 3);
        Cartridge source = cartridge(rom);
        source.setByte(0x2000, 6);
        fillRamBanks(source, 4);
        source.setByte(0x4000, 2);
        source.setByte(0, 0x0e);

        Cartridge restored = cartridge(rom);
        restore(restored, source.captureBessState());
        assertEquals(6, restored.getByte(0x4000));
        assertEquals(0xc0, restored.getByte(0xa123));
        restored.setByte(0, 0x0a);
        assertEquals(0x42, restored.getByte(0xa123));
        assertRamBanks(restored, 4);
    }

    @Test
    public void preservesHucInfraredOutputWithoutChangingFinalMapperModeOrRam() throws IOException {
        for (int mapper : new int[]{0xff, 0xfe}) {
            for (int finalMode : new int[]{0x0a, 0x0e}) {
                Rom rom = rom(mapper, 8, 3);
                Cartridge source = cartridge(rom);
                source.setByte(0, 0x0a);
                source.setByte(0xa000, 0x2b);
                source.setByte(0, 0x0e);
                source.setByte(0xa000, 1);
                source.setByte(0, finalMode);

                Cartridge restored = cartridge(rom);
                var output = new Peer2PeerInfraredEndpoint();
                var receiver = new Peer2PeerInfraredEndpoint();
                output.init(receiver);
                restored.setInfraredEndpoint(output);
                BessCartridgeState state = source.captureBessState();
                restore(restored, state);

                assertTrue("HuC mapper " + mapper + " lost its LED latch", receiver.isLightOn());
                assertEquals(finalMode == 0x0e ? 0xc0 : 0x2b, restored.getByte(0xa000));
                assertArrayEquals(state.ram(), restored.captureBessState().ram());
            }
        }
    }

    @Test
    public void exportsMbc7LittleEndianEepromAndRestoresWritableIdleChip() throws IOException {
        Rom rom = rom(0x22, 8, 0);
        Cartridge source = cartridge(rom);
        source.setByte(0, 0x0a);
        source.setByte(0x4000, 0x40);
        source.setByte(0x2000, 5);
        source.setByte(0xa000, 0x55);
        source.setByte(0xa010, 0xaa);
        eepromCommand(source, 0, 0xc0); // EWEN
        source.setByte(0xa080, 0);
        eepromCommand(source, 1, 0x12); // WRITE
        eepromBits(source, 0xbeef, 16);
        source.setByte(0xa080, 0);

        BessCartridgeState state = source.captureBessState();
        assertEquals(256, state.ram().length);
        assertEquals(0xef, state.ram()[0x24] & 0xff);
        assertEquals(0xbe, state.ram()[0x25] & 0xff);
        assertEquals(0x20, state.extensions().get("MBC7")[0] & 0x20);
        Cartridge restored = cartridge(rom);
        restore(restored, state);
        assertEquals(5, restored.getByte(0x4000));
        assertEquals(0xd0, restored.getByte(0xa020));
        assertEquals(0x81, restored.getByte(0xa030));
        assertEquals(0xd0, restored.getByte(0xa040));
        assertEquals(0x81, restored.getByte(0xa050));
        assertEquals(0xbeef, eepromReadWord(restored, 0x12));

        // The write-enable latch must survive independently of the stored EEPROM data.
        eepromCommand(restored, 1, 0x13);
        eepromBits(restored, 0xcafe, 16);
        restored.setByte(0xa080, 0);
        assertEquals(0xcafe, eepromReadWord(restored, 0x13));
    }

    @Test
    public void importsForeignMbc7EepromWordsAndGyroLatch() throws IOException {
        Cartridge cartridge = cartridge(rom(0x22, 8, 0));
        byte[] eeprom = new byte[256];
        eeprom[0] = 0x34;
        eeprom[1] = 0x12;
        ByteBuffer block = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        block.put((byte) 3).put((byte) 0).putShort((short) 0).putShort((short) 0xffff);
        block.putShort((short) 0x4567).putShort((short) 0x89ab);
        cartridge.restoreBessState(eeprom, List.of(new BessState.MbcWrite(0, 0x0a),
                new BessState.MbcWrite(0x2000, 3), new BessState.MbcWrite(0x4000, 0x40)),
                Map.of("MBC7", block.array()));
        assertEquals(3, cartridge.getByte(0x4000));
        assertEquals(0x67, cartridge.getByte(0xa020));
        assertEquals(0x45, cartridge.getByte(0xa030));
        assertEquals(0xab, cartridge.getByte(0xa040));
        assertEquals(0x89, cartridge.getByte(0xa050));
        assertEquals(0x1234, eepromReadWord(cartridge, 0));
        // Latch ready bit allows the game's next sample command to complete.
        cartridge.setByte(0xa010, 0xaa);
        assertEquals(0xd0, cartridge.getByte(0xa020));
    }

    @Test
    public void rejectsActiveMbc7EepromTransfersExplicitly() throws IOException {
        Cartridge cartridge = cartridge(rom(0x22, 8, 0));
        cartridge.setByte(0, 0x0a);
        cartridge.setByte(0x4000, 0x40);
        eepromBits(cartridge, 1, 1); // Start bit, awaiting command.
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                cartridge::captureBessState);
        assertTrue(error.getMessage().contains("active MBC7"));
        cartridge.setByte(0xa080, 0);
        byte[] block = new byte[10];
        ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN).putShort(2, (short) 1)
                .putShort(4, (short) 0xffff);
        error = assertThrows(IllegalArgumentException.class,
                () -> cartridge.restoreBessState(new byte[256], List.of(), Map.of("MBC7", block)));
        assertTrue(error.getMessage().contains("active MBC7"));
    }

    @Test
    public void truncatesOversizedRamAndZeroFillsShortRam() throws IOException {
        Rom rom = rom(0x09, 2, 2);
        Cartridge cartridge = cartridge(rom);
        byte[] oversized = new byte[0x4000];
        Arrays.fill(oversized, (byte) 0xc4);
        oversized[0x1fff] = 0x62;
        cartridge.restoreBessState(oversized, List.of(), Map.of());
        assertEquals(0x2000, cartridge.captureBessState().ram().length);
        assertEquals(0xc4, cartridge.getByte(0xa000));
        assertEquals(0x62, cartridge.getByte(0xbfff));

        cartridge.restoreBessState(new byte[]{0x12, 0x34}, List.of(), Map.of());
        assertEquals(0x12, cartridge.getByte(0xa000));
        assertEquals(0x34, cartridge.getByte(0xa001));
        assertEquals(0, cartridge.getByte(0xa002));
        assertEquals(0, cartridge.getByte(0xbfff));
    }

    @Test
    public void restorePreservesDisabledRamGate() throws IOException {
        Rom rom = rom(0x10, 4, 3);
        Cartridge source = cartridge(rom);
        source.setByte(0, 0x0a);
        source.setByte(0xa123, 0x75);
        source.setByte(0, 0);
        Cartridge restored = cartridge(rom);
        restore(restored, source.captureBessState());
        assertEquals(0xff, restored.getByte(0xa123));
        restored.setByte(0, 0x0a);
        assertEquals(0x75, restored.getByte(0xa123));
    }

    private static void fillRamBanks(Cartridge cartridge, int count) {
        for (int bank = 0; bank < count; bank++) {
            cartridge.setByte(0x4000, bank);
            cartridge.setByte(0xa123, 0x40 + bank);
            cartridge.setByte(0xbfff, 0x80 + bank);
        }
    }

    private static void assertRamBanks(Cartridge cartridge, int count) {
        for (int bank = 0; bank < count; bank++) {
            cartridge.setByte(0x4000, bank);
            assertEquals("RAM bank " + bank, 0x40 + bank, cartridge.getByte(0xa123));
            assertEquals("RAM bank " + bank, 0x80 + bank, cartridge.getByte(0xbfff));
        }
    }

    private static void writeRtc(Cartridge cartridge, int[] values) {
        for (int i = 0; i < values.length; i++) {
            cartridge.setByte(0x4000, 8 + i);
            cartridge.setByte(0xa000, values[i]);
        }
    }

    private static void eepromCommand(Cartridge cartridge, int op, int address) {
        eepromBits(cartridge, 1, 1);
        eepromBits(cartridge, op, 2);
        eepromBits(cartridge, address, 8);
    }

    private static void eepromBits(Cartridge cartridge, int value, int bits) {
        for (int bit = bits - 1; bit >= 0; bit--) {
            int di = (value >> bit & 1) << 1;
            cartridge.setByte(0xa080, 0x80 | di);
            cartridge.setByte(0xa080, 0xc0 | di);
        }
    }

    private static int eepromReadWord(Cartridge cartridge, int address) {
        eepromCommand(cartridge, 2, address);
        int value = 0;
        for (int bit = 0; bit < 17; bit++) {
            eepromBits(cartridge, 0, 1);
            if (bit != 0) value = value << 1 | (cartridge.getByte(0xa080) & 1);
        }
        cartridge.setByte(0xa080, 0);
        return value;
    }

    private static void assertRtc(Cartridge cartridge, int[] values) {
        for (int i = 0; i < values.length; i++) {
            cartridge.setByte(0x4000, 8 + i);
            assertEquals("RTC register " + i, values[i], cartridge.getByte(0xa000));
        }
    }

    private static Cartridge cartridge(Rom rom) {
        return new Cartridge(rom, Battery.NULL_BATTERY, new VirtualTimeSource());
    }

    private static void restore(Cartridge cartridge, BessCartridgeState state) {
        cartridge.restoreBessState(state.ram(), state.registers(), state.extensions());
    }

    private static Rom rom(int type, int banks, int ramSizeCode) throws IOException {
        byte[] data = new byte[banks * 0x4000];
        for (int bank = 0; bank < banks; bank++) {
            data[bank * 0x4000] = (byte) bank;
            data[bank * 0x4000 + 1] = (byte) (bank >> 8);
        }
        data[0x147] = (byte) type;
        data[0x148] = (byte) (Integer.numberOfTrailingZeros(banks) - 1);
        data[0x149] = (byte) ramSizeCode;
        return new Rom(data);
    }
}
