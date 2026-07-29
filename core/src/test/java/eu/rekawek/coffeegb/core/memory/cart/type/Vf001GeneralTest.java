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
import static org.junit.Assert.assertTrue;

public class Vf001GeneralTest {

    @Test
    public void detectsZhihuanWang2ExactImage() throws IOException {
        Rom rom = new Rom(vf001Rom());

        assertEquals(CartridgeProperties.Mapper.VF001_GENERAL,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof Vf001General);
    }

    @Test
    public void appliesByteInjectionAndBankZeroReplacement() throws IOException {
        Vf001General mapper = mapper();

        armReplacement(mapper, 0x2000, 5);
        assertEquals(0xc5, mapper.getByte(0x2000));
        assertEquals(0xb0, mapper.getByte(0x0000));

        mapper = mapper();
        armInjection(mapper, 0, 0x0100, new int[] {0x11, 0x22, 0x33, 0x44});
        assertEquals(0x11, mapper.getByte(0x0100));
        assertEquals(0x22, mapper.getByte(0x0101));
        assertEquals(0x33, mapper.getByte(0x0102));
        assertEquals(0x44, mapper.getByte(0x0103));
        assertEquals(0xb0, mapper.getByte(0x0000));
    }

    @Test
    public void stateRoundTripPreservesProtectionAndMbc5Bank() throws IOException {
        Vf001General mapper = mapper();
        armReplacement(mapper, 0x2000, 5);
        armInjection(mapper, 0, 0x0100, new int[] {0x11, 0x22, 0x33, 0x44});
        mapper.setByte(0x2000, 3);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2000, 2);
        mapper.restoreState(state);

        assertEquals(0x11, mapper.getByte(0x0100));
        assertEquals(0x22, mapper.getByte(0x0101));
        assertEquals(0x33, mapper.getByte(0x0102));
        assertEquals(0x44, mapper.getByte(0x0103));
        assertEquals(0xc5, mapper.getByte(0x2000));
        assertEquals(0xb3, mapper.getByte(0x4000));
    }

    private static void armInjection(Vf001General mapper, int bank, int address,
                                     int[] bytes) {
        Programmer programmer = new Programmer(mapper);
        programmer.latch(0x7001, address & 0xff);
        programmer.latch(0x7002, address >>> 8);
        programmer.latch(0x7003, bank);
        for (int i = 0; i < bytes.length; i++) {
            programmer.latch(0x7004 + i, bytes[i]);
        }
        programmer.latch(0x7000, 3 + bytes.length);
    }

    private static void armReplacement(Vf001General mapper, int address, int bank) {
        Programmer programmer = new Programmer(mapper);
        programmer.latch(0x6000, bank);
        programmer.latch(0x7009, address & 0xff);
        programmer.latch(0x700a, address >>> 8);
        programmer.latch(0x7008, 0x0f);
    }

    private static final class Programmer {

        private final Vf001General mapper;

        private int running;

        private Programmer(Vf001General mapper) {
            this.mapper = mapper;
            mapper.setByte(0x7000, 0x96);
        }

        private void latch(int address, int wanted) {
            int rotated = (running >>> 1) | ((running & 1) << 7);
            mapper.setByte(address, rotated ^ wanted);
            running = wanted;
        }
    }

    private static Vf001General mapper() throws IOException {
        return new Vf001General(new Rom(vf001Rom()), Battery.NULL_BATTERY);
    }

    private static byte[] vf001Rom() {
        byte[] data = new byte[0x80000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) (0xb0 + bank);
            data[bank * 0x4000 + 0x2000] = (byte) (0xc0 + bank);
        }
        data[0x0147] = 0x1b;
        data[0x0148] = 0x04;
        data[0x0149] = 0x03;
        // CRC-forcing suffix: the synthetic image's whole-ROM CRC-32 is e6748d1f.
        data[data.length - 4] = 0x58;
        data[data.length - 3] = 0x28;
        data[data.length - 2] = 0x71;
        data[data.length - 1] = 0x21;
        return data;
    }
}
