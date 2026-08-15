package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.memory.cart.Cartridge;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BiosShadowTest {

    @Test
    public void dmgFf50IgnoresWritesWithD0Clear() throws IOException {
        BiosShadow shadow = shadow(HardwareProfileRegistry.DMG);

        assertEquals(0xfe, shadow.getByte(0xff50));
        shadow.setByte(0xff50, 0x00);
        shadow.setByte(0xff50, 0xfe);

        assertFalse(shadow.isBootFinished());
        assertEquals(0xfe, shadow.getByte(0xff50));
    }

    @Test
    public void dmgFf50D0SetsAStickyDisableLatch() throws IOException {
        BiosShadow shadow = shadow(HardwareProfileRegistry.DMG);

        shadow.setByte(0xff50, 0x01);
        assertTrue(shadow.isBootFinished());
        assertEquals(0xff, shadow.getByte(0xff50));

        shadow.setByte(0xff50, 0x00);
        assertTrue(shadow.isBootFinished());
        assertEquals(0xff, shadow.getByte(0xff50));
    }

    @Test
    public void cgbUsesTheSameStickyD0Rule() throws IOException {
        BiosShadow shadow = shadow(HardwareProfileRegistry.CGB);

        shadow.setByte(0xff50, 0x00);
        assertFalse(shadow.isBootFinished());
        assertEquals(0xfe, shadow.getByte(0xff50));

        shadow.setByte(0xff50, 0x01);
        assertTrue(shadow.isBootFinished());
        assertEquals(0xff, shadow.getByte(0xff50));
    }

    private static BiosShadow shadow(HardwareProfile hardwareProfile) throws IOException {
        return new BiosShadow(
                new Bios(hardwareProfile),
                cartridge());
    }

    private static Cartridge cartridge() throws IOException {
        byte[] rom = new byte[0x8000];
        rom[0x0147] = 0x00;
        return new Cartridge(new Rom(rom), Battery.NULL_BATTERY);
    }
}
