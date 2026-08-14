package eu.rekawek.coffeegb.core.experimental.ppu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.Display;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.StatRegister;
import eu.rekawek.coffeegb.core.gpu.VRamTransfer;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import java.util.EnumSet;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.events.EventBus.NULL_EVENT_BUS;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.MODE_HBLANK;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.MODE_OAM;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.MODE_TRANSFER;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.MODE_VBLANK;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.STAT_LYC;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.STAT_M0;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.STAT_M1;
import static eu.rekawek.coffeegb.core.experimental.ppu.DmgStatControlPlane.STAT_M2;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Tests causal topology, not the production exception tree. */
public class DmgStatControlPlaneTest {

    private static final int PIXEL_END = 248;

    @Test
    public void ordinaryLineUsesIndependentModeAndBusGateLatches() {
        DmgStatControlPlane ppu = frame(1, STAT_M0);

        assertRasterState(ppu, MODE_OAM, true, true, false, false);
        advanceTo(ppu, 0, 75);
        assertRasterState(ppu, MODE_OAM, true, true, false, false);

        ppu.tick();
        assertEquals(76, ppu.dot());
        assertRasterState(ppu, MODE_OAM, true, false, true, false);

        advanceTo(ppu, 0, 79);
        assertRasterState(ppu, MODE_OAM, true, false, true, false);
        ppu.tick();
        assertEquals(80, ppu.dot());
        assertRasterState(ppu, MODE_TRANSFER, true, true, true, true);

        advanceTo(ppu, 0, PIXEL_END);
        assertRasterState(ppu, MODE_TRANSFER, true, true, true, true);
        assertFalse(ppu.mode0Source());
        ppu.tick();
        assertEquals(PIXEL_END + 1, ppu.dot());
        assertRasterState(ppu, MODE_HBLANK, false, false, false, false);
        assertFalse(ppu.mode0Source());
        assertFalse(ppu.lcdcIf());

        advanceTo(ppu, 0, PIXEL_END + 3);
        assertFalse(ppu.mode0Source());
        ppu.tick();
        assertEquals(PIXEL_END + 4, ppu.dot());
        assertTrue(ppu.mode0Source());
        assertTrue(ppu.lcdcIf());
    }

    @Test
    public void earlyLineEdgeAdvancesVisibleLyAndOamReadBeforeRollover() {
        DmgStatControlPlane ppu = frame(1, STAT_LYC);
        advanceTo(ppu, 0, 451);

        assertEquals(0, ppu.visibleLy());
        assertFalse(ppu.oamReadLocked());
        ppu.tick();

        assertEquals(452, ppu.dot());
        assertEquals(1, ppu.visibleLy());
        assertFalse(ppu.coincidence());
        assertTrue(ppu.oamReadLocked());
        assertFalse(ppu.oamWriteLocked());
        assertEquals(MODE_HBLANK, ppu.readableMode());
        assertTrue(ppu.mode2Source());

        advanceTo(ppu, 1, 0);
        assertEquals(1, ppu.visibleLy());
        assertEquals(MODE_OAM, ppu.readableMode());
        assertTrue(ppu.coincidence());
        assertFalse(ppu.coincidenceContribution());
        assertTrue(ppu.coincidenceEdgeHeld());
        assertTrue(ppu.lcdcIf());
    }

    @Test
    public void readableCoincidenceDropsBeforeItsRetiringStatSource() {
        DmgStatControlPlane ppu = frame(0, STAT_LYC);
        advanceTo(ppu, 0, 4);
        ppu.clearLcdcIf();
        advanceTo(ppu, 0, 451);
        assertTrue(ppu.coincidence());
        assertTrue(ppu.coincidenceContribution());

        ppu.tick();
        assertFalse(ppu.coincidence());
        assertTrue(ppu.coincidenceContribution());
        assertTrue(ppu.mode2Source());
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());

