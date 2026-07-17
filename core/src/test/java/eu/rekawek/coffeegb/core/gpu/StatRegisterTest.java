package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;

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
    public void vblankSourceMasksLineZeroOamSource() {
        Fixture fixture = new Fixture();
        fixture.advanceTo(144, 8);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x30);
        fixture.clearInterrupts();

        fixture.advanceTo(0, 4);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    private static class Fixture {

        private final InterruptManager interrupts = new InterruptManager(false);

        private final StatRegister stat = new StatRegister(interrupts);

        private final SpeedMode speedMode = new SpeedMode(false);

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final Gpu gpu = new Gpu(
                new Display(false),
                new Dma(new Ram(0, 0x10000), oam, speedMode),
                oam,
                new VRamTransfer(NULL_EVENT_BUS),
                stat,
                false,
                speedMode);

        private Fixture() {
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

        private void advanceTo(int line, int ticksInLine) {
            do {
                tick();
            } while (gpu.getLine() != line || gpu.getTicksInLine() != ticksInLine);
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
