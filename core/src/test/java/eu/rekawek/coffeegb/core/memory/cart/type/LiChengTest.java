package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.CartridgeProperties;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LiChengTest {

    @Test
    public void detectsLiChengSecondaryLogo() throws IOException {
        Rom rom = new Rom(liChengRom());

        assertEquals(CartridgeProperties.Mapper.LI_CHENG,
                rom.getCartridgeProperties().getMapper());
        assertTrue(new Cartridge(rom, Battery.NULL_BATTERY)
                .getMemoryController() instanceof LiCheng);
    }

    @Test
    public void detectsYingxiongTianxiaHeader() throws IOException {
        byte[] data = bankedRom();
        // A synthetic 0x50-byte header with CRC-32 3ef5afb2. The production
        // fingerprint identifies the Telefang-derived Li Cheng cartridge.
        Arrays.fill(data, 0x0100, 0x0150, (byte) 0);
        data[0x014c] = (byte) 0xf1;
        data[0x014d] = (byte) 0x89;
        data[0x014e] = (byte) 0x3d;
        data[0x014f] = (byte) 0x8e;

        assertEquals(CartridgeProperties.Mapper.LI_CHENG,
                new Rom(data).getCartridgeProperties().getMapper());
    }

    @Test
    public void ignoresWritesAboveLiChengBankRegisterWindow() throws IOException {
        LiCheng mapper = new LiCheng(new Rom(bankedRom()), Battery.NULL_BATTERY);

        mapper.setByte(0x2100, 0x02);
        assertEquals(0x02, mapper.getByte(0x4000));

        mapper.setByte(0x2101, 0x03);
        mapper.setByte(0x2fff, 0x04);
        assertEquals(0x02, mapper.getByte(0x4000));

        mapper.setByte(0x2000, 0x05);
        assertEquals(0x05, mapper.getByte(0x4000));
    }

    @Test
    public void stateRoundTripPreservesMbc5Banking() throws IOException {
        LiCheng mapper = new LiCheng(new Rom(bankedRom()), Battery.NULL_BATTERY);
        mapper.setByte(0x2000, 0x05);
        ComponentState<MemoryController> state = mapper.captureState();

        mapper.setByte(0x2000, 0x02);
        mapper.restoreState(state);

        assertEquals(0x05, mapper.getByte(0x4000));
    }

    @Test
    public void doesNotClassifyConsistentMbc5RomFromLogoCrcAlone() throws IOException {
        byte[] data = liChengRom();
        data[0x0147] = 0x19;
        data[0x0148] = 0x05;

        assertEquals(CartridgeProperties.Mapper.STANDARD,
                new Rom(data).getCartridgeProperties().getMapper());
    }

    private static byte[] liChengRom() {
        byte[] data = bankedRom();
        data[0x0147] = 0x01;
        data[0x0148] = 0x01;
        // Forty-eight synthetic bytes with CRC-32 d2b57657, one of the Li Cheng
        // secondary-logo fingerprints. Keeping the fixture synthetic avoids a ROM asset.
        data[0x01b0] = 0x4e;
        data[0x01b1] = (byte) 0xaf;
        data[0x01b2] = 0x41;
        data[0x01b3] = (byte) 0x9f;
        return data;
    }

    private static byte[] bankedRom() {
        byte[] data = new byte[0x100000];
        for (int bank = 0; bank < data.length / 0x4000; bank++) {
            data[bank * 0x4000] = (byte) bank;
        }
        data[0x0147] = 0x19;
        data[0x0148] = 0x05;
        return data;
    }
}
