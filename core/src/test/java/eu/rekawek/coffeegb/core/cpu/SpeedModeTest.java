package eu.rekawek.coffeegb.core.cpu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SpeedModeTest {

    @Test
    public void canEnableKnownDmgCompatibilitySpeedSwitchExtension() {
        SpeedMode speedMode = new SpeedMode(false, true);
        speedMode.setDmgCompat(true);

        speedMode.setByte(0xff4d, 0x01);

        assertEquals(0x7f, speedMode.getByte(0xff4d));
        assertTrue(speedMode.onStop());
        assertEquals(2, speedMode.getSpeedMode());
        assertEquals(0xfe, speedMode.getByte(0xff4d));
    }
}
