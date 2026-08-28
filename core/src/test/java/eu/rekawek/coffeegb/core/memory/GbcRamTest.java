package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GbcRamTest {

    @Test
    public void nativeCgbSvbkUnusedBitsReadHigh() {
        GbcRam ram = new GbcRam();
        ram.setSpeedMode(new SpeedMode(true));

        ram.setByte(GbcRam.SVBK, 0x03);

        assertEquals(0xfb, ram.getByte(GbcRam.SVBK));
    }

    @Test
    public void dmgCompatibilitySvbkReadsAsOpenBus() {
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setDmgCompat(true);
        GbcRam ram = new GbcRam();
        ram.setSpeedMode(speedMode);

        ram.setByte(GbcRam.SVBK, 0x03);

        assertEquals(0xff, ram.getByte(GbcRam.SVBK));
    }

    @Test
    public void unusedSvbkWriteBitsDoNotEnterPortableState() {
        GbcRam ram = new GbcRam();
        ram.setSpeedMode(new SpeedMode(true));

        ram.setByte(GbcRam.SVBK, 0xf8);

        assertEquals(0xf8, ram.getByte(GbcRam.SVBK));
        assertEquals(0, ((GbcRam.GbcRamState) ram.captureState()).svbk());
    }

    @Test
    public void legacyFullByteSvbkStateIsNormalizedOnRestore() {
        GbcRam ram = new GbcRam();
        ram.setSpeedMode(new SpeedMode(true));

        ram.restoreState(new GbcRam.GbcRamState(new int[7 * 0x1000], 0xfe));

        assertEquals(0xfe, ram.getByte(GbcRam.SVBK));
        assertEquals(6, ((GbcRam.GbcRamState) ram.captureState()).svbk());
    }
}
