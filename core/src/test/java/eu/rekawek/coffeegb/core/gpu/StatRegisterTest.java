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
    public void ordinaryHaltWakeSamplesNextCgbVblankLyAcrossReadCycle() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(144, 450);

        assertEquals(144, fixture.readLy());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(144, fixture.readLy());
        fixture.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(145, fixture.readLy());

        fixture.tick();
        assertEquals(144, fixture.readLy());
        fixture.tick();
        assertEquals(145, fixture.readLy());
    }

    @Test
    public void lycHaltWakeKeepsTheCurrentCgbVblankLyAcrossReadCycle() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(144, 450);

        fixture.stat.captureCpuStatReadPhase(false, false, true);

        assertEquals(144, fixture.readLy());
    }

    @Test
    public void cgbLcdRestartCpuPhaseSkipsTheTransientLine153Latch() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(153, 0);

        assertEquals(153, fixture.gpu.getVisibleLy());
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void cgbLcdRestartLyDotZeroExceptionExpiresAfterTheFirstFrame() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(153, 0);
        assertEquals(0, fixture.readLy());

        fixture.advanceTo(0, 0);
        fixture.advanceTo(153, 0);
        assertEquals(153, fixture.readLy());

        fixture.advanceTo(153, 2);
        assertEquals(0, fixture.readLy());
    }

    @Test
    public void rephasedNormalSpeedCgbCpuReadSeesLyRippleAtLineTail() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(5, 455);

        assertEquals(6, fixture.gpu.getVisibleLy());
        assertEquals(4, fixture.readLy());
    }

    @Test
    public void rephasedDoubleSpeedCgbCpuReadSeesLyRippleBeforeLineTail() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(5, 451);

        assertEquals(5, fixture.gpu.getVisibleLy());
        assertEquals(4, fixture.readLy());
    }

    @Test
    public void cgbLcdRestartRealignsCpuVisibleLyLatch() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(0xff40, 0x11);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(5, 451);

        assertEquals(5, fixture.gpu.getVisibleLy());
        assertEquals(5, fixture.readLy());
    }

    @Test
    public void rephasedCgbCpuReadSeesLyResetRippleWithoutMovingComparatorLatch() {
        Fixture normalSpeed = new Fixture(true);
        normalSpeed.gpu.onSpeedSwitch();
        normalSpeed.advanceTo(153, 2);

        assertEquals(153, normalSpeed.gpu.getVisibleLy());
        assertEquals(0, normalSpeed.readLy());
        normalSpeed.tick();
        assertEquals(153, normalSpeed.readLy());

        Fixture doubleSpeed = new Fixture(true, true);
        doubleSpeed.gpu.onSpeedSwitch();
        doubleSpeed.advanceTo(153, 1);

        assertEquals(153, doubleSpeed.gpu.getVisibleLy());
        assertEquals(0, doubleSpeed.readLy());
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
    public void rephasedDoubleSpeedCpuReadSamplesMode3AtEndOfBusCycle() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 76);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());

        fixture.stat.tick();
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void lcdRestartRealignsDoubleSpeedMode3CpuReadPhase() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(0xff40, 0x11);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(1, 76);

        fixture.stat.captureCpuStatReadPhase(false, false, false);

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
    public void rephasedCgbLycTailReadsOutgoingStateAtVblankBoundary() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(143, 454);

        assertEquals(0xc0, fixture.stat.getByte(StatRegister.ADDRESS));
        fixture.tick();
        assertEquals(0xc4, fixture.stat.getByte(StatRegister.ADDRESS));
        fixture.tick();
        assertEquals(0xc1, fixture.stat.getByte(StatRegister.ADDRESS));
    }

    @Test
    public void rephasedCgbMode1RequestIsHiddenInFinalLine143IfBusSlot() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(143, 454);

        fixture.tick();

        assertTrue(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.tick();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
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
    public void cgbCpuBusUsesSettledPixelTransferModeAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbMode2HandlerReadRetainsTheSourceLatchAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void ordinaryHaltWakeRetainsCgbOamSearchAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void doubleSpeedMode2HandlerReadRetainsTheSourceLatchAtDot80() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 0);
        fixture.gpu.onDoubleSpeedMode2Dispatch();
        fixture.advanceTo(1, 80);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void mode2HandlerReadRetainsModeZeroAtTheLineEdge() {
        Fixture normal = new Fixture(true);
        normal.stat.setByte(StatRegister.ADDRESS, 0x20);
        normal.advanceTo(1, 454);
        normal.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), normal.readStatMode());

        Fixture doubleSpeed = new Fixture(true, true);
        doubleSpeed.gpu.onSpeedSwitch();
        doubleSpeed.stat.setByte(StatRegister.ADDRESS, 0x20);
        doubleSpeed.advanceTo(1, 0);
        doubleSpeed.gpu.onDoubleSpeedMode2Dispatch();
        doubleSpeed.advanceTo(2, 0);
        doubleSpeed.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), doubleSpeed.readStatMode());
    }

    @Test
    public void ordinaryHaltWakeStatHoldExpiresAfterOneScanline() {
        Fixture recent = new Fixture(true);
        recent.stat.setByte(StatRegister.ADDRESS, 0x20);
        recent.advanceTo(1, 3);
        recent.stat.captureCpuStatReadPhase(false, false, true);
        recent.advanceTo(1, 454);
        recent.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.HBlank.ordinal(), recent.readStatMode());

        Fixture stale = new Fixture(true);
        stale.stat.setByte(StatRegister.ADDRESS, 0x20);
        stale.advanceTo(1, 3);
        stale.stat.captureCpuStatReadPhase(false, false, true);
        stale.advanceTo(2, 454);
        stale.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.OamSearch.ordinal(), stale.readStatMode());
    }

    @Test
    public void cgbFrameStartCpuBusRetainsOamSearchModeAtDot78() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 0);
        fixture.advanceTo(0, 78);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void synchronousHaltEntryRetainsCgbHblankOnTheFinalDot() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 454);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(true, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void asynchronousHaltEntryExposesDmgOamSearchAtLineRollover() {
        Fixture fixture = new Fixture(false);
        fixture.advanceTo(1, 455);

        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, true, false);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void ordinaryHaltWakeExposesDmgOamSearchAcrossLineEdgeReadCycle() {
        Fixture fixture = new Fixture(false);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        fixture.advanceTo(1, 448);
        fixture.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        fixture.advanceTo(1, 452);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, true, true);
        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbMode0SourceCpuReadSamplesTheUpcomingMode2Latch() {
        for (boolean doubleSpeed : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(true, doubleSpeed);
            fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
            fixture.gpu.setByte(GpuRegister.SCX.getAddress(), doubleSpeed ? 0 : 3);
            fixture.advanceTo(1, doubleSpeed ? 452 : 450);

            assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
            fixture.stat.captureCpuStatReadPhase(false, false, false);
            assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        }

        Fixture earlierFineScrollPhase = new Fixture(true);
        earlierFineScrollPhase.stat.setByte(StatRegister.ADDRESS, 0x08);
        earlierFineScrollPhase.gpu.setByte(GpuRegister.SCX.getAddress(), 2);
        earlierFineScrollPhase.advanceTo(1, 450);
        earlierFineScrollPhase.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), earlierFineScrollPhase.readStatMode());

        for (boolean synchronous : new boolean[] {false, true}) {
            Fixture haltEntry = new Fixture(true);
            haltEntry.stat.setByte(StatRegister.ADDRESS, 0x08);
            haltEntry.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
            haltEntry.advanceTo(1, 450);

            haltEntry.stat.captureCpuStatReadPhase(synchronous, !synchronous, false);

            assertEquals(Mode.HBlank.ordinal(), haltEntry.readStatMode());
        }

        Fixture ordinaryHaltWake = new Fixture(true);
        ordinaryHaltWake.stat.setByte(StatRegister.ADDRESS, 0x08);
        ordinaryHaltWake.gpu.setByte(GpuRegister.SCX.getAddress(), 3);
        ordinaryHaltWake.advanceTo(1, 450);
        ordinaryHaltWake.stat.captureCpuStatReadPhase(false, false, true);
        assertEquals(Mode.HBlank.ordinal(), ordinaryHaltWake.readStatMode());
    }

    @Test
    public void cgbOamDmaObjectLineCpuReadRetainsMode3AtTheMode0Edge() {
        Fixture fixture = new Fixture(true);
        for (int i = 0; i < 9; i++) {
            fixture.oam.setByte(0xfe00 + i * 4, 16);
            fixture.oam.setByte(0xfe01 + i * 4, 160);
        }
        fixture.advanceTo(1, 0);
        while (fixture.gpu.getMode() != Mode.HBlank
                || !fixture.gpu.isMode0IntWindow()) {
            fixture.tick();
        }

        assertEquals(Mode.HBlank.ordinal(), fixture.gpu.getVisibleStatMode());
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
        fixture.dma.setByte(0xff46, 0x12);
        for (int i = 0; i < 8; i++) {
            fixture.dma.tick();
        }
        assertTrue(fixture.dma.ownsOamForPpu());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
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
    public void cgbMode0SourceCpuReadSamplesTheEventAlignedHblankLatch() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 250);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        Fixture mode2Source = new Fixture(true);
        mode2Source.stat.setByte(StatRegister.ADDRESS, 0x20);
        mode2Source.advanceTo(1, 250);
        mode2Source.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.PixelTransfer.ordinal(), mode2Source.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedMode0SourceCpuReadLooksAheadToTheEventLatch() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 248);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbLine153CpuReadSamplesTheImminentCoincidenceRelease() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 153);
        fixture.advanceTo(153, 6);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);

        fixture.tick();
        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
    }

    @Test
    public void lcdRestartedCgbLineZeroRetainsBootPhaseMode3TailUntilRephased() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x11);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(1, 0);
        fixture.advanceTo(0, 254);

        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());

        Fixture interruptSourceSelected = new Fixture(true);
        interruptSourceSelected.gpu.setByte(0xff40, 0x11);
        interruptSourceSelected.gpu.setByte(0xff40, 0x91);
        interruptSourceSelected.stat.setByte(StatRegister.ADDRESS, 0x20);
        interruptSourceSelected.advanceTo(1, 0);
        interruptSourceSelected.advanceTo(0, 254);
        assertEquals(Mode.HBlank.ordinal(), interruptSourceSelected.readStatMode());

        Fixture rephased = new Fixture(true);
        rephased.gpu.setByte(0xff40, 0x11);
        rephased.gpu.setByte(0xff40, 0x91);
        rephased.gpu.onSpeedSwitch();
        rephased.advanceTo(1, 0);
        rephased.advanceTo(0, 254);
        assertEquals(Mode.HBlank.ordinal(), rephased.readStatMode());
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
        fixture.stat.captureCpuStatReadPhase(false, false, false);
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
    }

    @Test
    public void cgbDoubleSpeedObjectTailDoesNotUseACpuModeOverride() {
        Fixture fixture = new Fixture(true, true);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 16);
        fixture.gpu.setByte(GpuRegister.SCX.getAddress(), 0);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 260);

        assertTrue(fixture.gpu.hasObjectsOnLine());
        assertEquals(Mode.HBlank, fixture.gpu.getMode());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
        assertEquals(-1, fixture.gpu.getCpuReadStatModeOverride(false, false, false));

        fixture.tick();
        assertEquals(-1, fixture.gpu.getCpuReadStatModeOverride(false, false, false));
        assertEquals(Mode.HBlank.ordinal(), fixture.readStatMode());
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
    public void rephasedCpuReadSeesMode3AtDot74Boundary() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.advanceTo(1, 73);

        assertEquals(Mode.OamSearch.ordinal(), fixture.readStatMode());
        fixture.tick();
        assertEquals(74, fixture.gpu.getTicksInLine());
        assertEquals(Mode.PixelTransfer.ordinal(), fixture.readStatMode());
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
    public void cgbLcdRestartGridReleasesLaterCoincidenceAtStoredDot452() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.setByte(0xff40, 0x00);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0);
        fixture.gpu.setByte(0xff40, 0x91);
        fixture.advanceTo(1, 0);
        fixture.advanceTo(0, 451);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(452, fixture.gpu.getTicksInLine());
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
    public void doubleSpeedMode0PreviewIsReadOnlyAndExpiresAtThePeripheralTick() {
        Fixture fixture = new Fixture(true, true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x48);
        fixture.advanceTo(1, 248);
        assertEquals(250, fixture.gpu.getMode0InterruptTick());
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void mode0CpuLookaheadRequiresANewEligibleInterruptEdge() {
        Fixture disabled = new Fixture(true);
        disabled.advanceTo(1, 249);
        assertEquals(250, disabled.gpu.getMode0InterruptTick());
        assertFalse(disabled.stat.isMode0InterruptEdgeNextTick());

        Fixture alreadyAsserted = new Fixture(true);
        alreadyAsserted.stat.setByte(StatRegister.ADDRESS, 0x08);
        alreadyAsserted.advanceTo(1, 249);
        alreadyAsserted.interrupts.requestInterrupt(LCDC);
        assertFalse(alreadyAsserted.stat.isMode0InterruptEdgeNextTick());

        Fixture eligible = new Fixture(true);
        eligible.stat.setByte(StatRegister.ADDRESS, 0x08);
        eligible.advanceTo(1, 249);
        eligible.clearInterrupts();
        assertTrue(eligible.stat.isMode0InterruptEdgeNextTick());
    }

    @Test
    public void dmgIfReadPreviewsTerminalWindowMode0OnlyAtItsFinalBusPhase() {
        Fixture fixture = new Fixture(false);
        fixture.oam.setByte(0xfe00, 0x10);
        fixture.oam.setByte(0xfe01, 0xa7);
        fixture.gpu.setByte(0xff40, 0xb7);
        fixture.gpu.setByte(GpuRegister.WX.getAddress(), 0xa6);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 252);
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();
        assertEquals(0, fixture.lcdInterruptFlag());

        fixture.advanceTo(1, 256);
        assertEquals(258, fixture.gpu.getMode0InterruptTick());
        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void rephasedNormalSpeedMode2IsReadableAtTheEarlyCpuBusPhase() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(1, 20);
        fixture.clearInterrupts();
        fixture.advanceTo(1, 450);

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void rephasedNormalSpeedFrameMode2IsReadableBeforeItsLatchSettles() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 452);
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void rephasedMode2TailReadAlsoSamplesTheUpcomingVblankBit() {
        Fixture fixture = new Fixture(true);
        fixture.gpu.onSpeedSwitch();
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(143, 452);
        fixture.clearInterrupts();

        fixture.stat.captureCpuInterruptReadPhase();

        assertEquals(1 << VBlank.ordinal(),
                fixture.interrupts.getByte(0xff0f) & (1 << VBlank.ordinal()));
        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        assertFalse(fixture.interrupts.isInterruptRequested());
        fixture.stat.tick();
        assertEquals(0,
                fixture.interrupts.getByte(0xff0f) & (1 << VBlank.ordinal()));
    }

    @Test
    public void normalSpeedMode0AcknowledgeWinsOnlyInsideItsCaptureWindow() {
        Fixture captured = new Fixture(true);
        captured.stat.setByte(StatRegister.ADDRESS, 0x08);
        captured.advanceTo(1, 246);
        captured.interrupts.requestInterrupt(LCDC);
        captured.interrupts.clearInterrupt(LCDC);
        captured.tick();
        captured.advanceTo(1, 250);
        assertEquals(0, captured.lcdInterruptFlag());

        Fixture older = new Fixture(true);
        older.stat.setByte(StatRegister.ADDRESS, 0x08);
        older.advanceTo(1, 245);
        older.interrupts.requestInterrupt(LCDC);
        older.interrupts.clearInterrupt(LCDC);
        older.tick();
        older.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), older.lcdInterruptFlag());

        Fixture dmgCaptured = new Fixture(false);
        dmgCaptured.stat.setByte(StatRegister.ADDRESS, 0x08);
        dmgCaptured.advanceTo(1, 244);
        dmgCaptured.interrupts.requestInterrupt(LCDC);
        dmgCaptured.interrupts.clearInterrupt(LCDC);
        dmgCaptured.tick();
        dmgCaptured.advanceTo(1, 250);
        assertEquals(0, dmgCaptured.lcdInterruptFlag());

        Fixture dmgOlder = new Fixture(false);
        dmgOlder.stat.setByte(StatRegister.ADDRESS, 0x08);
        dmgOlder.advanceTo(1, 243);
        dmgOlder.interrupts.requestInterrupt(LCDC);
        dmgOlder.interrupts.clearInterrupt(LCDC);
        dmgOlder.tick();
        dmgOlder.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), dmgOlder.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedMode0SetWinsOnlyTheFollowingAcknowledgeSlot() {
        Fixture captured = new Fixture(true, true);
        captured.stat.setByte(StatRegister.ADDRESS, 0x08);
        captured.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), captured.lcdInterruptFlag());
        captured.interrupts.clearInterrupt(LCDC);
        captured.tick();
        assertEquals(1 << LCDC.ordinal(), captured.lcdInterruptFlag());

        Fixture later = new Fixture(true, true);
        later.stat.setByte(StatRegister.ADDRESS, 0x08);
        later.advanceTo(1, 252);
        later.interrupts.clearInterrupt(LCDC);
        later.tick();
        assertEquals(0, later.lcdInterruptFlag());
    }

    @Test
    public void cgbMode0SetWinsOnlyTheCoincidentInterruptFlagWrite() {
        Fixture coincident = new Fixture(true);
        coincident.stat.setByte(StatRegister.ADDRESS, 0x08);
        coincident.advanceTo(1, 250);
        coincident.clearInterrupts();
        coincident.tick();
        assertEquals(1 << LCDC.ordinal(), coincident.lcdInterruptFlag());

        Fixture later = new Fixture(true);
        later.stat.setByte(StatRegister.ADDRESS, 0x08);
        later.advanceTo(1, 251);
        later.clearInterrupts();
        later.tick();
        assertEquals(0, later.lcdInterruptFlag());
    }

    @Test
    public void dmgMode0ClearWinsTheSamePeripheralSlot() {
        Fixture coincident = new Fixture(false);
        coincident.stat.setByte(StatRegister.ADDRESS, 0x08);
        coincident.advanceTo(1, 249);
        coincident.clearInterrupts();
        coincident.tick();
        assertEquals(0, coincident.lcdInterruptFlag());

        Fixture ordinary = new Fixture(false);
        ordinary.stat.setByte(StatRegister.ADDRESS, 0x08);
        ordinary.advanceTo(1, 250);
        assertEquals(1 << LCDC.ordinal(), ordinary.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedMode0SetWinsTwoAndThreeDotFlagWriteSlots() {
        for (int clearTick : new int[] {251, 252}) {
            Fixture captured = new Fixture(true, true);
            captured.stat.setByte(StatRegister.ADDRESS, 0x08);
            captured.advanceTo(1, clearTick);
            captured.clearInterrupts();
            captured.tick();
            assertEquals(1 << LCDC.ordinal(), captured.lcdInterruptFlag());
        }

        Fixture later = new Fixture(true, true);
        later.stat.setByte(StatRegister.ADDRESS, 0x08);
        later.advanceTo(1, 253);
        later.clearInterrupts();
        later.tick();
        assertEquals(0, later.lcdInterruptFlag());
    }

    @Test
    public void capturedMode0IfReadMasksOnlyTheBusValue() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);
        fixture.advanceTo(1, 245);
        fixture.interrupts.requestInterrupt(LCDC);
        fixture.interrupts.clearInterrupt(LCDC);
        fixture.tick();
        fixture.advanceTo(1, 249);
        fixture.stat.captureCpuInterruptReadPhase(2, false, false);

        fixture.tick();

        assertTrue(fixture.interrupts.isInterruptFlagSet(LCDC));
        assertEquals(0, fixture.lcdInterruptFlag());
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());

        Fixture older = new Fixture(true);
        older.stat.setByte(StatRegister.ADDRESS, 0x08);
        older.advanceTo(1, 249);
        older.clearInterrupts();
        older.stat.captureCpuInterruptReadPhase(2, false, false);
        older.tick();
        assertEquals(1 << LCDC.ordinal(), older.lcdInterruptFlag());
    }

    @Test
    public void mode0RequestCanRetainCpuPhaseOrWaitForInstruction() {
        Fixture phased = new Fixture(true);
        phased.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        phased.interrupts.enableInterrupts(false);
        phased.stat.setByte(StatRegister.ADDRESS, 0x08);
        phased.advanceTo(1, 249);
        phased.clearInterrupts();
        phased.stat.captureCpuInterruptReadPhase(0, true, false);
        phased.tick();
        assertTrue(phased.interrupts.isInterruptRequested());
        assertFalse(phased.interrupts.isUnphasedPpuInterruptRequested());

        Fixture instruction = new Fixture(true);
        instruction.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        instruction.interrupts.enableInterrupts(false);
        instruction.stat.setByte(StatRegister.ADDRESS, 0x08);
        instruction.advanceTo(1, 249);
        instruction.clearInterrupts();
        instruction.stat.captureCpuInterruptReadPhase(0, false, true);
        instruction.tick();
        assertTrue(instruction.interrupts.isInterruptFlagSet(LCDC));
        assertFalse(instruction.interrupts.isInterruptRequested());
        instruction.interrupts.onInstructionFinished();
        assertTrue(instruction.interrupts.isInterruptRequested());
    }

    @Test
    public void rephasedDoubleSpeedCgbReleasesCoincidenceInFinalVblankBusSlot() {
        Fixture fixture = new Fixture(true, true);
        fixture.gpu.onSpeedSwitch();
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        fixture.advanceTo(143, 452);

        assertEquals(0x04, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);
        fixture.tick();
        assertEquals(0, fixture.stat.getByte(StatRegister.ADDRESS) & 0x04);

        Fixture unrephased = new Fixture(true, true);
        unrephased.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        unrephased.advanceTo(143, 453);
        assertEquals(0x04, unrephased.stat.getByte(StatRegister.ADDRESS) & 0x04);
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
    public void cgbMode0EnableDuringOamOnlyArmsTheNextHblankEvent() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(1, 40);
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(0, fixture.lcdInterruptFlag());
        fixture.advanceToHBlank();
        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
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
    public void cgbVblankLycToMode1HandoffUsesTheEarlyComparatorRelease() {
        Fixture beforeRelease = new Fixture(true);
        beforeRelease.gpu.setByte(GpuRegister.LYC.getAddress(), 144);
        beforeRelease.stat.setByte(StatRegister.ADDRESS, 0x40);
        beforeRelease.advanceTo(144, 446);
        beforeRelease.clearInterrupts();

        beforeRelease.stat.setByte(StatRegister.ADDRESS, 0x10);

        assertEquals(0, beforeRelease.lcdInterruptFlag());

        Fixture atRelease = new Fixture(true);
        atRelease.gpu.setByte(GpuRegister.LYC.getAddress(), 144);
        atRelease.stat.setByte(StatRegister.ADDRESS, 0x40);
        atRelease.advanceTo(144, 450);
        atRelease.clearInterrupts();

        atRelease.stat.setByte(StatRegister.ADDRESS, 0x10);

        assertEquals(1 << LCDC.ordinal(), atRelease.lcdInterruptFlag());
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
    public void dmgStatWriteGlitchSamplesLineStartCoincidence() {
        Fixture matching = new Fixture(false);
        matching.gpu.setByte(GpuRegister.LYC.getAddress(), 1);
        matching.advanceTo(1, 0);
        matching.clearInterrupts();

        matching.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(1 << LCDC.ordinal(), matching.lcdInterruptFlag());

        Fixture different = new Fixture(false);
        different.gpu.setByte(GpuRegister.LYC.getAddress(), 2);
        different.advanceTo(1, 0);
        different.clearInterrupts();
        different.stat.setByte(StatRegister.ADDRESS, 0x00);
        assertEquals(0, different.lcdInterruptFlag());
    }

    @Test
    public void dmgLineZeroStatGlitchCannotArmTheRetiredMode2Event() {
        Fixture fixture = new Fixture(false);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), 0xff);
        fixture.advanceTo(153, 455);
        fixture.tick();
        assertEquals(0, fixture.gpu.getLine());
        assertEquals(0, fixture.gpu.getTicksInLine());
        fixture.clearInterrupts();

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);

        assertEquals(0, fixture.lcdInterruptFlag());
    }

    @Test
    public void dmgOutgoingLycBlocksMode1StatWriteInTheFinalVblankSlot() {
        Fixture captured = dmgLycMatchAt(148, 451);
        captured.tick();
        assertEquals(452, captured.gpu.getTicksInLine());

        captured.stat.setByte(StatRegister.ADDRESS, 0x50);

        assertEquals(0, captured.lcdInterruptFlag());

        Fixture afterHandoff = dmgLycMatchAt(148, 455);
        afterHandoff.tick();
        assertEquals(149, afterHandoff.gpu.getLine());

        afterHandoff.stat.setByte(StatRegister.ADDRESS, 0x50);

        assertEquals(1 << LCDC.ordinal(), afterHandoff.lcdInterruptFlag());
    }

    @Test
    public void dmgOutgoingLycBlocksMode0StatWriteInTheFinalVisibleLineSlot() {
        Fixture captured = dmgLycMatchAt(143, 451);
        captured.tick();
        assertEquals(452, captured.gpu.getTicksInLine());

        captured.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(0, captured.lcdInterruptFlag());

        Fixture afterHandoff = dmgLycMatchAt(143, 455);
        afterHandoff.tick();
        assertEquals(144, afterHandoff.gpu.getLine());

        afterHandoff.stat.setByte(StatRegister.ADDRESS, 0x08);

        assertEquals(1 << LCDC.ordinal(), afterHandoff.lcdInterruptFlag());
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
    public void firstPostFrameLyc0M2HandoffPublishesBeforeCpuIo() {
        Fixture fixture = pendingFrameLyc0M2Event(0x60, 0, 1);

        fixture.stat.publishFrameLyc0Mode2HandoffBeforeCpu();

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
        assertFalse(fixture.interrupts.isInterruptRequested());
        assertFalse(fixture.interrupts.isInterruptRequestedForHalt());
    }

    @Test
    public void ordinaryM2HandoffsDoNotPublishEarlyBeforeCpuIo() {
        Fixture m2Only = pendingFrameLyc0M2Event(0x20, 0, 1);
        m2Only.stat.publishFrameLyc0Mode2HandoffBeforeCpu();
        assertEquals(0, m2Only.lcdInterruptFlag());

        Fixture ordinaryLine = pendingFrameLyc0M2Event(0x60, 4, 5);
        ordinaryLine.stat.publishFrameLyc0Mode2HandoffBeforeCpu();
        assertEquals(0, ordinaryLine.lcdInterruptFlag());
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
    public void frameMode2DoesNotRetriggerInsideTheInterruptAcknowledgeWindow() {
        Fixture cgb = new Fixture(true);
        cgb.stat.setByte(StatRegister.ADDRESS, 0x20);
        cgb.advanceTo(153, 446);
        cgb.interrupts.clearInterrupt(LCDC);
        cgb.tick();
        cgb.advanceTo(0, 0);
        assertEquals(0, cgb.lcdInterruptFlag());

        Fixture doubleSpeed = new Fixture(true, true);
        doubleSpeed.gpu.onSpeedSwitch();
        doubleSpeed.stat.setByte(StatRegister.ADDRESS, 0x20);
        doubleSpeed.advanceTo(153, 452);
        doubleSpeed.interrupts.clearInterrupt(LCDC);
        doubleSpeed.tick();
        doubleSpeed.advanceTo(0, 0);
        assertEquals(0, doubleSpeed.lcdInterruptFlag());

        Fixture dmg = new Fixture();
        dmg.stat.setByte(StatRegister.ADDRESS, 0x20);
        dmg.advanceTo(153, 448);
        dmg.interrupts.clearInterrupt(LCDC);
        dmg.tick();
        dmg.advanceTo(0, 0);
        assertEquals(0, dmg.lcdInterruptFlag());
    }

    @Test
    public void frameMode2RetriggersAfterTheAcknowledgeWindow() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x20);
        fixture.advanceTo(153, 442);
        fixture.interrupts.clearInterrupt(LCDC);
        fixture.tick();
        fixture.advanceTo(0, 0);

        assertEquals(1 << LCDC.ordinal(), fixture.lcdInterruptFlag());
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
    public void recentVblankAcknowledgeConsumesNormalSpeedFrameEdge() {
        for (boolean gbc : new boolean[] {false, true}) {
            Fixture fixture = new Fixture(gbc);
            fixture.advanceTo(143, 449);
            fixture.interrupts.requestInterrupt(VBlank);
            fixture.interrupts.clearInterrupt(VBlank);

            fixture.advanceTo(144, 0);

            assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        }
    }

    @Test
    public void olderVblankAcknowledgeDoesNotConsumeNormalSpeedFrameEdge() {
        Fixture fixture = new Fixture(true);
        fixture.advanceTo(143, 447);
        fixture.interrupts.requestInterrupt(VBlank);
        fixture.interrupts.clearInterrupt(VBlank);

        fixture.advanceTo(144, 0);

        assertTrue(fixture.interrupts.isInterruptFlagSet(VBlank));
    }

    @Test
    public void doubleSpeedVblankTailIsASingleCapturedOccurrence() {
        Fixture fixture = new Fixture(true, true);
        fixture.advanceTo(143, 452);
        fixture.interrupts.requestInterrupt(VBlank);
        fixture.interrupts.clearInterrupt(VBlank);

        fixture.advanceTo(143, 454);
        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
        fixture.advanceTo(144, 0);
        assertFalse(fixture.interrupts.isInterruptFlagSet(VBlank));
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
    public void normalSpeedCgbMode1CanBeDisabledInItsCaptureDot() {
        Fixture fixture = new Fixture(true);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x10);
        fixture.advanceTo(143, 447);
        fixture.clearInterrupts();
        fixture.advanceTo(143, 454);

        fixture.stat.setByte(StatRegister.ADDRESS, 0x00);
        fixture.tick();

        assertEquals(455, fixture.gpu.getTicksInLine());
        assertEquals(0, fixture.lcdInterruptFlag());
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
    public void dmgLyc143Mode1CaptureStraddlesTheLateInterruptAcknowledge() {
        Fixture acknowledgeBefore = new Fixture(false);
        acknowledgeBefore.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeBefore.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeBefore.advanceTo(143, 444);
        acknowledgeBefore.interrupts.requestInterrupt(LCDC);
        acknowledgeBefore.interrupts.clearInterrupt(LCDC);
        acknowledgeBefore.advanceTo(143, 448);

        assertEquals(1 << LCDC.ordinal(), acknowledgeBefore.lcdInterruptFlag());

        Fixture acknowledgeAfter = new Fixture(false);
        acknowledgeAfter.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeAfter.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeAfter.advanceTo(143, 448);
        acknowledgeAfter.interrupts.clearInterrupt(LCDC);
        acknowledgeAfter.advanceTo(144, 0);

        assertEquals(0, acknowledgeAfter.lcdInterruptFlag());

        Fixture explicitClear = new Fixture(false);
        explicitClear.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        explicitClear.stat.setByte(StatRegister.ADDRESS, 0x50);
        explicitClear.advanceTo(143, 447);
        explicitClear.clearInterrupts();
        explicitClear.tick();

        assertEquals(0, explicitClear.lcdInterruptFlag());
    }

    @Test
    public void doubleSpeedCgbMode1RecapturesIfAtDot452() {
        Fixture acknowledgeBefore = new Fixture(true, true);
        acknowledgeBefore.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeBefore.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeBefore.advanceTo(143, 451);
        acknowledgeBefore.interrupts.clearInterrupt(LCDC);
        acknowledgeBefore.advanceTo(143, 454);

        assertEquals(1 << LCDC.ordinal(), acknowledgeBefore.lcdInterruptFlag());

        Fixture acknowledgeAfter = new Fixture(true, true);
        acknowledgeAfter.gpu.setByte(GpuRegister.LYC.getAddress(), 143);
        acknowledgeAfter.stat.setByte(StatRegister.ADDRESS, 0x50);
        acknowledgeAfter.advanceTo(143, 452);
        acknowledgeAfter.interrupts.clearInterrupt(LCDC);
        acknowledgeAfter.advanceTo(143, 454);

        assertEquals(0, acknowledgeAfter.lcdInterruptFlag());
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

    private static Fixture pendingFrameLyc0M2Event(int stat, int lyc, int line) {
        Fixture fixture = new Fixture(true);
        fixture.interrupts.setByte(0xffff, 1 << LCDC.ordinal());
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), lyc);
        fixture.stat.setByte(StatRegister.ADDRESS, stat);
        fixture.advanceTo(line, 447);
        fixture.clearInterrupts();
        fixture.tick();
        fixture.tick();
        fixture.tick();
        assertEquals(450, fixture.gpu.getTicksInLine());
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

    private static Fixture dmgLycMatchAt(int line, int tick) {
        Fixture fixture = new Fixture(false);
        fixture.gpu.setByte(GpuRegister.LYC.getAddress(), line);
        fixture.stat.setByte(StatRegister.ADDRESS, 0x40);
        fixture.advanceTo(line, tick);
        fixture.clearInterrupts();
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

        private final Dma dma;

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
            interrupts.setByteFromCpu(0xff0f, 0);
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
