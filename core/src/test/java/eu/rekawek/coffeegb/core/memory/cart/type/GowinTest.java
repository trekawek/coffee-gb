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

public class GowinTest {

    private static final int[] NINTENDO_LOGO = {
            0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
            0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
            0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
            0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
            0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
            0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    @Test
    public void detectsSchoolFighterBoardWithoutAWholeRomFingerprint() throws IOException {
        Rom rom = new Rom(gowinRom());

        assertEquals(CartridgeProperties.Mapper.GOWIN,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof Gowin);
    }

    @Test
    public void doesNotClassifyOtherMbc1SoftwareByTitleAlone() throws IOException {
        byte[] data = gowinRom();
        data[0x0147] = 0x19;

        assertEquals(CartridgeProperties.Mapper.STANDARD,
                new Rom(data).getCartridgeProperties().getMapper());
    }

    @Test
    public void answersEveryKnownSchoolFighterChallenge() throws IOException {
        Gowin mapper = mapper();

        assertEquals(0xff, mapper.getByte(0xa080));
        assertResponse(mapper, 0x42, 0xfb);
        assertResponse(mapper, 0x44, 0xfa);
        assertResponse(mapper, 0x4a, 0xfa);
        assertResponse(mapper, 0x4c, 0xfc);
        assertResponse(mapper, 0x57, 0xfc);
        assertResponse(mapper, 0x62, 0xf1);
        assertResponse(mapper, 0x65, 0xf5);
        assertResponse(mapper, 0x78, 0xf3);
    }

    @Test
    public void preservesMbc1BankingAndProtectionState() throws IOException {
        Gowin mapper = mapper();
        mapper.setByte(0x2000, 5);
        mapper.setByte(0x6080, 0x44);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2000, 2);
        mapper.setByte(0x6080, 0x20);
        mapper.restoreState(state);

        assertEquals(5, mapper.getByte(0x4000));
        assertEquals(0xfa, mapper.getByte(0xa080));
    }

    @Test
    public void protectionWritesAlsoReachTheUnderlyingMbc1ModeRegister() throws IOException {
        byte[] data = gowinRom(0x100000, 0x05);
        Gowin mapper = new Gowin(new Rom(data), Battery.NULL_BATTERY);
        mapper.setByte(0x6080, 0x65);
        mapper.setByte(0x4000, 1);
        assertEquals(32, mapper.getByte(0x0000));

        mapper.setByte(0x6080, 0x44);
        assertEquals(0, mapper.getByte(0x0000));
        mapper.setByte(0x6080, 0x65);
        assertEquals(32, mapper.getByte(0x0000));
        assertEquals(0xf5, mapper.getByte(0xa080));
    }

    private static void assertResponse(Gowin mapper, int value, int response) {
        mapper.setByte(0x6080, value);
        assertEquals(response, mapper.getByte(0xa080));
    }

    private static Gowin mapper() throws IOException {
        return new Gowin(new Rom(gowinRom()), Battery.NULL_BATTERY);
    }

    private static byte[] gowinRom() {
        return gowinRom(0x80000, 0x04);
    }

    private static byte[] gowinRom(int size, int sizeCode) {
        byte[] data = new byte[size];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            data[0x0104 + i] = (byte) NINTENDO_LOGO[i];
        }
        byte[] title = "SCHOOL FIGHTER1".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x01;
        data[0x0148] = (byte) sizeCode;
        data[0x0149] = 0x00;
        return data;
    }
}
