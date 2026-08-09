package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

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

    private static class Fixture {

        private final StatRegister stat;

        private final Gpu gpu;

        private Fixture(boolean gbc, int speed, boolean dmgCompat) {
            Ram oam = new Ram(0xfe00, 0xa0);
            InterruptManager interrupts = new InterruptManager(gbc);
            stat = new StatRegister(interrupts);
            SpeedMode speedMode = new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
            speedMode.setDmgCompat(dmgCompat);
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
    }
}
