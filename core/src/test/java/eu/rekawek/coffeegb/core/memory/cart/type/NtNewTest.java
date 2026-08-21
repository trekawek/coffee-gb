package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class NtNewTest {

    @Test
    public void detectsTheMakonDigimonFourRelease() throws IOException {
        Rom rom = new Rom(makonDigimonFourRom());
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);

        assertEquals(CartridgeProperties.Mapper.MAKON_NT_NEW,
                rom.getCartridgeProperties().getMapper());
        assertTrue(cartridge.getMemoryController() instanceof NtNew);
    }

    @Test
    public void persistsTheBoards8KiBRamDespiteItsNonBatteryHeader() throws IOException {
        Rom rom = new Rom(makonDigimonFourRom());
        Path save = Files.createTempFile("makon-nt-new", ".sav");
        try {
            byte[] initial = new byte[0x2000];
            Arrays.fill(initial, (byte) 0xff);
            initial[0x123] = 0x5a;
            Files.write(save, initial);

            assertTrue(Cartridge.supportsBatterySave(rom));
            Cartridge cartridge = persistentCartridge(rom, save);
            assertTrue(cartridge.getMemoryController() instanceof NtNew);
            cartridge.setByte(0x0000, 0x0a);
            assertEquals(0x5a, cartridge.getByte(0xa123));
            cartridge.setByte(0xa123, 0x6b);
            cartridge.flushBattery();

            assertEquals(0x2000, Files.size(save));
            Cartridge reloaded = persistentCartridge(rom, save);
            reloaded.setByte(0x0000, 0x0a);
            assertEquals(0x6b, reloaded.getByte(0xa123));
        } finally {
            Files.deleteIfExists(save);
        }
    }

    private static Cartridge persistentCartridge(Rom rom, Path save) {
        return new Cartridge(
                rom,
                true,
                BatteryStorage.direct(save),
                new SystemTimeSource(),
                ClockSpec.LEGACY);
    }

    private static byte[] makonDigimonFourRom() {
        byte[] data = new byte[0x100000];
        byte[] title = "DIGIMON 4".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        int[] logo = {
                0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
                0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
                0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
                0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
                0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
                0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
        };
        for (int i = 0; i < logo.length; i++) {
            data[0x0104 + i] = (byte) logo[i];
        }
        data[0x0143] = (byte) 0x80;
        data[0x0144] = 'M';
        data[0x0145] = 'K';
        data[0x0147] = 0x19;
        data[0x0148] = 0x05;
        data[0x0149] = 0x02;
        return data;
    }

    @Test
    public void changesFromA16KiBWindowToIndependent8KiBWindows() throws IOException {
        NtNew mapper = new NtNew(new Rom(romWith8kPageMarkers()), Battery.NULL_BATTERY);

        assertEquals(0, mapper.getByte(0x0000));
        assertEquals(2, mapper.getByte(0x4000));
        assertEquals(3, mapper.getByte(0x6000));

        mapper.setByte(0x2000, 0x10);
        assertEquals(0x20, mapper.getByte(0x4000));
        assertEquals(0x21, mapper.getByte(0x6000));

        mapper.setByte(0x1400, 0x55);
        mapper.setByte(0x2000, 0x04);
        mapper.setByte(0x2400, 0x05);
        assertEquals(0x04, mapper.getByte(0x4000));
        assertEquals(0x05, mapper.getByte(0x6000));
    }

    @Test
    public void restoresTheIndependent8KiBWindowState() throws IOException {
        NtNew mapper = new NtNew(new Rom(romWith8kPageMarkers()), Battery.NULL_BATTERY);
        mapper.setByte(0x1400, 0x55);
        mapper.setByte(0x2000, 0x0a);
        mapper.setByte(0x2400, 0x0b);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2000, 0x12);
        mapper.setByte(0x2400, 0x13);
        mapper.restoreState(state);

        assertEquals(0x0a, mapper.getByte(0x4000));
        assertEquals(0x0b, mapper.getByte(0x6000));
    }

    private static byte[] romWith8kPageMarkers() {
        byte[] data = new byte[64 * 0x2000];
        for (int page = 0; page < 64; page++) {
            Arrays.fill(data, page * 0x2000, (page + 1) * 0x2000, (byte) page);
        }
        data[0x0147] = 0x01;
        data[0x0148] = 0x04;
        return data;
    }
}
