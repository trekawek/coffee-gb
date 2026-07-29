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

public class XploderGbTest {

    private static final byte[] CLEAN_ROOM_HEADER = {
            0x52, 0x55, 0x53, 0x54, 0x59, 0x42, 0x4f, 0x49,
            0x20, 0x58, 0x50, 0x4c, 0x4f, 0x44, 0x45, 0x52,
            0x20, 0x47, 0x42, 0x20, 0x46, 0x43, 0x44, 0x20,
            0x43, 0x4c, 0x45, 0x41, 0x4e, 0x52, 0x4f, 0x4f,
            0x4d, 0x20, 0x53, 0x49, 0x47, 0x21, 0x21, 0x21,
            0x21, 0x21, 0x21, 0x21, 0x0a, (byte) 0xc6, (byte) 0xe6, (byte) 0xb2
    };

    @Test
    public void detectsXploderBoardFromCreditArea() throws IOException {
        Rom rom = new Rom(xploderRom());

        assertEquals(CartridgeProperties.Mapper.XPLODER_GB,
                rom.getCartridgeProperties().getMapper());
        assertEquals(0, rom.getRom()[0x014d]);
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof XploderGb);
    }

    @Test
    public void banksRomThroughRegister0006() throws IOException {
        MemoryController mapper = mapper();

        assertEquals(1, mapper.getByte(0x4000));
        mapper.setByte(0x0006, 6);
        assertEquals(6, mapper.getByte(0x4000));
        mapper.setByte(0x0006, 0);
        assertEquals(0, mapper.getByte(0x4000));
        mapper.setByte(0x0006, 9);
        assertEquals(1, mapper.getByte(0x4000));
    }

    @Test
    public void exposesSixteenUngatedRamBanks() throws IOException {
        MemoryController mapper = mapper();

        mapper.setByte(0x0007, 0);
        mapper.setByte(0xb000, 0x11);
        mapper.setByte(0x0007, 14);
        mapper.setByte(0xb000, 0x22);
        assertEquals(0x22, mapper.getByte(0xb000));
        mapper.setByte(0x0007, 0);
        assertEquals(0x11, mapper.getByte(0xb000));
    }

    @Test
    public void stateRoundTripPreservesRegistersAndRam() throws IOException {
        MemoryController mapper = mapper();
        mapper.setByte(0x0006, 6);
        mapper.setByte(0x0007, 14);
        mapper.setByte(0xb000, 0x5a);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x0006, 2);
        mapper.setByte(0x0007, 1);
        mapper.setByte(0xb000, 0x33);
        mapper.restoreState(state);

        assertEquals(6, mapper.getByte(0x4000));
        assertEquals(0x5a, mapper.getByte(0xb000));
    }

    private static MemoryController mapper() throws IOException {
        return new XploderGb(new Rom(xploderRom()), Battery.NULL_BATTERY);
    }

    private static byte[] xploderRom() {
        byte[] data = new byte[0x20000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        System.arraycopy(CLEAN_ROOM_HEADER, 0, data, 0x0104, CLEAN_ROOM_HEADER.length);
        data[0x0147] = 0x69;
        data[0x0148] = 0x67;
        data[0x0149] = 0x6e;
        return data;
    }
}
