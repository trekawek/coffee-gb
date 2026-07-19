package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OamSearchTest {

    @Test
    public void findsVisibleSpriteWithoutDma() {
        Fixture fixture = new Fixture();

        fixture.runSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void hidesSpriteWhenDmaOverlapsOamScan() {
        Fixture fixture = new Fixture();
        fixture.dma.setByte(0xff46, 0x12);

        fixture.runSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void hidesSpriteWhenDmaEndsBetweenItsYAndXReads() {
        Fixture fixture = new Fixture();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.search.start();

        fixture.tickSearch();
        for (int i = 0; i < 648; i++) {
            fixture.dma.tick();
        }
        fixture.tickSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void spriteHeightIsSampledWithTheYCoordinate() {
        Fixture fixture = new Fixture();
        fixture.registers.put(GpuRegister.LY, 8);
        fixture.lcdc.set(0x97);
        fixture.settleLcdc();
        fixture.search.start();

        fixture.lcdc.set(0x93);
        fixture.tickSearch();
        fixture.tickSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void sampledSpriteHeightRoundTripsThroughMemento() {
        Fixture fixture = new Fixture(true);
        fixture.registers.put(GpuRegister.LY, 8);
        fixture.lcdc.set(0x97);
        fixture.settleLcdc();
        fixture.search.start();
        fixture.tickSearch();
        Memento<OamSearch> state = fixture.search.saveToMemento();

        Fixture restored = new Fixture(true);
        restored.registers.put(GpuRegister.LY, 8);
        restored.lcdc.set(0x93);
        restored.settleLcdc();
        restored.search.restoreFromMemento(state);
        restored.tickSearch();

        assertTrue(restored.search.getSprites()[0].isEnabled());
    }

    @Test
    public void cgbNormalSpeedDelaysHeightWriteThroughTheNextOamEntry() {
        Fixture fixture = new Fixture(true);
        fixture.registers.put(GpuRegister.LY, 8);
        fixture.oam.setByte(0xfe00, 0);
        fixture.oam.setByte(0xfe04, 16);
        fixture.oam.setByte(0xfe05, 8);
        fixture.lcdc.set(0x97);
        fixture.settleLcdc();
        fixture.search.start();

        fixture.lcdc.set(0x93);
        for (int i = 0; i < 4; i++) {
            fixture.tickSearch();
        }

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void cgbNormalSpeedHeightComparatorMixesOldAndNewSizeBits() {
        Fixture fixture = new Fixture(true);
        fixture.registers.put(GpuRegister.LY, 8);
        fixture.oam.setByte(0xfe00, 0);
        fixture.oam.setByte(0xfe04, 16);
        fixture.oam.setByte(0xfe05, 8);
        fixture.lcdc.set(0x93);
        fixture.settleLcdc();
        fixture.search.start();

        fixture.lcdc.set(0x97);
        for (int i = 0; i < 4; i++) {
            fixture.tickSearch();
        }

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void cgbNormalSpeedCurrentOamEntryStillSeesOldSizeBit() {
        Fixture fixture = new Fixture(true);
        fixture.registers.put(GpuRegister.LY, 8);
        fixture.lcdc.set(0x93);
        fixture.settleLcdc();
        fixture.search.start();

        fixture.lcdc.set(0x97);
        fixture.tickSearch();
        fixture.tickSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void recordsHeightTransitionsForTheCurrentLineAndMemento() {
        Fixture fixture = new Fixture(true);
        fixture.lcdc.set(0x93);
        fixture.settleLcdc();
        fixture.search.start();

        fixture.tickSearch();
        assertFalse(fixture.search.hadSpriteHeightTransition());

        fixture.lcdc.set(0x97);
        fixture.tickSearch();
        fixture.tickSearch();
        fixture.tickSearch();
        assertFalse(fixture.search.hadSpriteHeightTransition());
        fixture.tickSearch();
        assertTrue(fixture.search.hadSpriteHeightTransition());

        Memento<OamSearch> state = fixture.search.saveToMemento();
        Fixture restored = new Fixture(true);
        restored.search.restoreFromMemento(state);
        assertTrue(restored.search.hadSpriteHeightTransition());

        restored.search.start();
        assertFalse(restored.search.hadSpriteHeightTransition());
    }

    private static class Fixture {

        private static final Method LCDC_TICK_CONFLICTS;

        static {
            try {
                LCDC_TICK_CONFLICTS = Lcdc.class.getDeclaredMethod("tickConflicts");
                LCDC_TICK_CONFLICTS.setAccessible(true);
            } catch (NoSuchMethodException e) {
                throw new ExceptionInInitializerError(e);
            }
        }

        private final Ram memory = new Ram(0, 0x10000);

        private final Ram oam = new Ram(0xfe00, 0xa0);

        private final SpeedMode speedMode;

        private final Dma dma;

        private final GpuRegisterValues registers = new GpuRegisterValues();

        private final Lcdc lcdc = new Lcdc();

        private final OamSearch search;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean gbc) {
            speedMode = new SpeedMode(gbc);
            dma = new Dma(memory, oam, speedMode);
            registers.setGbc(gbc);
            registers.setSpeedMode(speedMode);
            lcdc.setGbc(gbc);
            search = new OamSearch(oam, dma, lcdc, registers);
            registers.put(GpuRegister.LY, 0);
            oam.setByte(0xfe00, 16);
            oam.setByte(0xfe01, 8);
        }

        private void runSearch() {
            search.start();
            while (tickSearch()) {
                // Complete the 80-dot OAM scan.
            }
        }

        private boolean tickSearch() {
            tickLcdc();
            return search.tick();
        }

        private void settleLcdc() {
            for (int i = 0; i < 8; i++) {
                tickLcdc();
            }
        }

        private void tickLcdc() {
            try {
                LCDC_TICK_CONFLICTS.invoke(lcdc);
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new AssertionError(e);
            }
        }
    }
}
