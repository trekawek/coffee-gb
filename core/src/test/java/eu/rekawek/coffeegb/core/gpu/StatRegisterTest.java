package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.LCDC;
import static eu.rekawek.coffeegb.core.cpu.InterruptManager.InterruptType.VBlank;
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
    public void oamPulseHasEndedAtReadableLineStart() {
        Fixture fixture = new Fixture();
        fixture.advanceToNextLineStart();
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
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

    @Test
    public void vblankSourceMasksLineZeroOamSource() {
        Fixture fixture = new Fixture();
        fixture.advanceTo(144, 8);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x30);
        fixture.clearInterrupts();

        fixture.advanceTo(0, 4);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbLyAdvancesAtDot452AndRetains153ForFourDots() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(152, 451);

        assertEquals(152, fixture.readLy());
        fixture.tick();
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 0);
        assertEquals(153, fixture.readLy());
        fixture.advanceTo(153, 3);
        assertEquals(153, fixture.readLy());
        fixture.tick();
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void cgbDoubleSpeedTailLycEdgeDuringVblankIsReleasedAtLineStart() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 152);
        fixture.clearInterrupts();
        fixture.advanceTo(151, 453);

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());

        fixture.advanceTo(152, 0);
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void cgbDoubleSpeedNewFrameLycEdgeUsesTwoDotCpuCycle() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(152, 400);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.clearInterrupts();
        fixture.advanceTo(153, 5);

        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());

        fixture.advanceTo(153, 8);
        assertTrue(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void cgbStatProjectsNextLineModeAtDot454() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(0, 453);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbStatExposesPixelTransferAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 77);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbCoincidenceRemainsReadableThroughDot452() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(0, 452);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbDoubleSpeedCoincidenceSwitchesToNextLineAtDot454() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(0, 453);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbDoubleSpeedUsesTwoDotReadCyclesAtStatBoundaries() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(0, 453);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());

        fixture.advanceTo(1, 77);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedUsesCpuLyEdgeAndRetains153AfterRollover() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(152, 451);

        assertEquals(152, fixture.readLy());
        fixture.tick();
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 1);
        assertEquals(153, fixture.readLy());
        fixture.tick();
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void cgbDoubleSpeedTailLycEdgeIsReadableBeforeCpuAcceptance() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.clearInterrupts();
        fixture.advanceTo(0, 453);

        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());

        fixture.advanceTo(1, 0);
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void cgbDoubleSpeedVblankFlagIsReadableBeforeCpuAcceptance() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << VBlank.ordinal());
        fixture.clearInterrupts();
        fixture.advanceTo(143, 453);

        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        fixture.tick();
        assertTrue(fixture.interrupts.isInterruptFlagSet(VBlank));
        assertFalse(fixture.interrupts.isInterruptRequested());

        fixture.advanceTo(144, 0);
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void cgbDmgCompatibilityUsesItsOwnLyBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.speedMode.setDmgCompat(true);
        fixture.advanceTo(152, 449);

        assertEquals(152, fixture.readLy());
        fixture.tick();
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 0);
        assertEquals(153, fixture.readLy());
        fixture.advanceTo(153, 4);
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void cgbDmgCompatibilityUsesItsOwnStatBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.speedMode.setDmgCompat(true);
        fixture.advanceTo(0, 453);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);

        fixture.advanceTo(153, 454);
        assertEquals(Mode.VBlank.ordinal(), fixture.readStatMode());
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
            this(gbc, false);
        }

        private Fixture(boolean gbc, boolean doubleSpeed) {
            interrupts = new InterruptManager(gbc);
            stat = new StatRegister(interrupts);
            speedMode = doubleSpeed ? new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return 2;
                }
            } : new SpeedMode(gbc);
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

        private int readLy() {
            return gpu.getByte(GpuRegister.LY.getAddress());
        }

        private int readStatMode() {
            return stat.getByte(StatRegister.ADDRESS) & 0x03;
        }
    }
}
