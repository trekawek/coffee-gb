package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StatRegisterTest {

    @Test
    public void hblankEnableMasksStatWriteGlitchAtOamBoundary() {
        Fixture fixture = new Fixture();
        fixture.advanceToHBlank();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.clearInterrupts();

        fixture.advanceToNextLineStart();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void oamBoundaryStillTriggersStatWriteGlitchWithoutHblankMask() {
        Fixture fixture = new Fixture();
        fixture.advanceToNextLineStart();
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void lycEdgeIsNotRepeatedWhenIfIsClearedWhileComparatorSettles() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);

        fixture.advanceToNextLineStart();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertTrue(fixture.interrupts.isInterruptRequested());
        fixture.clearInterrupts();
        for (int i = 0; i < 4; i++) {
            fixture.tick();
        }
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    private static class Fixture {

        private final InterruptManager interrupts;

        private final StatRegister stat;

        private final SpeedMode speedMode;

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final Gpu gpu;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean gbc) {
            interrupts = new InterruptManager(gbc);
            stat = new StatRegister(interrupts);
            speedMode = new SpeedMode(gbc);
            gpu = new Gpu(
                    new Display(gbc),
                    new Dma(new Ram(0, 0x10000), oam, speedMode),
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    gbc,
                    speedMode);
            stat.init(gpu);
        }

        private void advanceToHBlank() {
            while (!gpu.isMode0IntWindow()) {
                tick();
            }
        }

        private void advanceToNextLineStart() {
            int targetLine = gpu.getLine() + 1;
            while (gpu.getLine() != targetLine || gpu.getTicksInLine() != 0) {
                tick();
            }
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }

        private void clearInterrupts() {
            interrupts.setByte(0xff0f, 0);
        }

        private int lcdInterruptFlag() {
            return interrupts.getByte(0xff0f) & (1 << LCDC.ordinal());
        }
    }
}
