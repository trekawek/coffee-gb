package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class UndocumentedGbcRegistersTest {

    @Test
    public void hasCgbPowerOnValues() {
        UndocumentedGbcRegisters registers = new UndocumentedGbcRegisters();

        assertEquals(0x00, registers.getByte(0xff72));
        assertEquals(0x00, registers.getByte(0xff73));
        assertEquals(0x00, registers.getByte(0xff74));
        assertEquals(0x8f, registers.getByte(0xff75));
    }

    @Test
    public void undocumentedStorageRegistersRemainWritable() {
        UndocumentedGbcRegisters registers = new UndocumentedGbcRegisters();

        registers.setByte(0xff72, 0xff);
        registers.setByte(0xff73, 0xff);
        registers.setByte(0xff74, 0xff);

        assertEquals(0xff, registers.getByte(0xff72));
        assertEquals(0xff, registers.getByte(0xff73));
        assertEquals(0xff, registers.getByte(0xff74));
    }

    @Test
    public void ff74IsDisabledInDmgCompatibilityMode() {
        UndocumentedGbcRegisters registers = new UndocumentedGbcRegisters();
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setDmgCompat(true);
        registers.setSpeedMode(speedMode);

        assertEquals(0xff, registers.getByte(0xff74));
        registers.setByte(0xff74, 0x42);
        assertEquals(0xff, registers.getByte(0xff74));
    }
}
