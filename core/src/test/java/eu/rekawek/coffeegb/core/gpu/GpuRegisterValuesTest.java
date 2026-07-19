package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class GpuRegisterValuesTest {

    @Test
    public void dmgCompatibilityIgnoresVramBankWrites() {
        SpeedMode speedMode = new SpeedMode(true);
        speedMode.setDmgCompat(true);
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(true);
        registers.setSpeedMode(speedMode);

        registers.setByte(GpuRegister.VBK.getAddress(), 1);

        assertEquals(0, registers.get(GpuRegister.VBK));
    }

    @Test
    public void nativeCgbModeAcceptsVramBankWrites() {
        SpeedMode speedMode = new SpeedMode(true);
        GpuRegisterValues registers = new GpuRegisterValues();
        registers.setGbc(true);
        registers.setSpeedMode(speedMode);

        registers.setByte(GpuRegister.VBK.getAddress(), 1);

        assertEquals(1, registers.get(GpuRegister.VBK));
    }
}
