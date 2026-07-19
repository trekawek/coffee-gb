package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpuDisplayEnableTimingTest {

    @Test
    public void normalSpeedFirstLineUsesSeparateVramPaletteAndOamLatches() {
        Fixture fixture = new Fixture(1);
        fixture.seedAndEnableDisplay();
        fixture.advanceTo(79);

        assertEquals(0x55, fixture.gpu.getByte(0x8000));
        assertEquals(0x55, fixture.gpu.getByte(0xff69));
        assertFalse(fixture.gpu.isOamAvailableForCpu(true));
        fixture.gpu.setByte(0x8000, 0xaa);
        fixture.gpu.setByte(0xff69, 0xaa);

        fixture.advanceTo(80);
        assertEquals(0xff, fixture.gpu.getByte(0x8000));
        assertEquals(0xff, fixture.gpu.getByte(0xff69));
        fixture.gpu.setByte(0x8000, 0xbb);
        fixture.gpu.setByte(0xff69, 0xbb);

        fixture.advanceTo(320);
        assertEquals(0xaa, fixture.gpu.getByte(0x8000));
        assertEquals(0xaa, fixture.gpu.getByte(0xff69));
    }

    @Test
    public void doubleSpeedFirstLineClosesEachCpuBusOnItsOwnDot() {
        Fixture fixture = new Fixture(2);
        fixture.seedAndEnableDisplay();
        fixture.advanceTo(75);
        assertTrue(fixture.gpu.isOamAvailableForCpu(true));

        fixture.advanceTo(77);
        assertFalse(fixture.gpu.isOamAvailableForCpu(true));

        fixture.advanceTo(78);
        assertEquals(0x55, fixture.gpu.getByte(0x8000));
        assertEquals(0x55, fixture.gpu.getByte(0xff69));

        fixture.advanceTo(79);
        assertEquals(0xff, fixture.gpu.getByte(0x8000));
        assertEquals(0x55, fixture.gpu.getByte(0xff69));

        fixture.advanceTo(80);
        assertEquals(0xff, fixture.gpu.getByte(0xff69));
    }

    @Test
    public void firstLineBackgroundModeLatchKeepsItsFourDotTail() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff43, 7);
        fixture.seedAndEnableDisplay();
        fixture.advanceTo(248);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());

        fixture.advanceTo(252);
        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void firstLineSpriteCandidateHoldsCgbReadableModeThroughPhysicalHandoff() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);
        fixture.gpu.setByte(0xff40, 0x93);

        fixture.advanceTo(247);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());

        while (fixture.gpu.getMode() != Mode.HBlank) {
            fixture.tick();
        }
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
        while (fixture.gpu.getVisibleStatMode() == Mode.PixelTransfer.ordinal()) {
            fixture.tick();
        }
        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void terminalCgbWindowStartSeparatesReadableModeFromMode0Interrupt() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 166);
        fixture.gpu.setByte(0xff40, 0xb1);

        while (fixture.gpu.getMode() != Mode.HBlank) {
            fixture.tick();
        }
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
        assertFalse(fixture.gpu.isMode0IntWindow());

        int readableTailDots = 0;
        while (!fixture.gpu.isMode0IntWindow()) {
            assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
            fixture.tick();
            readableTailDots++;
        }
        assertEquals(2, readableTailDots);
        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void doubleSpeedWindowKeepsReadableModeThroughInternalHblankEdge() {
        Fixture fixture = new Fixture(2);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff43, 5);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 7);
        fixture.gpu.setByte(0xff40, 0xb1);

        while (fixture.gpu.getMode() != Mode.HBlank) {
            fixture.tick();
        }

        for (int tailDot = 0; tailDot < 6; tailDot++) {
            assertEquals("SCX=5 retains its predicted double-speed window tail",
                    Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
            fixture.tick();
        }
        assertEquals("the readable latch releases after the final predicted dot",
                Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void lcdEnableSamplesLineZeroWindowMasterBeforeLaterWyWrite() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 7);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.gpu.setByte(0xff4a, 0xff);

        fixture.advanceTo(247);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    private static class Fixture {

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final StatRegister stat = new StatRegister(new InterruptManager(true));

        private final Gpu gpu;

        private Fixture(int speed) {
            SpeedMode speedMode = new SpeedMode(true) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
            gpu = new Gpu(
                    new Display(true),
                    new Dma(new Ram(0, 0x10000), oam, speedMode),
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    true,
                    speedMode);
            stat.init(gpu);
        }

        private void seedAndEnableDisplay() {
            gpu.setByte(0xff40, 0x00);
            gpu.setByte(0x8000, 0x55);
            gpu.setByte(0xff68, 0x00);
            gpu.setByte(0xff69, 0x55);
            gpu.setByte(0xff40, 0x91);
        }

        private void advanceTo(int dot) {
            while (gpu.getLine() != 0 || gpu.getTicksInLine() != dot) {
                gpu.tick();
                stat.tick();
            }
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }
    }
}
