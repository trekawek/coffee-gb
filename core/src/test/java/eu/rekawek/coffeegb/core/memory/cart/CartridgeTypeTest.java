package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CartridgeTypeTest {

    @Test
    public void onlyMbc5MotorCartridgesReportRumbleCapability() {
        assertTrue(CartridgeType.ROM_MBC5_RUMBLE.isRumble());
        assertTrue(CartridgeType.ROM_MBC5_RUMBLE_SRAM.isRumble());
        assertTrue(CartridgeType.ROM_MBC5_RUMBLE_SRAM_BATTERY.isRumble());

        assertFalse(CartridgeType.ROM_MBC7_SENSOR_RUMBLE_RAM_BATTERY.isRumble());
        assertFalse(CartridgeType.ROM_MBC5_RAM_BATTERY.isRumble());
    }
}