        // Replacing LYC with an already-active HBlank source is a level handoff, not an edge.
        ppu.writeStat(STAT_M0);
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());
    }

    @Test
    public void lineStartComparatorCaptureSettlesWithoutRetriggeringClearedIf() {
        DmgStatControlPlane ppu = frame(1, STAT_LYC);
        advanceTo(ppu, 1, 0);

        assertTrue(ppu.coincidence());
        assertFalse(ppu.coincidenceContribution());
        assertTrue(ppu.coincidenceEdgeHeld());
        assertTrue(ppu.lcdcIf());
        ppu.clearLcdcIf();

        advanceTo(ppu, 1, 3);
        assertTrue(ppu.coincidenceEdgeHeld());
        assertFalse(ppu.coincidenceContribution());
        assertFalse(ppu.lcdcIf());
        ppu.tick();

        assertEquals(4, ppu.dot());
        assertFalse(ppu.coincidenceEdgeHeld());
        assertTrue(ppu.coincidenceContribution());
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());
    }

    @Test
    public void m0ToLycCreatesAnEdgeUnlessTheM2PulseKeepsTheSharedLineHigh() {
        DmgStatControlPlane withoutM2 = frame(1, STAT_M0 | STAT_LYC);
        advanceTo(withoutM2, 0, PIXEL_END + 4);
        withoutM2.clearLcdcIf();
        advanceTo(withoutM2, 1, 0);

        assertFalse(withoutM2.mode0Source());
        assertTrue(withoutM2.coincidenceEdgeHeld());
        assertTrue(withoutM2.lcdcIf());

        DmgStatControlPlane withM2 = frame(1, STAT_M0 | STAT_M2 | STAT_LYC);
        advanceTo(withM2, 0, PIXEL_END + 4);
        withM2.clearLcdcIf();
        advanceTo(withM2, 1, 0);

        assertTrue(withM2.mode2Source());
        assertTrue(withM2.coincidenceEdgeHeld());
        assertFalse(withM2.lcdcIf());
    }

    @Test
    public void combinedStatLevelBlocksEdgesAcrossM0ToM2Handoff() {
        DmgStatControlPlane ppu = frame(255, STAT_M0 | STAT_M2);
        advanceTo(ppu, 0, PIXEL_END + 4);
        ppu.clearLcdcIf();

        advanceTo(ppu, 0, 451);
        assertTrue(ppu.statLine());
        ppu.tick();
        assertTrue(ppu.mode0Source());
        assertTrue(ppu.mode2Source());
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());

        advanceTo(ppu, 1, 0);
        assertFalse(ppu.mode0Source());
        assertTrue(ppu.mode2Source());
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());
    }

    @Test
    public void vblankSourceSurvivesReadableLine153Mode0AndHandsDirectlyToM2() {
        DmgStatControlPlane ppu = frame(255, STAT_M1 | STAT_M2);
        advanceTo(ppu, 144, 0);

        assertEquals(MODE_VBLANK, ppu.readableMode());
        assertTrue(ppu.mode1Source());
        assertTrue(ppu.vblankIf());
        assertTrue(ppu.statLine());
        ppu.clearLcdcIf();
        ppu.clearVblankIf();

        advanceTo(ppu, 153, 452);
        assertEquals(MODE_HBLANK, ppu.readableMode());
        assertTrue(ppu.mode1Source());
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());

        advanceTo(ppu, 0, 0);
        assertEquals(MODE_OAM, ppu.readableMode());
        assertFalse(ppu.mode1Source());
        assertTrue(ppu.mode2Source());
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());
    }

    @Test
    public void line144M2PulseHandsDirectlyToInternalVblankWithoutAnotherEdge() {
        DmgStatControlPlane ppu = frame(255, STAT_M1 | STAT_M2);
        advanceTo(ppu, 143, 452);

        assertTrue(ppu.mode2Source());
        assertFalse(ppu.mode1Source());
        assertTrue(ppu.statLine());
        ppu.clearLcdcIf();
        advanceTo(ppu, 144, 0);

        assertTrue(ppu.mode2Source());
        assertTrue(ppu.mode1Source());
        assertTrue(ppu.statLine());
        assertTrue(ppu.vblankIf());
        assertFalse(ppu.lcdcIf());
    }

    @Test
    public void mode2PulseIsExactlyFourTailDotsAndFourHeadDots() {
        DmgStatControlPlane ppu = frame(255, 0);
        advanceTo(ppu, 0, 451);
        assertFalse(ppu.mode2Source());

        for (int dot = 452; dot <= 455; dot++) {
            ppu.tick();
            assertEquals(dot, ppu.dot());
            assertTrue(ppu.mode2Source());
        }
        ppu.tick();
        assertEquals(1, ppu.line());
        assertEquals(0, ppu.dot());
        assertTrue(ppu.mode2Source());
        for (int dot = 1; dot <= 3; dot++) {
            ppu.tick();
            assertEquals(dot, ppu.dot());
            assertTrue(ppu.mode2Source());
        }
        ppu.tick();
        assertEquals(4, ppu.dot());
        assertFalse(ppu.mode2Source());
    }

    @Test
    public void line153HasIndependentVisibleLyAndComparatorCaptures() {
        DmgStatControlPlane ppu = frame(153, STAT_LYC);
        advanceTo(ppu, 153, 0);

        assertEquals(0, ppu.visibleLy());
        assertTrue(ppu.coincidence());
        assertTrue(ppu.coincidenceEdgeHeld());
        advanceTo(ppu, 153, 4);
        assertFalse(ppu.coincidence());
        assertFalse(ppu.coincidenceContribution());

        ppu.writeLyc(0);
        assertFalse(ppu.coincidence());
        advanceTo(ppu, 153, 8);
        assertTrue(ppu.coincidence());
        assertTrue(ppu.coincidenceEdgeHeld());
        assertEquals(0, ppu.visibleLy());
    }

    @Test
    public void lycZeroComparisonStaysHighAcrossTheFrameRolloverWithoutASecondEdge() {
        DmgStatControlPlane ppu = frame(0, STAT_LYC);
        advanceTo(ppu, 153, 12);
        assertTrue(ppu.coincidence());
        assertTrue(ppu.coincidenceContribution());
        ppu.clearLcdcIf();

        advanceTo(ppu, 153, 452);
        assertTrue(ppu.coincidence());
        assertTrue(ppu.coincidenceContribution());
        assertFalse(ppu.lcdcIf());
        advanceTo(ppu, 0, 0);

        assertTrue(ppu.coincidence());
        assertTrue(ppu.coincidenceContribution());
        assertTrue(ppu.statLine());
        assertFalse(ppu.lcdcIf());
    }

    @Test
    public void lcdOffFreezesCoincidenceAndEnableLineHasItsOwnModeAndLength() {
        DmgStatControlPlane ppu = DmgStatControlPlane.lcdOff(PIXEL_END, 1, true);

        ppu.tick();
        assertEquals(0, ppu.dot());
        assertEquals(0, ppu.visibleLy());
        assertEquals(MODE_HBLANK, ppu.readableMode());
        assertTrue(ppu.coincidence());
        assertRasterState(ppu, MODE_HBLANK, false, false, false, false);

        ppu.enableLcd();
        ppu.tick();
        assertEquals(0, ppu.dot());
        assertTrue(ppu.firstLine());
        assertFalse(ppu.coincidence());
        assertRasterState(ppu, MODE_HBLANK, false, false, false, false);

        advanceTo(ppu, 0, 78);
        assertRasterState(ppu, MODE_HBLANK, false, false, false, false);
        ppu.tick();
        assertEquals(79, ppu.dot());
        assertRasterState(ppu, MODE_TRANSFER, true, true, true, true);

        advanceTo(ppu, 0, 454);
        assertTrue(ppu.firstLine());
        ppu.tick();
        assertEquals(1, ppu.line());
        assertEquals(0, ppu.dot());
        assertFalse(ppu.firstLine());
        assertEquals(MODE_OAM, ppu.readableMode());
    }

    @Test
    public void dmgStatWriteGlitchFallsOutOfTransientEnableAndCapturedSources() {
        DmgStatControlPlane hblank = frame(255, 0);
        advanceTo(hblank, 0, PIXEL_END + 4);
        assertTrue(hblank.mode0Source());
        hblank.writeStat(0);
        assertTrue(hblank.lcdcIf());
        assertFalse(hblank.statLine());

        DmgStatControlPlane transfer = frame(255, 0);
        advanceTo(transfer, 0, 100);
        transfer.writeStat(0);
        assertFalse(transfer.lcdcIf());

        DmgStatControlPlane lyc = frame(0, 0);
        advanceTo(lyc, 0, 4);
        assertTrue(lyc.coincidenceContribution());
        lyc.writeStat(0);
        assertTrue(lyc.lcdcIf());

        DmgStatControlPlane frameStart = frame(255, 0);
        assertTrue(frameStart.mode2Source());
        frameStart.writeStat(0);
        assertFalse(frameStart.lcdcIf());

        DmgStatControlPlane ordinaryLineStart = frame(255, 0);
        advanceTo(ordinaryLineStart, 1, 0);
        assertTrue(ordinaryLineStart.mode2Source());
        ordinaryLineStart.writeStat(0);
        assertFalse(ordinaryLineStart.lcdcIf());

        DmgStatControlPlane hblankToOam = frame(255, STAT_M0);
        advanceTo(hblankToOam, 0, PIXEL_END + 4);
        hblankToOam.clearLcdcIf();
        advanceTo(hblankToOam, 1, 0);
        assertFalse(hblankToOam.statLine());
        hblankToOam.writeStat(STAT_M0);
        assertFalse(hblankToOam.lcdcIf());

        DmgStatControlPlane lineStartCoincidence = frame(1, 0);
        advanceTo(lineStartCoincidence, 1, 0);
        assertTrue(lineStartCoincidence.coincidence());
        assertTrue(lineStartCoincidence.coincidenceEdgeHeld());
        lineStartCoincidence.writeStat(0);
        assertTrue(lineStartCoincidence.lcdcIf());
    }

    @Test
    public void simpleWritesDriveComparatorAndSharedEdgeDirectly() {
        DmgStatControlPlane ppu = frame(1, STAT_LYC);
        advanceTo(ppu, 0, 12);
        assertFalse(ppu.coincidence());

        ppu.writeLyc(0);
        assertTrue(ppu.coincidence());
        assertTrue(ppu.coincidenceContribution());
        assertTrue(ppu.lcdcIf());
        ppu.clearLcdcIf();

        ppu.writeLyc(1);
        assertFalse(ppu.coincidence());
        assertFalse(ppu.statLine());
        ppu.writeLyc(0);
        assertTrue(ppu.lcdcIf());
    }

    @Test
    public void unresolvedProfilesAreExplicitFalsifiersNotHiddenBranches() {
        DmgStatControlPlane ppu = frame(0, 0);
        assertEquals(EnumSet.allOf(DmgStatControlPlane.Falsifier.class),
                ppu.unresolvedFalsifiers());
    }

    @Test
    public void steadyNoSpriteNoWindowRasterDifferentialMatchesProductionForAFrame() {
        ProductionFixture production = new ProductionFixture();
        DmgStatControlPlane topology = frame(1, 0);
        topology.tick(); // Gpu's power-on raster starts at line 0, dot 1.

        production.gpu.setByte(0xff45, 1);
        production.gpu.setByte(0x8000, 0x42);
        while (production.gpu.getLine() != 1 || production.gpu.getTicksInLine() != 0) {
            production.tick();
            topology.tick();
        }

        // Start after the constructor's partial power-on line. Every line in this interval was
        // entered through the same line-edge clock as the detached topology, including line 0.
        int observations = 154 * 456;
        for (int i = 0; i < observations; i++) {
            String at = "line " + production.gpu.getLine()
                    + " dot " + production.gpu.getTicksInLine();
            int stat = production.stat.getByte(0xff41);
            assertEquals(at, production.gpu.getLine(), topology.line());
            assertEquals(at, production.gpu.getTicksInLine(), topology.dot());
            assertEquals(at, production.gpu.getVisibleLy(), topology.visibleLy());
            assertEquals(at, stat & 0x03, topology.readableMode());
            // isMode0IntWindow is deliberately excluded: on this line it rises at dot 250,
            // while the hardware-derived physical source is E+4=252. It is a production
            // prediction/scheduler signal and is listed as an unresolved topology falsifier.
            assertEquals(at, production.gpu.isMode1IntWindow(), topology.mode1Source());
            assertEquals(at, production.gpu.isOamAvailableForCpu(false),
                    !topology.oamReadLocked());
            assertEquals(at, production.gpu.isOamAvailableForCpu(true),
                    !topology.oamWriteLocked());
            assertEquals(at, production.gpu.getByte(0x8000) == 0x42,
                    !topology.vramReadLocked());
            assertEquals(at, (stat & 0x04) != 0,
                    topology.coincidence());

            production.tick();
            topology.tick();
        }
    }

    private static DmgStatControlPlane frame(int lyc, int statEnable) {
        return DmgStatControlPlane.steadyFrame(PIXEL_END, lyc, statEnable);
    }

    private static void advanceTo(DmgStatControlPlane ppu, int line, int dot) {
        int guard = 154 * 456 + 456;
        while ((ppu.line() != line || ppu.dot() != dot) && guard-- > 0) {
            ppu.tick();
        }
        assertTrue("target raster position was not reached", guard > 0);
    }

    private static void assertRasterState(DmgStatControlPlane ppu, int mode,
            boolean oamReadLocked, boolean oamWriteLocked,
            boolean vramReadLocked, boolean vramWriteLocked) {
        assertEquals(mode, ppu.readableMode());
        assertEquals(oamReadLocked, ppu.oamReadLocked());
        assertEquals(oamWriteLocked, ppu.oamWriteLocked());
        assertEquals(vramReadLocked, ppu.vramReadLocked());
        assertEquals(vramWriteLocked, ppu.vramWriteLocked());
    }

    private static final class ProductionFixture {

        private final InterruptManager interrupts = new InterruptManager(false);
        private final StatRegister stat = new StatRegister(interrupts);
        private final Ram oam = new Ram(0xfe00, 0xa0);
        private final SpeedMode speedMode = new SpeedMode(false);
        private final Dma dma = new Dma(new Ram(0, 0x10000), oam, speedMode);
        private final Gpu gpu = new Gpu(
                new Display(false), dma, oam, new VRamTransfer(NULL_EVENT_BUS),
                stat, false, speedMode);

        private ProductionFixture() {
            stat.init(gpu);
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }
    }
}
