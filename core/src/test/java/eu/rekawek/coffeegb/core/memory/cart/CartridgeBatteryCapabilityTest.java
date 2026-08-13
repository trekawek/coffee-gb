package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Mapper capability checks backing the pause-menu's battery-save status. */
public class CartridgeBatteryCapabilityTest {

    @Test
    public void mbc6AdvertisesItsSramAndFlashPersistenceWithoutABatteryHeaderBit()
            throws IOException {
        assertTrue(Cartridge.supportsBatterySave(romWithType(0x20)));
    }

    @Test
    public void pocketCameraAdvertisesItsPersistentCartridgeRam() throws IOException {
        assertTrue(Cartridge.supportsBatterySave(romWithType(0xfc)));
    }

    @Test
    public void ordinaryRomDoesNotAdvertiseABatterySave() throws IOException {
        assertFalse(Cartridge.supportsBatterySave(romWithType(0x00)));
    }

    private static Rom romWithType(int type) throws IOException {
        byte[] bytes = new byte[0x8000];
        bytes[0x0147] = (byte) type;
        return new Rom(bytes);
    }
}
