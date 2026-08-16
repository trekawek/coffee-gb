package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.VirtualTimeSource;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Mbc5MulticartTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    @Test
    public void detectsTheMultiMbcLayout() throws IOException {
        Rom rom = new Rom(multicartRom());
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);

        assertEquals(CartridgeProperties.Mapper.MBC5_MULTICART,
                rom.getCartridgeProperties().getMapper());
        assertTrue(cartridge.getMemoryController() instanceof Mbc5Multicart);
    }

    @Test
    public void doesNotDetectTheBoardWithoutItsEmbeddedGameHeaderLayout() throws IOException {
        byte[] data = multicartRom();
        data[0x16 * 0x4000 + 0x0104] ^= 1;

        assertFalse(new Rom(data).getCartridgeProperties().getMapper()
                == CartridgeProperties.Mapper.MBC5_MULTICART);
    }

    @Test
    public void keepsTheMenuOnMbc5UntilExternalConfigurationCommitsAGame() throws IOException {
        MemoryController mapper = new Mbc5Multicart(new Rom(multicartRom()), Battery.NULL_BATTERY);

        mapper.setByte(0x2000, 0x15);
        assertEquals(0, mapper.getByte(0x0000));
        assertEquals(0x15, mapper.getByte(0x4000));

        mapper.setByte(0x7000, 0xaa);
        mapper.setByte(0x2000, 1);
        assertEquals(0x16, mapper.getByte(0x0000));
        assertEquals(0x17, mapper.getByte(0x4000));

        mapper.setByte(0xb000, 0x10);
        mapper.setByte(0xb100, 0xf0);
        assertEquals(0x16, mapper.getByte(0x0000));
        assertEquals(0x17, mapper.getByte(0x4000));

        mapper.setByte(0xb200, 0xe1);
        assertEquals(0x16, mapper.getByte(0x0000));

        mapper.setByte(0xb200, 0xe0);
        assertEquals(0x20, mapper.getByte(0x0000));
        assertEquals(0x21, mapper.getByte(0x4000));

        mapper.setByte(0x2000, 0);
        assertEquals(0x21, mapper.getByte(0x4000));

        mapper.setByte(0x2000, 3);
        assertEquals(0x20, mapper.getByte(0x0000));
        assertEquals(0x23, mapper.getByte(0x4000));
    }

    @Test
    public void selectsMbc3AndMbc5GamesUsingTheBoardMapperMode() throws IOException {
        MemoryController mbc3 = new Mbc5Multicart(new Rom(multicartRom()), Battery.NULL_BATTERY);
        configure(mbc3, 0x20, 0xe0, 0xa0);
        assertEquals(0x40, mbc3.getByte(0x0000));
        mbc3.setByte(0x2000, 3);
        assertEquals(0x43, mbc3.getByte(0x4000));

        MemoryController mbc5 = new Mbc5Multicart(new Rom(multicartRom()), Battery.NULL_BATTERY);
        configure(mbc5, 0x40, 0xe0, 0xc0);
        assertEquals(0x80, mbc5.getByte(0x0000));
        mbc5.setByte(0x2000, 3);
        assertEquals(0x83, mbc5.getByte(0x4000));
    }

    @Test
    public void refinesTheSharedMbc3ModeFromTheSelectedGameHeader() throws IOException {
        MemoryController mbc2 = new Mbc5Multicart(new Rom(multicartRom()), Battery.NULL_BATTERY);
        configure(mbc2, 0x60, 0xfc, 0xa0);
        assertEquals(0xc0, mbc2.getByte(0x0000));

        mbc2.setByte(0x2000, 2);
        assertEquals(0xc1, mbc2.getByte(0x4000));
        mbc2.setByte(0x2100, 3);
        assertEquals(0xc3, mbc2.getByte(0x4000));
        mbc2.setByte(0x0000, 0x0a);
        mbc2.setByte(0xa000, 0xab);
        assertEquals(0xfb, mbc2.getByte(0xa000));
    }

    @Test
    public void appliesThePageMaskEvenWhenTheEmbeddedHeaderOverstatesItsSize() throws IOException {
        MemoryController mapper = new Mbc5Multicart(new Rom(multicartRom()), Battery.NULL_BATTERY);
        configure(mapper, 0, 0xf0, 0xc0);

        mapper.setByte(0x2000, 0x21);
        assertEquals(1, mapper.getByte(0x4000));
    }

    @Test
    public void restoresTheSelectedGameAndItsMapperState() throws IOException {
        Mbc5Multicart mapper = new Mbc5Multicart(new Rom(multicartRom()), Battery.NULL_BATTERY);
        configure(mapper, 0x68, 0xfc, 0xe0);
        mapper.setByte(0x2000, 4);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2000, 7);
        mapper.restoreState(state);

        assertEquals(0xd0, mapper.getByte(0x0000));
        assertEquals(0xd4, mapper.getByte(0x4000));
    }

    @Test
    public void exposesAnMbc3RuntimeAfterTheMbc3GameIsSelected() throws IOException {
        VirtualTimeSource timeSource = new VirtualTimeSource();
        Cartridge cartridge = new Cartridge(
                new Rom(multicartRom()), Battery.NULL_BATTERY, timeSource, ClockSpec.LEGACY);

        assertTrue(cartridge.isClocked());
        configure(cartridge.getMemoryController(), 0x20, 0xe0, 0xa0);
        assertNotNull(cartridge.captureRtcRuntimeState());
    }

    @Test
    public void restoresAnMbc3GameWithoutConsultingTheClockDuringASuppressedTransaction()
            throws IOException {
        FailingTimeSource timeSource = new FailingTimeSource();
        Mbc5Multicart mapper = new Mbc5Multicart(
                new Rom(multicartRom()), Battery.NULL_BATTERY, timeSource, ClockSpec.LEGACY);
        configure(mapper, 0x20, 0xe0, 0xa0);
        ComponentState<MemoryController> state = mapper.captureState();

        timeSource.failOnRead = true;
        mapper.setStateTimeSourceAccessSuppressed(true);
        mapper.restoreState(state);

        assertEquals(0x40, mapper.getByte(0x0000));
    }

    private static void configure(MemoryController mapper, int page, int invertedMask, int mode) {
        mapper.setByte(0xb000, page);
        mapper.setByte(0xb100, invertedMask);
        mapper.setByte(0xb200, mode);
    }

    private static byte[] multicartRom() {
        byte[] data = new byte[256 * 0x4000];
        for (int bank = 0; bank < 256; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        putHeader(data, 0, 0x19, 0x05);
        for (int bank = 0x16; bank <= 0x20; bank += 2) {
            putHeader(data, bank, 0x19, 0x05);
        }
        putHeader(data, 0x20, 0x01, 0x04);
        putHeader(data, 0x40, 0x10, 0x05);
        putHeader(data, 0x80, 0x1c, 0x05);
        putHeader(data, 0xc0, 0x06, 0x02);
        putHeader(data, 0xd0, 0x01, 0x02);
        return data;
    }

    private static void putHeader(byte[] data, int bank, int type, int size) {
        int base = bank * 0x4000;
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[base + 0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        data[base + 0x0143] = (byte) 0x80;
        data[base + 0x0147] = (byte) type;
        data[base + 0x0148] = (byte) size;
    }

    private static final class FailingTimeSource implements TimeSource {

        private boolean failOnRead;

        @Override
        public long currentTimeMillis() {
            if (failOnRead) {
                throw new AssertionError("unexpected wall-clock read");
            }
            return 1;
        }
    }
}
