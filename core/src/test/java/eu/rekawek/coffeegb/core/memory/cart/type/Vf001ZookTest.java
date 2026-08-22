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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Vf001ZookTest {

    private static final byte[] CLEAN_ROOM_LOGO = {
            'R', 'U', 'S', 'T', 'Y', 'B', 'O', 'I', ' ', 'V', 'F', '0', '0', '1', ' ', 'Z',
            'O', 'O', 'K', ' ', 'C', 'L', 'E', 'A', 'N', 'R', 'O', 'O', 'M', ' ', 'S', 'T',
            'A', 'N', 'D', 'I', 'N', ' ', 'S', 'I', 'G', '!', '!', '!',
            (byte) 0xdf, (byte) 0xd4, 0x43, (byte) 0xd7
    };

    @Test
    public void detectsTheLogoAndProtectionThunkTogether() throws IOException {
        Rom rom = new Rom(zookRom());

        assertEquals(CartridgeProperties.Mapper.VF001_ZOOK,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof Vf001Zook);

        byte[] withoutThunk = zookRom();
        withoutThunk[0x3ef5] ^= 1;
        assertEquals(CartridgeProperties.Mapper.STANDARD,
                new Rom(withoutThunk).getCartridgeProperties().getMapper());
    }

    @Test
    public void exposesTheUnpackedSuperFightersSVectors() throws IOException {
        Rom rom = new Rom(superFightersSRom());

        assertEquals(CartridgeProperties.Mapper.VF001_ZOOK,
                rom.getCartridgeProperties().getMapper());
        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.SUPER_FIGHTERS_S_VECTOR_PATCH));

        MemoryController mapper = new Cartridge(rom, Battery.NULL_BATTERY).getMemoryController();
        assertEquals(0xc3, mapper.getByte(0x0008));
        assertEquals(0xc3, mapper.getByte(0x0010));
        assertEquals(0xc3, mapper.getByte(0x0018));
        assertEquals(0x35, mapper.getByte(0x002f));
        assertEquals(0x00, mapper.getByte(0x0007));
        assertEquals(0x00, mapper.getByte(0x0030));

        byte[] withoutPackedVectors = superFightersSRom();
        withoutPackedVectors[0x0018] ^= 1;
        Rom other = new Rom(withoutPackedVectors);
        assertEquals(CartridgeProperties.Mapper.STANDARD, other.getCartridgeProperties().getMapper());
        assertFalse(other.getCartridgeProperties().has(
                CartridgeProperties.Feature.SUPER_FIGHTERS_S_VECTOR_PATCH));
    }

    @Test
    public void answersBankAndProtectionChallenges() throws IOException {
        MemoryController mapper = mapper();

        write(mapper, 0x7081, 0x46, 0x58, 0x54, 0x5f);
        assertEquals(4, mapper.getByte(0x4000));

        write(mapper, 0x7080, 0xa8, 0xb6);
        assertEquals(0x6e, mapper.getByte(0xa080));
        assertEquals(0xff, mapper.getByte(0xa180));

        int[] longer = {
                0xf3, 0x21, 0x80, 0x70, 0x36, 0x77, 0x36, 0x45,
                0x36, 0x03, 0x36, 0xa2
        };
        write(mapper, 0x7080, longer);
        assertEquals(0x4c, mapper.getByte(0xa080));
        mapper.setByte(0x7080, 0x8c);
        assertEquals(0xdf, mapper.getByte(0xa080));
    }

    @Test
    public void answersSuperFightersSBankCommandsAndVerificationReads() throws IOException {
        MemoryController mapper = superFightersSMapper();

        int[][] bankCommands = {
                {0x36, 0x9f, 0x3b, 0xc0, 0x42},
                {0x48, 0xe0, 0xb2, 0x36, 0x36},
                {0x96, 0x64, 0x07, 0x1f, 0x1f},
                {0xfa, 0xc5, 0xd8, 0x1f, 0x1f}
        };
        for (int[] command : bankCommands) {
            write(mapper, 0x7081, command[0], command[1], command[2], command[3]);
            assertEquals(command[4], mapper.getByte(0x4000));
        }

        write(mapper, 0x7080, 0x98, 0xa3, 0xa9, 0xce, 0xcc, 0x40, 0xff, 0xff,
                0xef, 0x40, 0xff, 0xff, 0xac, 0x41, 0xff, 0xff, 0x8f, 0xa3);
        assertEquals(0xce, mapper.getByte(0xa080));
        mapper.setByte(0x7080, 0x98);
        assertEquals(0xc3, mapper.getByte(0xa080));

        write(mapper, 0x7080, 0xe0, 0xfe, 0xe0, 0x2f, 0xf8, 0x04, 0x30, 0x00,
                0x06, 0x40, 0xe8, 0x08, 0x40, 0xf0, 0x0a, 0x40, 0xf8, 0x0c,
                0x40, 0x00, 0xa4);
        assertEquals(0x14, mapper.getByte(0xa080));
        mapper.setByte(0x7080, 0x9b);
        assertEquals(0x20, mapper.getByte(0xa080));
    }

    @Test
    public void stateRoundTripPreservesTheShiftWindowAndSelectedBank() throws IOException {
        MemoryController mapper = mapper();
        write(mapper, 0x7081, 0x46, 0x58, 0x54, 0x5f);
        write(mapper, 0x7080, 0xa8, 0xb6);
        ComponentState<MemoryController> state = mapper.captureState();

        write(mapper, 0x7081, 0xa4, 0xba, 0xd5, 0x44);
        write(mapper, 0x7080, 0xce, 0x8a);
        mapper.restoreState(state);

        assertEquals(4, mapper.getByte(0x4000));
        assertEquals(0x6e, mapper.getByte(0xa080));
    }

    private static MemoryController mapper() throws IOException {
        return new Vf001Zook(new Rom(zookRom()), Battery.NULL_BATTERY);
    }

    private static MemoryController superFightersSMapper() throws IOException {
        return new Cartridge(new Rom(superFightersSRom()), Battery.NULL_BATTERY)
                .getMemoryController();
    }

    private static void write(MemoryController mapper, int address, int... values) {
        for (int value : values) {
            mapper.setByte(address, value);
        }
    }

    private static byte[] zookRom() {
        byte[] data = new byte[0x100000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        System.arraycopy(CLEAN_ROOM_LOGO, 0, data, 0x0184, CLEAN_ROOM_LOGO.length);
        int[] thunk = {
                0x21, 0x81, 0x70, 0x1a, 0x77, 0x13, 0x1a, 0x77, 0x13,
                0x1a, 0x77, 0x13, 0x1a, 0x77, 0xfa, 0xff, 0x7f
        };
        for (int i = 0; i < thunk.length; i++) {
            data[0x3ef5 + i] = (byte) thunk[i];
        }
        byte[] title = "ZOOK Z TEST".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0147] = 0x01;
        data[0x0148] = 0x05;
        return data;
    }

    private static byte[] superFightersSRom() {
        byte[] data = new byte[0x200000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        byte[] title = "Super Fight's".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        data[0x0018] = (byte) 0x91;
        data[0x0019] = 0x20;
        data[0x001a] = (byte) 0xfa;
        data[0x0143] = (byte) 0x80;
        data[0x0144] = 'A';
        data[0x0145] = '7';
        data[0x0146] = 0x03;
        data[0x0147] = 0x01;
        data[0x0148] = 0x06;
        return data;
    }
}
