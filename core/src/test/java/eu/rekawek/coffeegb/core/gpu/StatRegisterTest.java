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
    public void cgbLycEdgeTakesPrecedenceOverRetiringMode0Source() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceToHBlank();
        fixture.clearInterrupts();

        fixture.advanceTo(1, 0);

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
    public void rephasedNormalSpeedCgbStatProjectsMode2InFinalCpuBusSlot() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 449);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedDoubleSpeedCgbStatProjectsMode2InFinalCpuBusSlot() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 452);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedNormalSpeedCgbStatProjectsMode2AtFrameTail() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(153, 449);

        assertEquals(Mode.VBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedCgbDmgCompatibilityKeepsOrdinaryTailMux() {
        Fixture fixture = new Fixture(true);
        fixture.speedMode.setDmgCompat(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 450);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedCgbLycSourceKeepsCurrentModeInTailMux() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(1, 450);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbLycStatReadRetainsHblankThroughDot454OnObjectFreeLine() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(0, 454);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbSameLineLycWriteReleasesDot454ModeReadMux() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.advanceTo(1, 100);

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 454);

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
    public void cgbCpuBusStillSamplesOamModeAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.preCpuTick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbWindowEnabledBackgroundMode3ReleaseFollowsReadableLatchPhase() {
        for (int[] timing : new int[][] {{0, 250}, {2, 252}, {3, 250}, {5, 250}}) {
            int scrollX = timing[0];
            Fixture fixture = new Fixture(true);
            fixture.gpu.setByte(GpuRegister.WY.getAddress(), 0xff);
            fixture.gpu.setByte(0xff40, 0xb1);
            fixture.gpu.setByte(GpuRegister.SCX.getAddress(), scrollX);
            int releaseTick = timing[1];
            fixture.advanceTo(1, releaseTick - 1);

            assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
            fixture.tick();
            assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        }
    }

    @Test
    public void cgbWindowDisabledBackgroundWaitsForShiftedHblankEdge() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 250);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbStartedWindowModeReadPredictsHblankTwoDotsAhead() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.advanceTo(1, 240);

        while (fixture.readStatMode() == Mode.PixelTransfer.ordinal()) {
            fixture.tick();
        }
        assertEquals(257, fixture.gpu.getTicksInLine());
    }

    @Test
    public void cgbDisabledWindowModeReadUsesTwoDotM0Prediction() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 15);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.advanceTo(1, 120);
        fixture.gpu.setByteFromCpu(0xff40, 0x91);
        fixture.advanceTo(1, 240);

        while (fixture.readStatMode() == Mode.PixelTransfer.ordinal()) {
            fixture.tick();
        }
        assertEquals(259, fixture.gpu.getTicksInLine());
    }

    @Test
    public void cgbDoubleSpeedSelectedObjectsKeepTheirPredictedWindowTail() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.setByte(0xff40, 0x00);
        for (int i = 0; i < 9; i++) {
            fixture.oam.setByte(0xfe00 + 4 * i, 16);
            fixture.oam.setByte(0xfe01 + 4 * i, 8);
        }
        fixture.gpu.setByte(GpuRegister.WY.getAddress(), 0);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 8);
        fixture.gpu.setByte(0xff40, 0xb3);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 314);

        assertTrue(fixture.gpu.hasObjectsOnLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(315, fixture.gpu.getTicksInLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(316, fixture.gpu.getTicksInLine());
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.preCpuTick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedWindowMode3LatchFollowsTheFullOutputTail() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.WY.getAddress(), 0);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 7);
        fixture.gpu.setByte(0xff40, 0xb1);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 258);

        assertFalse(fixture.gpu.hasObjectsOnLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.preCpuTick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(259, fixture.gpu.getTicksInLine());
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.preCpuTick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void rephasedBackgroundCpuReadSettlesAtEndOfMachineCycle() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 2);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 244);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.advanceTo(1, 248);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        Fixture phaseThree = new Fixture(true);
        phaseThree.gpu.setByte(GpuRegister.SCX.getAddress(), 1);
        phaseThree.gpu.onSpeedSwitch();
        phaseThree.advanceTo(1, 247);

        assertEquals(Mode.PixelTransfer.ordinal(), phaseThree.readStatMode());

        Fixture phaseTwo = new Fixture(true);
        phaseTwo.gpu.onSpeedSwitch();
        phaseTwo.advanceTo(1, 246);

        assertEquals(Mode.HBlank.ordinal(), phaseTwo.readStatMode());
    }

    @Test
    public void speedSwitchCompletionRetainsOldStatPhaseUntilNextLine() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 2);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 248);
        fixture.gpu.onSpeedSwitchComplete();
        var completionLine = fixture.gpu.saveToMemento();

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());

        fixture.advanceTo(2, 248);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        fixture.gpu.restoreFromMemento(completionLine);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void sameLineScxWriteRetainsDynamicStatPhase() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 200);
        fixture.gpu.setByteFromCpu(GpuRegister.SCX.getAddress(), 2);
        fixture.advanceTo(1, 248);

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
    public void cgbLcdEnableLineReleasesCoincidenceAtItsShortenedEdge() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(0, 450);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(451, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void cgbDoubleSpeedLcdEnableLineReleasesCoincidenceAtDot453() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(0, 452);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.gpu.getLine());
        assertEquals(453, fixture.gpu.getTicksInLine());
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
    public void cgbDoubleSpeedLycWriteRequestCrossesThirdPpuClock() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(1, 100);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.clearInterrupts();

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
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

    @Test
    public void cgbLateLycWriteDoesNotUnmaskCapturedMode0Event() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 244);
        fixture.clearInterrupts();

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.advanceTo(1, 250);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbMode0DisableWithinCaptureWindowDoesNotWithdrawEvent() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 244);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.advanceToHBlank();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbTailMode0EnableDoesNotCreateCombinationalInterrupt() {
        for (boolean doubleSpeed : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(true, doubleSpeed);
            fixture.advanceTo(1, 450);
            fixture.clearInterrupts();

            fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
            fixture.advanceTo(2, 0);

            assertEquals(0, fixture.lcdInterruptFlag());
        }
    }

    @Test
    public void cgbTailMode0EnableUsesCoincidencePhaseWhenSourceIsDisabled() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 450);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(2, 0);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbTailMode0SuppressionExpiresBeforeLaterScanlines() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 450);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(2, 449);
        fixture.clearInterrupts();

        fixture.advanceTo(2, 450);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(2, 452);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode0BlocksLycEnableAfterTwentyDotSettleWindow() {
        Fixture atBoundary = activeCgbMode0(false);
        int mode0Tick = atBoundary.gpu.getMode0InterruptTick();
        atBoundary.advanceTo(1, mode0Tick + 20);
        atBoundary.clearInterrupts();

        atBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(1 << LCDC.ordinal(), atBoundary.lcdInterruptFlag());

        Fixture afterBoundary = activeCgbMode0(false);
        mode0Tick = afterBoundary.gpu.getMode0InterruptTick();
        afterBoundary.advanceTo(1, mode0Tick + 21);
        afterBoundary.clearInterrupts();

        afterBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(0, afterBoundary.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedCgbMode0ReleasesLycEnableForFinalEightCpuClocks() {
        Fixture beforeBoundary = activeCgbMode0(true);
        beforeBoundary.advanceTo(1, 451);
        beforeBoundary.clearInterrupts();

        beforeBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(0, beforeBoundary.lcdInterruptFlag());

        Fixture atBoundary = activeCgbMode0(true);
        atBoundary.advanceTo(1, 452);
        atBoundary.clearInterrupts();

        atBoundary.stat.setByte(StatRegister.ADDRESS, 0x40);

        assertEquals(1 << LCDC.ordinal(), atBoundary.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode1LycEnableBoundaryFollowsRephasedClock() {
        assertEquals(0, cgbMode1LycEnableFlagAt(449, true));
        assertEquals(1 << LCDC.ordinal(), cgbMode1LycEnableFlagAt(450, true));
        assertEquals(0, cgbMode1LycEnableFlagAt(452, false));
        assertEquals(1 << LCDC.ordinal(), cgbMode1LycEnableFlagAt(453, false));
    }

    @Test
    public void dmgStatWriteSettlesBeforeFollowingMode0Edge() {
        Fixture fixture = new Fixture();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 100);

        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.clearInterrupts();
        fixture.advanceToHBlank();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbMode0StatCaptureSurvivesMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 244);
        fixture.clearInterrupts();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        var gpuMemento = fixture.gpu.saveToMemento();
        var statMemento = fixture.stat.saveToMemento();
        var interruptMemento = fixture.interrupts.saveToMemento();

        fixture.advanceToHBlank();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        fixture.gpu.restoreFromMemento(gpuMemento);
        fixture.stat.restoreFromMemento(statMemento);
        fixture.interrupts.restoreFromMemento(interruptMemento);
        fixture.advanceToHBlank();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void modeEventRegisterLatchesSurviveMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 244);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        var gpuMemento = fixture.gpu.saveToMemento();
        var statMemento = fixture.stat.saveToMemento();

        fixture.advanceTo(1, 250);
        fixture.gpu.restoreFromMemento(gpuMemento);
        fixture.stat.restoreFromMemento(statMemento);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 250);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbM2EventPublishesBeforeSameTimestampCpuRead() {
        Fixture fixture = pendingNormalSpeedCgbM2Event();

        fixture.stat.preCpuTick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        fixture.stat.preCpuTick();
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.stat.preCpuTick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void normalSpeedCgbMode2DisableAtDot450CancelsScheduledEvent() {
        Fixture fixture = pendingNormalSpeedCgbM2Event();

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.stat.preCpuTick();
        fixture.advanceTo(2, 0);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode2CapturesLycWriteAtSixClockBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.advanceTo(1, 444);

        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 2);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 448);
        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());

        fixture.stat.preCpuTick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void cgbMode2CaptureDoesNotRetriggerWhenIfWasAlreadyHigh() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 447);
        fixture.interrupts.setByte(0xff0f, 1 << LCDC.ordinal());

        fixture.tick();
        assertEquals(448, fixture.gpu.getTicksInLine());
        fixture.clearInterrupts();
        fixture.advanceTo(2, 0);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbFrameMode2CapturesAtDot454AndPublishesAtDot455() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();

        fixture.tick();
        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void rephasedNormalSpeedCgbFrameMode2PublishesAfterRollover() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();

        fixture.tick();
        fixture.tick();
        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(0, fixture.gpu.getLine());
        assertEquals(0, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertTrue(fixture.interrupts.isInterruptRequested());
        assertTrue(fixture.interrupts.isUnphasedPpuInterruptRequested());
    }

    @Test
    public void normalSpeedCgbLyc153FrameMode2PublishesAtDot454() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 153);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();

        fixture.tick();

        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void normalSpeedCgbFrameMode2DoesNotRetriggerAfterCapturedHighIf() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.interrupts.setByte(0xff0f, 1 << LCDC.ordinal());

        fixture.tick();
        fixture.clearInterrupts();
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbFrameMode2DisableCancelsCapturedEvent() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();
        fixture.tick();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void pendingNormalSpeedCgbFrameMode2SurvivesMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 453);
        fixture.clearInterrupts();
        fixture.tick();
        var gpuMemento = fixture.gpu.saveToMemento();
        var statMemento = fixture.stat.saveToMemento();
        var interruptMemento = fixture.interrupts.saveToMemento();

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        fixture.gpu.restoreFromMemento(gpuMemento);
        fixture.stat.restoreFromMemento(statMemento);
        fixture.interrupts.restoreFromMemento(interruptMemento);
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbMode2CapturesStatWriteAtTwoClockBoundary() {
        assertEquals(1 << LCDC.ordinal(), cgbMode2StatCaptureFlagAt(448));
        assertEquals(0, cgbMode2StatCaptureFlagAt(449));
    }

    @Test
    public void doubleSpeedCgbMode2DisableRetractsRequestThroughDot454() {
        Fixture fixture = publishedDoubleSpeedCgbM2Event();

        fixture.advanceTo(1, 454);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void doubleSpeedCgbMode2DisableCannotRetractRequestAfterDot454() {
        Fixture fixture = publishedDoubleSpeedCgbM2Event();

        fixture.advanceTo(1, 455);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void retractableDoubleSpeedCgbMode2EventSurvivesMementoRoundTrip() {
        Fixture fixture = publishedDoubleSpeedCgbM2Event();
        var gpuMemento = fixture.gpu.saveToMemento();
        var statMemento = fixture.stat.saveToMemento();
        var interruptMemento = fixture.interrupts.saveToMemento();

        fixture.advanceTo(1, 455);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        fixture.gpu.restoreFromMemento(gpuMemento);
        fixture.stat.restoreFromMemento(statMemento);
        fixture.interrupts.restoreFromMemento(interruptMemento);
        fixture.advanceTo(1, 454);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void normalSpeedCgbLycSourceSharesScheduledM2PublicationBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 2);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.tick();

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());

        fixture.stat.preCpuTick();
        // LYC is sampled as a blocker by the shared MSTAT event. Enabling the
        // source does not defer that event past the same-timestamp CPU callback.
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void pendingCgbM2EventSurvivesMementoRoundTrip() {
        Fixture fixture = pendingNormalSpeedCgbM2Event();
        var gpuMemento = fixture.gpu.saveToMemento();
        var statMemento = fixture.stat.saveToMemento();
        var interruptMemento = fixture.interrupts.saveToMemento();

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        fixture.clearInterrupts();

        fixture.gpu.restoreFromMemento(gpuMemento);
        fixture.stat.restoreFromMemento(statMemento);
        fixture.interrupts.restoreFromMemento(interruptMemento);
        assertEquals(448, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();
        fixture.tick();
        fixture.stat.preCpuTick();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
    }

    @Test
    public void normalSpeedCgbPublishesCapturedMode1AtDot455() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();

        fixture.advanceTo(143, 454);
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedCgbPublishesCapturedMode1AtDot454() {
        Fixture fixture = new Fixture(true, true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 453);
        fixture.clearInterrupts();

        fixture.tick();
        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void cgbMode1CaptureDoesNotRetriggerWhenIfWasAlreadyHigh() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.interrupts.setByte(0xff0f, 1 << LCDC.ordinal());

        fixture.tick();
        assertEquals(448, fixture.gpu.getTicksInLine());
        fixture.clearInterrupts();
        fixture.advanceTo(143, 455);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void pendingCgbMode1EventSurvivesMementoRoundTrip() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(143, 454);
        var gpuMemento = fixture.gpu.saveToMemento();
        var statMemento = fixture.stat.saveToMemento();
        var interruptMemento = fixture.interrupts.saveToMemento();

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        fixture.clearInterrupts();

        fixture.gpu.restoreFromMemento(gpuMemento);
        fixture.stat.restoreFromMemento(statMemento);
        fixture.interrupts.restoreFromMemento(interruptMemento);
        assertEquals(454, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    @Test
    public void lateModeWriteDoesNotSuppressTheNextFramesLine143Event() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(143, 453);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.clearInterrupts();

        fixture.advanceTo(143, 100);
        fixture.clearInterrupts();
        do {
            fixture.tick();
        } while (fixture.gpu.getLine() != 143 || !fixture.gpu.isMode0IntWindow());

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
    }

    private static Fixture pendingNormalSpeedCgbM2Event() {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.tick();
        assertEquals(448, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
        return fixture;
    }

    private static Fixture publishedDoubleSpeedCgbM2Event() {
        Fixture fixture = new Fixture(true, true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 452);
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        return fixture;
    }

    private static int cgbMode2StatCaptureFlagAt(int writeTick) {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x60);
        fixture.advanceTo(1, 447);
        fixture.clearInterrupts();
        fixture.tick();
        if (writeTick == 449) {
            fixture.stat.preCpuTick();
            fixture.tick();
        }

        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        while (fixture.gpu.getTicksInLine() < 450) {
            fixture.stat.preCpuTick();
            fixture.tick();
        }
        fixture.stat.preCpuTick();
        return fixture.lcdInterruptFlag();
    }

    private static Fixture activeCgbMode0(boolean doubleSpeed) {
        Fixture fixture = new Fixture(true, doubleSpeed);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 100);
        fixture.advanceToHBlank();
        return fixture;
    }

    private static int cgbMode1LycEnableFlagAt(int tick, boolean rephased) {
        Fixture fixture = new Fixture(true);
        if (rephased) {
            fixture.gpu.onSpeedSwitch();
        }
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(153, tick);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x50);

        return fixture.lcdInterruptFlag();
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
