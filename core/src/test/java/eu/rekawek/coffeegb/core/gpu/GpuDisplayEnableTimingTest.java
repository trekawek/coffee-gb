package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.memento.Memento;
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
    public void normalSpeedCpuReadSamplesFirstLineModeZeroOneDotEarly() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff43, 1);
        fixture.gpu.setByte(0xff40, 0x11);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(88);
        fixture.gpu.setByte(0xff43, 7);
        fixture.advanceTo(251);

        assertEquals(Mode.HBlank, fixture.gpu.getMode());
        assertFalse(fixture.gpu.hasObjectsOnLine());
        assertFalse(fixture.gpu.isMode0IntWindow());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());

        fixture.stat.captureCpuStatReadPhase();
        assertEquals(Mode.HBlank.ordinal(),
                fixture.stat.getByte(StatRegister.ADDRESS) & 0x03);
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
    public void dmgLowerFineScxSpriteLatchReleasesTwoDotsBeforeModeZeroEdge() {
        Fixture fixture = new Fixture(1, false, false);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff43, 4);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 9);
        fixture.gpu.setByte(0xff40, 0x93);
        fixture.advanceTo(1, 0);

        int threeDotsBefore = -1;
        int twoDotsBefore = -1;
        int oneDotBefore = -1;
        while (!fixture.gpu.isMode0IntWindow()) {
            threeDotsBefore = twoDotsBefore;
            twoDotsBefore = oneDotBefore;
            oneDotBefore = fixture.gpu.getVisibleStatMode();
            fixture.tick();
        }

        assertEquals(Mode.PixelTransfer.ordinal(), threeDotsBefore);
        assertEquals(Mode.HBlank.ordinal(), twoDotsBefore);
        assertEquals(Mode.HBlank.ordinal(), oneDotBefore);
    }

    @Test
    public void dmgHighFineScxSpriteLatchPersistsThroughModeZeroEdge() {
        for (int fineScx = 5; fineScx <= 7; fineScx++) {
            Fixture fixture = new Fixture(1, false, false);
            fixture.gpu.setByte(0xff40, 0x00);
            fixture.gpu.setByte(0xff43, fineScx);
            fixture.oam.setByte(0xfe00, 16);
            fixture.oam.setByte(0xfe01, 9);
            fixture.gpu.setByte(0xff40, 0x93);
            fixture.advanceTo(1, 0);

            while (!fixture.gpu.isMode0IntWindow()) {
                fixture.tick();
            }

            assertEquals("SCX=" + fineScx,
                    Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
            fixture.tick();
            assertEquals("SCX=" + fineScx,
                    Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
        }
    }

    @Test
    public void terminalCgbWindowStartKeepsTwoReadableDotsBeyondMode0Interrupt() {
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
        for (int tailDot = 0; tailDot < 3; tailDot++) {
            assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
            fixture.tick();
        }
        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void terminalCgbWindowStartKeepsFiveDotsBeyondDoubleSpeedMode0Edge() {
        Fixture fixture = new Fixture(2);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 166);
        fixture.gpu.setByte(0xff40, 0xb1);

        while (!fixture.gpu.isMode0IntWindow()) {
            fixture.tick();
        }

        int readableTailDots = 0;
        while (fixture.gpu.getVisibleStatMode() == Mode.PixelTransfer.ordinal()) {
            fixture.tick();
            readableTailDots++;
        }
        assertEquals(6, readableTailDots);
        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void terminalWindowAndX167ObjectRephaseMode0EventByTwoDots() {
        int ordinaryEdge = mode0EdgeWithX167Object(false);
        int terminalEdge = mode0EdgeWithX167Object(true);

        assertEquals("the terminal comparator collision crosses the CPU event latch two dots later",
                ordinaryEdge + 2, terminalEdge);
    }

    @Test
    public void terminalWindowMode0PredictionSurvivesMemento() {
        Fixture fixture = fixtureWithX167Object(true);
        Memento<Gpu> immediatelyBeforeEdge = null;

        while (!fixture.gpu.isMode0IntWindow()) {
            immediatelyBeforeEdge = fixture.gpu.saveToMemento();
            fixture.tick();
        }
        int edge = fixture.gpu.getTicksInLine();

        fixture.gpu.restoreFromMemento(immediatelyBeforeEdge);
        assertFalse(fixture.gpu.isMode0IntWindow());
        fixture.tick();
        assertTrue(fixture.gpu.isMode0IntWindow());
        assertEquals(edge, fixture.gpu.getTicksInLine());
    }

    @Test
    public void terminalWindowUsesTheLongStatPredictionOnlyForX167Object() {
        int x166Tail = terminalReadableTailWithObjectAt(166);
        int x167Tail = terminalReadableTailWithObjectAt(167);
        int x168Tail = terminalReadableTailWithObjectAt(168);

        assertEquals("resetting the tile predictor at WX=166 adds ten X=167 cycles",
                x166Tail + 10, x167Tail);
        assertEquals("the long prediction is scoped to X=167 exactly",
                x166Tail, x168Tail);
    }

    @Test
    public void lateWx166WriteCannotResurrectAMissedTerminalComparator() {
        int ordinaryTail = readableTailAfterMissedTerminalComparator(false);
        int lateWriteTail = readableTailAfterMissedTerminalComparator(true);

        assertEquals("HBlank uses the captured event rather than live WX",
                ordinaryTail, lateWriteTail);
    }

    @Test
    public void terminalWindowKeepsOamAndVramLockedThroughTheirHandoff() {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0x8000, 0x55);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 166);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.advanceTo(1, 0);

        while (fixture.gpu.getMode() != Mode.HBlank) {
            fixture.tick();
        }

        int readLockedDots = 0;
        int writeLockedDots = 0;
        while (!fixture.gpu.isOamAvailableForCpu(false) && readLockedDots < 20) {
            assertEquals("VRAM and OAM retain the same terminal prediction",
                    0xff, fixture.gpu.getByte(0x8000));
            if (!fixture.gpu.isOamAvailableForCpu(true)) {
                writeLockedDots++;
            }
            fixture.tick();
            readLockedDots++;
        }
        assertEquals(6, readLockedDots);
        assertEquals("WX=166 does not extend the independently timed write latch",
                2, writeLockedDots);
        assertTrue(fixture.gpu.isOamAvailableForCpu(false));
        assertTrue(fixture.gpu.isOamAvailableForCpu(true));
        assertEquals(0x55, fixture.gpu.getByte(0x8000));
    }

    @Test
    public void terminalWindowPreservesDmaOwnedOamReadHandoff() {
        int ordinaryHandoff = dmaOwnedOamReadLockedDots(false);
        int terminalHandoff = dmaOwnedOamReadLockedDots(true);

        assertEquals("WX=166 must not delay the OAM-DMA read release",
                ordinaryHandoff, terminalHandoff);
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
    public void doubleSpeedDisabledWindowExposesModeZeroDuringModeZeroLookahead() {
        Fixture fixture = new Fixture(2);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 15);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.advanceTo(1, 130);

        fixture.gpu.setByte(0xff40, 0x91);
        int modeImmediatelyBeforeModeZero = -1;
        while (!fixture.gpu.isMode0IntWindow()) {
            if (fixture.gpu.getMode() == Mode.HBlank) {
                modeImmediatelyBeforeModeZero = fixture.gpu.getVisibleStatMode();
            }
            fixture.tick();
        }

        assertEquals(Mode.HBlank.ordinal(), modeImmediatelyBeforeModeZero);
    }

    @Test
    public void doubleSpeedMode2DispatchExtendsObjectFreeStatTailThroughHblankPlusThree() {
        Fixture fixture = new Fixture(2);
        fixture.advanceTo(1, 0);
        fixture.gpu.onDoubleSpeedMode2Dispatch();

        while (!fixture.gpu.isMode0IntWindow()) {
            fixture.tick();
        }
        int hblankEdge = fixture.gpu.getTicksInLine();

        for (int tailDot = 0; tailDot < 4; tailDot++) {
            assertEquals(hblankEdge + tailDot, fixture.gpu.getTicksInLine());
            assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
            fixture.tick();
        }
        assertEquals(hblankEdge + 4, fixture.gpu.getTicksInLine());
        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void doubleSpeedBackgroundWithoutMode2DispatchHasNoExtendedStatTail() {
        Fixture fixture = new Fixture(2);
        fixture.advanceTo(1, 0);

        while (!fixture.gpu.isMode0IntWindow()) {
            fixture.tick();
        }

        for (int tailDot = 0; tailDot < 3; tailDot++) {
            fixture.tick();
        }
        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
    }

    @Test
    public void cgbDmgCompatibilityBgDisabledDoesNotForceModeZeroAtDot250() {
        Fixture fixture = new Fixture(1, true);
        fixture.gpu.setByte(0xff40, 0x90);
        fixture.advanceTo(1, 250);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.gpu.getVisibleStatMode());
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

    private static int mode0EdgeWithX167Object(boolean terminalWindow) {
        Fixture fixture = fixtureWithX167Object(terminalWindow);

        while (!fixture.gpu.isMode0IntWindow()) {
            fixture.tick();
        }
        return fixture.gpu.getTicksInLine();
    }

    private static Fixture fixtureWithX167Object(boolean terminalWindow) {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 167);
        if (terminalWindow) {
            fixture.gpu.setByte(0xff4a, 0);
            fixture.gpu.setByte(0xff4b, 166);
        }
        fixture.gpu.setByte(0xff40, terminalWindow ? 0xb3 : 0x93);
        fixture.advanceTo(1, 0);
        return fixture;
    }

    private static int terminalReadableTailWithObjectAt(int x) {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, x);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 166);
        fixture.gpu.setByte(0xff40, 0xb3);
        fixture.advanceTo(1, 0);

        while (fixture.gpu.getMode() != Mode.HBlank) {
            fixture.tick();
        }
        int readableDots = 0;
        while (fixture.gpu.getVisibleStatMode() == Mode.PixelTransfer.ordinal()
                && readableDots < 30) {
            fixture.tick();
            readableDots++;
        }
        return readableDots;
    }

    private static int readableTailAfterMissedTerminalComparator(boolean writeWxInHblank) {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, 167);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.advanceTo(1, 0);

        while (fixture.gpu.getMode() != Mode.HBlank) {
            fixture.tick();
        }
        if (writeWxInHblank) {
            fixture.gpu.setByte(0xff4b, 166);
        }
        int readableDots = 0;
        while (fixture.gpu.getVisibleStatMode() == Mode.PixelTransfer.ordinal()
                && readableDots < 20) {
            fixture.tick();
            readableDots++;
        }
        return readableDots;
    }

    private static int dmaOwnedOamReadLockedDots(boolean terminalWindow) {
        Fixture fixture = new Fixture(1);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff4a, 0);
        fixture.gpu.setByte(0xff4b, terminalWindow ? 166 : 167);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.dma.setByte(0xff46, 0x00);
        fixture.advanceTo(1, 0);

        while (fixture.gpu.getMode() != Mode.HBlank) {
            fixture.tick();
        }
        int lockedDots = 0;
        while (!fixture.gpu.isOamAvailableForCpu(false) && lockedDots < 20) {
            fixture.tick();
            lockedDots++;
        }
        return lockedDots;
    }

    private static class Fixture {

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final StatRegister stat = new StatRegister(new InterruptManager(true));

        private final Dma dma;

        private final Gpu gpu;

        private Fixture(int speed) {
            this(speed, false);
        }

        private Fixture(int speed, boolean dmgCompat) {
            this(speed, dmgCompat, true);
        }

        private Fixture(int speed, boolean dmgCompat, boolean gbc) {
            SpeedMode speedMode = new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
            speedMode.setDmgCompat(dmgCompat);
            dma = new Dma(new Ram(0, 0x10000), oam, speedMode);
            gpu = new Gpu(
                    new Display(gbc),
                    dma,
                    oam,
                    new VRamTransfer(NULL_EVENT_BUS),
                    stat,
                    gbc,
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
            advanceTo(0, dot);
        }

        private void advanceTo(int line, int dot) {
            while (gpu.getLine() != line || gpu.getTicksInLine() != dot) {
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
