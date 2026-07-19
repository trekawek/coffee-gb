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

public class GpuOamAccessTest {

    @Test
    public void mode2WriteOpeningIsDmgOnly() {
        Fixture dmg = new Fixture(false, 1);
        Fixture cgb = new Fixture(true, 1);
        dmg.advanceTo(0, 77);
        cgb.advanceTo(0, 77);

        assertTrue(dmg.gpu.isOamAvailableForCpu(true));
        assertFalse(cgb.gpu.isOamAvailableForCpu(true));
    }

    @Test
    public void cgbBgOnlyOamOpensAtNormalSpeedMode0Edge() {
        assertBgOnlyMode0Edge(1);
    }

    @Test
    public void cgbBgOnlyOamOpensAtDoubleSpeedMode0Edge() {
        assertBgOnlyMode0Edge(2);
    }

    @Test
    public void cgbObjectLineKeepsReadLatchClosedUntilMode0Edge() {
        Fixture fixture = new Fixture(true, 1);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);
        fixture.gpu.setByte(0xff40, 0x93);

        fixture.advanceToInternalHBlank();

        assertTrue(fixture.gpu.hasObjectsOnLine());
        assertFalse(fixture.gpu.isOamAvailableForCpu(false));
        assertTrue(fixture.gpu.isOamAvailableForCpu(true));
    }

    @Test
    public void cgbNormalSpeedWriteLatchClosesAtCgbLineEdge() {
        Fixture fixture = new Fixture(true, 1);
        fixture.advanceTo(0, 447);

        assertTrue(fixture.gpu.isOamAvailableForCpu(false));
        assertTrue(fixture.gpu.isOamAvailableForCpu(true));

        fixture.tick();
        assertFalse(fixture.gpu.isOamAvailableForCpu(false));
        assertFalse(fixture.gpu.isOamAvailableForCpu(true));
    }

    @Test
    public void cgbDoubleSpeedKeepsBothLineEdgeLatchesClosed() {
        Fixture fixture = new Fixture(true, 2);
        fixture.advanceTo(0, 452);

        assertFalse(fixture.gpu.isOamAvailableForCpu(false));
        assertFalse(fixture.gpu.isOamAvailableForCpu(true));

        fixture.tick();
        fixture.tick();
        fixture.tick();
        assertFalse(fixture.gpu.isOamAvailableForCpu(false));
        assertFalse(fixture.gpu.isOamAvailableForCpu(true));
    }

    @Test
    public void cgbMode0PredictionCountsX166SpritesWhileDmgUsesCompletedTransfer() {
        for (boolean gbc : new boolean[] {false, true}) {
            Fixture atPredictionEdge = new Fixture(gbc, 1);
            atPredictionEdge.putTenSpritesAt(166);
            int x166Edge = atPredictionEdge.advanceToMode0InterruptEdge();

            Fixture afterPredictionEdge = new Fixture(gbc, 1);
            afterPredictionEdge.putTenSpritesAt(167);
            int x167Edge = afterPredictionEdge.advanceToMode0InterruptEdge();

            assertEquals(gbc ? 10 * 6 : 0, x166Edge - x167Edge);
        }
    }

    @Test
    public void predictedMode0EdgeCanPrecedePhysicalHblankAndSurvivesMemento() {
        Fixture fixture = new Fixture(true, 1);
        fixture.putTenSpritesAt(167);
        Memento<Gpu> immediatelyBeforeEdge = null;

        while (!fixture.gpu.isMode0IntWindow()) {
            immediatelyBeforeEdge = fixture.gpu.saveToMemento();
            fixture.tick();
        }

        assertEquals(Mode.PixelTransfer, fixture.gpu.getMode());
        fixture.gpu.restoreFromMemento(immediatelyBeforeEdge);
        assertFalse(fixture.gpu.isMode0IntWindow());
        fixture.tick();
        assertTrue(fixture.gpu.isMode0IntWindow());
        assertEquals(Mode.PixelTransfer, fixture.gpu.getMode());
    }

    private static void assertBgOnlyMode0Edge(int speed) {
        Fixture fixture = new Fixture(true, speed);
        fixture.advanceTo(1, 0);
        fixture.advanceToInternalHBlank();
        while (!fixture.gpu.isMode0IntWindow()) {
            fixture.tick();
        }

        assertTrue(fixture.gpu.isOamAvailableForCpu(false));
        assertTrue(fixture.gpu.isOamAvailableForCpu(true));
    }

    private static class Fixture {

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final StatRegister stat = new StatRegister(new InterruptManager(true));

        private final Gpu gpu;

        private Fixture(boolean gbc, int speed) {
            SpeedMode speedMode = new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return speed;
                }
            };
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

        private void advanceToInternalHBlank() {
            Mode previous;
            do {
                previous = gpu.getMode();
                tick();
            } while (previous != Mode.PixelTransfer || gpu.getMode() != Mode.HBlank);
        }

        private void advanceTo(int line, int dot) {
            while (gpu.getLine() != line || gpu.getTicksInLine() != dot) {
                tick();
            }
        }

        private void putTenSpritesAt(int x) {
            for (int i = 0; i < 10; i++) {
                oam.setByte(0xfe00 + i * 4, 16);
                oam.setByte(0xfe01 + i * 4, x);
            }
            gpu.setByte(0xff40, 0x93);
        }

        private int advanceToMode0InterruptEdge() {
            while (!gpu.isMode0IntWindow()) {
                tick();
            }
            return gpu.getTicksInLine();
        }

        private void tick() {
            gpu.tick();
            stat.tick();
        }
    }
}
