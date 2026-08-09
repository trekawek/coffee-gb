package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GpuTimingSnapshotTest {

    @Test
    public void snapshotMatchesGpuTimingGettersAcrossPpuStates() {
        for (boolean gbc : new boolean[]{false, true}) {
            for (int speed : new int[]{1, 2}) {
                for (boolean dmgCompat : new boolean[]{false, true}) {
                    Fixture fixture = new Fixture(gbc, speed, dmgCompat);
                    assertSnapshotMatchesGetters(fixture);

                    fixture.gpu.setByte(0xff40, 0);
                    assertSnapshotMatchesGetters(fixture);

                    fixture.gpu.setByte(0xff40, 0x91);
                    assertSnapshotMatchesGetters(fixture);
                    fixture.advanceTo(1, 100);
                    assertSnapshotMatchesGetters(fixture);
                    fixture.advanceTo(145, 100);
                    assertSnapshotMatchesGetters(fixture);
                    fixture.advanceTo(153, 0);
                    assertSnapshotMatchesGetters(fixture);
                }
            }
        }
    }

    @Test
    public void liveStatCheckpointMatchesSnapshotPredicateAcrossPpuConfigurations() {
        for (boolean gbc : new boolean[]{false, true}) {
            for (int speed : new int[]{1, 2}) {
                for (boolean dmgCompat : new boolean[]{false, true}) {
                    Fixture fixture = new Fixture(gbc, speed, dmgCompat);

                    fixture.gpu.setByte(0xff40, 0);
                    assertFalse(fixture.gpu.isStatEventCheckpoint());
                    assertCheckpointMatchesSnapshot(fixture);

                    fixture.gpu.setByte(0xff40, 0x91);
                    int finiteMode0Edges = 0;
                    int mode0EdgeChanges = 0;
                    int previousMode0Edge = Integer.MIN_VALUE;
                    // Scan every dot in a complete frame after LCD enable. This
                    // covers line/tail boundaries and samples the live mode-0
                    // prediction while pixel transfer updates it.
                    for (int dot = 0; dot < 154 * 456; dot++) {
                        GpuTimingSnapshot snapshot = new GpuTimingSnapshot();
                        fixture.gpu.captureStatTiming(snapshot);
                        assertEquals(legacyStatEventCheckpoint(snapshot),
                                fixture.gpu.isStatEventCheckpoint());
                        if (snapshot.mode0InterruptTick != Integer.MAX_VALUE) {
                            finiteMode0Edges++;
                        }
                        if (previousMode0Edge != Integer.MIN_VALUE
                                && previousMode0Edge != snapshot.mode0InterruptTick) {
                            mode0EdgeChanges++;
                        }
                        previousMode0Edge = snapshot.mode0InterruptTick;
                        fixture.tick();
                    }
                    assertTrue("expected a live mode-0 edge", finiteMode0Edges > 0);
                    assertTrue("expected pixel transfer to update the mode-0 edge",
                            mode0EdgeChanges > 0);
                }
            }
        }
    }

    @Test
    public void dmgCompatibilityChangeUpdatesTimingAndCpuAccessWithoutGpuTick() {
        Fixture fixture = new Fixture(true, 1, false);
        GpuTimingSnapshot snapshot = new GpuTimingSnapshot();
        fixture.gpu.captureStatTiming(snapshot);
        assertFalse(snapshot.dmgCompat);

        fixture.speedMode.setDmgCompat(true);

        assertTrue(fixture.gpu.isDmgCompatMode());
        fixture.gpu.captureStatTiming(snapshot);
        assertTrue(snapshot.dmgCompat);
        fixture.gpu.setByteFromCpu(GpuRegister.VBK.getAddress(), 1);
        assertEquals(0xfe, fixture.gpu.getByte(GpuRegister.VBK.getAddress()));
    }

    @Test
    public void speedSwitchUpdatesTimingSnapshotWithoutGpuTick() {
        Fixture fixture = new Fixture(true, new SpeedMode(true));
        GpuTimingSnapshot snapshot = new GpuTimingSnapshot();

        fixture.gpu.captureStatTiming(snapshot);
        assertEquals(4, snapshot.cpuMachineCycleDots);
        switchSpeed(fixture, 2);
        fixture.gpu.captureStatTiming(snapshot);
        assertEquals(2, snapshot.cpuMachineCycleDots);
        switchSpeed(fixture, 1);
        fixture.gpu.captureStatTiming(snapshot);
        assertEquals(4, snapshot.cpuMachineCycleDots);
    }

    @Test
    public void speedModeRestoreAfterGpuRestoreRefreshesTimingSnapshot() {
        Fixture fixture = new Fixture(true, 1, false);
        fixture.speedMode.setDmgCompat(true);
        var gpuState = fixture.gpu.captureState();
        var speedModeState = fixture.speedMode.captureState();

        fixture.speedMode.setDmgCompat(false);
        fixture.gpu.restoreState(gpuState);
        fixture.speedMode.restoreState(speedModeState);

        GpuTimingSnapshot snapshot = new GpuTimingSnapshot();
        fixture.gpu.captureStatTiming(snapshot);
        assertTrue(fixture.gpu.isDmgCompatMode());
        assertTrue(snapshot.dmgCompat);
    }

    private static void assertSnapshotMatchesGetters(Fixture fixture) {
        GpuTimingSnapshot snapshot = new GpuTimingSnapshot();
        fixture.gpu.captureStatTiming(snapshot);

        assertEquals(fixture.gpu.getLine(), snapshot.line);
        assertEquals(fixture.gpu.getTicksInLine(), snapshot.ticksInLine);
        assertEquals(fixture.gpu.getVisibleLy(), snapshot.visibleLy);
        assertEquals(fixture.gpu.getEarlyLineEdgeTick(), snapshot.earlyLineEdgeTick);
        assertEquals(fixture.gpu.getMode0InterruptTick(), snapshot.mode0InterruptTick);
        assertEquals(fixture.gpu.getCpuMachineCycleDots(), snapshot.cpuMachineCycleDots);
        assertEquals(fixture.gpu.isDmgCompatMode(), snapshot.dmgCompat);
        assertEquals(fixture.gpu.isLcdEnabled(), snapshot.lcdEnabled);
        assertEquals(fixture.gpu.isFirstLine(), snapshot.firstLine);
        assertEquals(fixture.gpu.isStatModeLatchRephasedBySpeedSwitch(),
                snapshot.statModeLatchRephasedBySpeedSwitch);
        assertEquals(fixture.gpu.isMode0HaltWakeTick(), snapshot.mode0HaltWakeTick);
        assertEquals(fixture.gpu.isMode0IntWindow(), snapshot.mode0IntWindow);
        assertEquals(fixture.gpu.isMode1IntWindow(), snapshot.mode1IntWindow);
        assertEquals(fixture.gpu.isMode2IntWindow(), snapshot.mode2IntWindow);
        assertEquals(snapshot.cpuMachineCycleDots == 2, snapshot.doubleSpeed);
        assertEquals(fixture.gpu.isGbc() && !fixture.gpu.isDmgCompatMode()
                        && fixture.gpu.getCpuMachineCycleDots() == 2,
                snapshot.nativeDoubleSpeed);
    }

    private static void assertCheckpointMatchesSnapshot(Fixture fixture) {
        GpuTimingSnapshot snapshot = new GpuTimingSnapshot();
        fixture.gpu.captureStatTiming(snapshot);
        assertEquals(legacyStatEventCheckpoint(snapshot), fixture.gpu.isStatEventCheckpoint());
    }

    private static boolean legacyStatEventCheckpoint(GpuTimingSnapshot snapshot) {
        if (!snapshot.lcdEnabled) {
            return false;
        }
        int ticksInLine = snapshot.ticksInLine;
        int mode0InterruptTick = snapshot.mode0InterruptTick;
        return ticksInLine < 13
                || ticksInLine >= 448
                || ticksInLine == mode0InterruptTick
                || (snapshot.line < 144 && mode0InterruptTick != Integer.MAX_VALUE
                && ticksInLine == mode0InterruptTick + 2);
    }

    private static void switchSpeed(Fixture fixture, int expectedSpeed) {
        Ram memory = new Ram(0, 0x10000);
        memory.setByte(0x100, 0x10);
        memory.setByte(0x101, 0);
        memory.setByte(0xff00, 0xcf);
        fixture.speedMode.setByte(0xff4d, 1);
        Cpu cpu = new Cpu(memory, new InterruptManager(true), fixture.gpu,
                fixture.speedMode, new Display(true));
        cpu.getRegisters().setPC(0x100);
        for (int i = 0; i < 20 && fixture.speedMode.getSpeedMode() != expectedSpeed; i++) {
            cpu.tick();
        }
        assertEquals(expectedSpeed, fixture.speedMode.getSpeedMode());
    }

    private static class Fixture {

        private final StatRegister stat;

        private final Gpu gpu;

        private final SpeedMode speedMode;

        private Fixture(boolean gbc, int speed, boolean dmgCompat) {
            this(gbc, new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            });
            speedMode.setDmgCompat(dmgCompat);
        }

        private Fixture(boolean gbc, SpeedMode speedMode) {
            Ram oam = new Ram(0xfe00, 0xa0);
            InterruptManager interrupts = new InterruptManager(gbc);
            stat = new StatRegister(interrupts);
            this.speedMode = speedMode;
            gpu = new Gpu(
                    new Display(gbc),
                    new Dma(new Ram(0, 0x10000), oam, speedMode),
                    oam,
                    new VRamTransfer(EventBus.NULL_EVENT_BUS),
                    stat,
                    gbc,
                    speedMode);
            stat.init(gpu);
        }

        private void advanceTo(int targetLine, int targetTicksInLine) {
            while (gpu.getLine() != targetLine
                    || gpu.getTicksInLine() != targetTicksInLine) {
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
