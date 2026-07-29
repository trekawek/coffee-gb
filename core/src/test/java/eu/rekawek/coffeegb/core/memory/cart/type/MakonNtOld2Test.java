package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MakonNtOld2Test {

    @Test
    public void detectsIssue229RomAndRelocatesEmbeddedGames() throws IOException {
        Rom rom = new Rom(multicartRom(0xe0));
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);
        MemoryController mapper = cartridge.getMemoryController();

        assertEquals(CartridgeProperties.Mapper.MAKON_NT_OLD_2,
                rom.getCartridgeProperties().getMapper());
        assertTrue(mapper instanceof MakonNtOld2);

        // Before the menu chooses a game, all 2 MiB are bankable.
        mapper.setByte(0x2000, 65);
        assertEquals(65, mapper.getByte(0x4000));

        // Select the 512 KiB game beginning at byte 0x100000 (ROM bank 64).
        mapper.setByte(0x5001, 0x20);
        mapper.setByte(0x5002, 0x00);
        assertEquals(64, mapper.getByte(0x0000));
        assertEquals(65, mapper.getByte(0x4000));

        mapper.setByte(0x2000, 3);
        assertEquals(67, mapper.getByte(0x4000));
    }

    @Test
    public void detectsSuper21In1HeaderFingerprint() throws IOException {
        assertNtOld2Header(new byte[] {0x26, (byte) 0xef, (byte) 0xa7, (byte) 0xd3});
    }

    @Test
    public void detectsSuperDonkeyKong5HeaderFingerprint() throws IOException {
        byte[] data = multicartRom(0xc0);
        Arrays.fill(data, 0x0100, 0x0150, (byte) 0);
        // Synthetic 0x50-byte header with CRC-32 0b1b808a.
        byte[] crcSuffix = {0x5d, 0x36, 0x7a, (byte) 0xe6};
        System.arraycopy(crcSuffix, 0, data, 0x014c, crcSuffix.length);

        Rom rom = new Rom(data);
        assertEquals(CartridgeProperties.Mapper.MAKON_NT_OLD_2,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof MakonNtOld2);
    }

    @Test
    public void masksBanksToTheSelectedGameSizeAndSupportsAlternateWiring()
            throws IOException {
        MemoryController mapper = new MakonNtOld2(
                new Rom(multicartRom(0xe0)), Battery.NULL_BATTERY);

        // A 128 KiB game begins at ROM bank 96. Bank 9 wraps to bank 1 within it.
        mapper.setByte(0x5001, 0x30);
        mapper.setByte(0x5002, 0x0c);
        mapper.setByte(0x2000, 9);
        assertEquals(96, mapper.getByte(0x0000));
        assertEquals(97, mapper.getByte(0x4000));

        // Alternate wiring maps low bank bits 001 -> 100, 010 -> 001, 100 -> 010.
        mapper.setByte(0x2000, 1);
        mapper.setByte(0x5003, 0x10);
        assertEquals(100, mapper.getByte(0x4000));
        mapper.setByte(0x2000, 2);
        assertEquals(97, mapper.getByte(0x4000));
        mapper.setByte(0x2000, 4);
        assertEquals(98, mapper.getByte(0x4000));
    }

    @Test
    public void mirrorsMulticartRegistersAcrossThe5000Range() throws IOException {
        MemoryController mapper = new MakonNtOld2(
                new Rom(multicartRom(0xe0)), Battery.NULL_BATTERY);

        mapper.setByte(0x50e9, 0x20); // low address bits 1: base bank 64
        mapper.setByte(0x50ee, 0x0c); // low address bits 2: 128 KiB mask
        mapper.setByte(0x50ef, 0x10); // low address bits 3: alternate wiring
        mapper.setByte(0x2137, 0x02); // alternate wiring maps bank 2 to bank 1

        assertEquals(64, mapper.getByte(0x0000));
        assertEquals(65, mapper.getByte(0x4000));
    }

    @Test
    public void mementoRestoresMappingAndVolatileRam() throws IOException {
        MemoryController mapper = new MakonNtOld2(
                new Rom(multicartRom(0xe0)), Battery.NULL_BATTERY);

        mapper.setByte(0x5001, 0x30);
        mapper.setByte(0x5002, 0x0c);
        mapper.setByte(0x2000, 3);
        mapper.setByte(0xa123, 0x5a);
        ComponentState<MemoryController> memento = mapper.captureState();

        mapper.setByte(0x5001, 0x20);
        mapper.setByte(0x2000, 7);
        mapper.setByte(0xa123, 0x33);
        mapper.restoreState(memento);

        assertEquals(96, mapper.getByte(0x0000));
        assertEquals(99, mapper.getByte(0x4000));
        assertEquals(0x5a, mapper.getByte(0xa123));
    }

    @Test
    public void controlsRumbleUsingTheMapperMode() throws IOException {
        List<Boolean> motorLog = new ArrayList<>();
        MakonNtOld2 mapper = new MakonNtOld2(
                new Rom(multicartRom(0xe0)), Battery.NULL_BATTERY);
        EventBusImpl bus = new EventBusImpl(null, null, false);
        bus.register(event -> motorLog.add(event.on()), RumbleEvent.class);
        mapper.init(bus);

        mapper.setByte(0x5001, 0x80); // enable rumble; old mode uses bit 1
        mapper.setByte(0x4000, 0x02);
        mapper.setByte(0x4000, 0x00);
        mapper.setByte(0x5003, 0x10); // alternate mode uses bit 3
        mapper.setByte(0x4000, 0x08);
        mapper.setByte(0x5001, 0x00); // disabling rumble also stops the motor

        assertEquals(List.of(true, false, true, false), motorLog);
    }

    @Test
    public void doesNotRouteTheRelated25In1ThroughTheType2Mapper() throws IOException {
        Rom rom = new Rom(multicartRom(0xc0));
        Cartridge cartridge = new Cartridge(rom, Battery.NULL_BATTERY);

        assertEquals(CartridgeProperties.Mapper.STANDARD,
                rom.getCartridgeProperties().getMapper());
        assertTrue(cartridge.getMemoryController() instanceof Mbc5);
    }

    private static byte[] multicartRom(int entryByte) {
        byte[] data = new byte[128 * 0x4000];
        for (int bank = 0; bank < 128; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        byte[] title = "POKEBOM USA".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x134, title.length);
        data[0x0102] = (byte) entryByte;
        data[0x0143] = (byte) 0x80;
        data[0x0147] = 0x19; // MBC5 in the menu's standard header
        data[0x0148] = 0x06; // 2 MiB
        return data;
    }

    private static void assertNtOld2Header(byte[] crcSuffix) throws IOException {
        byte[] data = multicartRom(0xc0);
        Arrays.fill(data, 0x0100, 0x0150, (byte) 0);
        System.arraycopy(crcSuffix, 0, data, 0x014c, crcSuffix.length);

        Rom rom = new Rom(data);
        assertEquals(CartridgeProperties.Mapper.MAKON_NT_OLD_2,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof MakonNtOld2);
    }
}
