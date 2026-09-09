package eu.rekawek.coffeegb.core;

import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.memory.Hdma;
import org.junit.Test;

import static eu.rekawek.coffeegb.core.performance.PerformanceStateAssertions.assertStateEquals;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;
import static org.junit.Assert.*;

/** Positive transfer coverage and canonical whole-machine equivalence across arbitrary owner tails. */
public class PerformanceOwnedDmaWorkloadTest {
    @Test
    public void normalAndDoubleSpeedOwnedDmaPreserveEveryTailAndScalarDestinationCommit() throws Exception {
        for (Profile profile : new Profile[]{Profile.CGB, Profile.CGB_X2, Profile.CGB0, Profile.CGB0_X2}) {
            for (int transfer = 0; transfer < 3; transfer++) {
                try (Gameboy scalar = session(Scenario.CPU, profile);
                     Gameboy direct = session(Scenario.CPU, profile)) {
                    scalar.setPerformanceBatchingEnabled(false);
                    direct.setPerformanceBatchingEnabled(false);
                    scalar.runTicks(210_000);
                    direct.runTicks(210_000);
                    if (transfer == 2) {
                        scalar.getAddressSpace().setByte(0xff40, 0x11);
                        direct.getAddressSpace().setByte(0xff40, 0x11);
                        scalar.runTicks(8);
                        direct.runTicks(8);
                    } else {
                        int guard = 0;
                        while (!(direct.getGpu().getLine() < 144
                                && direct.getGpu().getMode() == Mode.HBlank
                                && direct.getGpu().getTicksInLine() == 300)
                                && guard++ < 70_224) {
                            scalar.runTicks(1);
                            direct.runTicks(1);
                        }
                        assertTrue("HBlank setup", guard < 70_224);
                    }
                    for (Gameboy gameboy : new Gameboy[]{scalar, direct}) {
                        var bus = gameboy.getAddressSpace();
                        for (int index = 0; index < 64; index++) bus.setByte(0xc800 + index, index + 0x31);
                        gameboy.getHdma().onLcdSwitch(gameboy.getGpu().isLcdEnabled());
                        bus.setByte(0xff51, 0xc8);
                        bus.setByte(0xff52, 0);
                        bus.setByte(0xff53, 0);
                        bus.setByte(0xff54, 0);
                        bus.setByte(0xff55, transfer == 0 ? 0x82 : 2);
                    }
                    int guard = 0;
                    while (!ownedStart(direct) && guard++ < 100) {
                        scalar.runTicks(1);
                        direct.runTicks(1);
                    }
                    String label = profile + "/transfer " + transfer;
                    assertTrue(label + " did not reach owned source interior", guard < 100);
                    assertStateEquals(label + " setup", scalar.captureStateWithoutTimeSource(),
                            direct.captureStateWithoutTimeSource());
                    var scalarCheckpoint = scalar.captureStateWithoutTimeSource();
                    var directCheckpoint = direct.captureStateWithoutTimeSource();
                    direct.setPerformanceBatchingEnabled(true);
                    long covered = 0;
                    for (int tail = 1; tail <= 32; tail++) {
                        scalar.restoreStateSilently(scalarCheckpoint);
                        direct.restoreStateSilently(directCheckpoint);
                        direct.resetPerformanceBulkCounters();
                        assertEquals(scalar.runTicks(tail), direct.runTicks(tail));
                        covered += direct.getPerformanceHdmaOwnedTicks();
                        assertStateEquals(label + " tail " + tail, scalar.captureStateWithoutTimeSource(),
                                direct.captureStateWithoutTimeSource());
                        if (tail < 32) assertEquals(0, direct.getGpu().readSelectedVideoRamForCore(0x8000));
                    }
                    assertTrue(label + " lost owned transfer coverage: " + covered, covered > 100);
                    assertEquals(0x31, direct.getGpu().readSelectedVideoRamForCore(0x8000));
                    assertEquals(scalar.runTicks(1400), direct.runTicks(1400));
                    assertStateEquals(label + " resumed after commit", scalar.captureStateWithoutTimeSource(),
                            direct.captureStateWithoutTimeSource());
                }
            }
        }
    }

    private static boolean ownedStart(Gameboy gameboy) {
        return ((Hdma.HdmaState) gameboy.getHdma().captureState()).tick() == 0
                && gameboy.getHdma().isPerformanceNativeCgbOwnedDataStructurallyStable()
                && gameboy.getCpu().performanceHdmaOwnedBlockCpuFrozenEligible();
    }

}
