package eu.rekawek.coffeegb.core.gpu.phase;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.gpu.GpuRegister;
import eu.rekawek.coffeegb.core.gpu.GpuRegisterValues;
import eu.rekawek.coffeegb.core.gpu.Lcdc;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.Dma;
import eu.rekawek.coffeegb.core.memory.Ram;
import eu.rekawek.coffeegb.core.gpu.phase.OamSearch.SpritePosition;
import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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
    public void hidesSpriteWhenDmaOwnsOamDuringScan() {
        Fixture fixture = new Fixture();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);

        fixture.runSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void readsSpriteDuringDmaWarmup() {
        Fixture fixture = new Fixture();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(7, 100);
        fixture.beginSearchLine();

        fixture.tickSearch();
        fixture.tickSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void blockedDmaReadsFfButStillEvaluatesCandidate() {
        Fixture fixture = new Fixture();
        fixture.registers.put(GpuRegister.LY, 239);
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);
        fixture.beginSearchLine();

        fixture.tickSearch();
        fixture.tickSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void runningDmaKeepsLastOamWordOnReaderBus() {
        Fixture fixture = new Fixture();
        for (int i = 0; i < 40; i++) {
            fixture.oam.setByte(0xfe00 + 4 * i, 16);
            fixture.oam.setByte(0xfe01 + 4 * i, 8 + i);
        }
        fixture.runSearch();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);

        fixture.runSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
        assertTrue(fixture.search.getSprites()[9].isEnabled());
        assertEquals(47, fixture.search.getSprites()[0].getX());
        assertEquals(47, fixture.search.getSprites()[9].getX());
    }

    @Test
    public void objectTileIdReadReplacesHeldYBus() {
        Fixture fixture = new Fixture();
        fixture.fillOamPositions(16, 24);
        fixture.runSearch();
        fixture.search.latchObjectTileId(0xa0);
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);

        fixture.runSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void objectTileIdReadKeepsHeldXBus() {
        Fixture fixture = new Fixture();
        fixture.fillOamPositions(0xa0, 24);
        fixture.runSearch();
        fixture.search.latchObjectTileId(16);
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);

        fixture.runSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
        assertEquals(24, fixture.search.getSprites()[0].getX());
    }

    @Test
    public void cgbDmaDisconnectsAndPrechargesTheReaderBus() {
        Fixture fixture = new Fixture(true);
        fixture.fillOamPositions(16, 24);
        fixture.runSearch();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);

        fixture.runSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void dmaAcquisitionFinishesTheCachedOamWord() {
        Fixture fixture = new Fixture();
        fixture.runSearch();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(7, 100);
        fixture.dmaTickAtReaderPosition(0);
        fixture.search.start();
        fixture.readerPosition = 1;

        fixture.tickSearch();
        fixture.tickSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
        assertEquals(8, fixture.search.getSprites()[0].getX());
    }

    @Test
    public void dmaReleaseFinishesTheDisabledOamWord() {
        Fixture fixture = new Fixture();
        fixture.runSearch();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);
        fixture.search.trackDmaSource(0);
        fixture.advanceDma(639, 100);
        fixture.dmaTickAtReaderPosition(0);
        fixture.search.start();
        fixture.readerPosition = 1;

        fixture.tickSearch();
        fixture.tickSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void readerCapturesYAndXTogether() {
        Fixture fixture = new Fixture();
        fixture.search.trackDmaSource(0);
        fixture.oam.setByte(0xfe01, 24);
        fixture.search.start();
        fixture.readerPosition = 1;

        fixture.tickSearch();
        fixture.tickSearch();

        assertEquals(8, fixture.search.getSprites()[0].getX());
    }

    @Test
    public void readerInitializesWhenTheFirstTrackedPositionIsOutsideMode2() {
        Fixture fixture = new Fixture();

        assertFalse(fixture.search.isOamReaderInitialized());
        fixture.search.trackDmaSource(100);

        assertTrue(fixture.search.isOamReaderInitialized());
    }

    @Test
    public void readerTracksDmaSourceChangesOutsideMode2() {
        Fixture fixture = new Fixture();
        fixture.search.trackDmaSource(0);
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDmaWithoutReader(7);
        fixture.dma.tick();

        assertTrue(fixture.dma.hasPpuOamOwnershipTransitionThisTick());
        fixture.search.trackDmaSource(100);

        assertTrue(booleanField(fixture.search, "oamReaderDmaSource"));
        assertEquals(80, intField(fixture.search, "oamReaderSourceChangeTicks"));
    }

    @Test
    public void stableTrackingOutsideMode2LeavesTheReaderUntouched() {
        Fixture fixture = new Fixture();
        fixture.search.trackDmaSource(0);
        int[] beforeY = intArrayField(fixture.search, "oamReaderY").clone();
        int[] beforeX = intArrayField(fixture.search, "oamReaderX").clone();
        int beforeBusY = intField(fixture.search, "oamReaderBusY");
        int beforeBusX = intField(fixture.search, "oamReaderBusX");
        boolean beforeSource = booleanField(fixture.search, "oamReaderDmaSource");
        int beforeChangeTicks = intField(fixture.search, "oamReaderSourceChangeTicks");

        fixture.search.trackDmaSource(80);

        assertArrayEquals(beforeY, intArrayField(fixture.search, "oamReaderY"));
        assertArrayEquals(beforeX, intArrayField(fixture.search, "oamReaderX"));
        assertEquals(beforeBusY, intField(fixture.search, "oamReaderBusY"));
        assertEquals(beforeBusX, intField(fixture.search, "oamReaderBusX"));
        assertEquals(beforeSource, booleanField(fixture.search, "oamReaderDmaSource"));
        assertEquals(beforeChangeTicks, intField(fixture.search, "oamReaderSourceChangeTicks"));
    }

    @Test
    public void everyMode2ReaderPositionConsumesOneSourceChangeTick() {
        Fixture fixture = new Fixture();
        fixture.search.onLcdEnabled();

        for (int readerPosition = 0; readerPosition < 80; readerPosition++) {
            fixture.search.trackDmaSource(readerPosition);
            assertEquals(79 - readerPosition,
                    intField(fixture.search, "oamReaderSourceChangeTicks"));
        }
    }

    @Test
    public void cachedOamWordRoundTripsThroughMementos() {
        Fixture fixture = new Fixture();
        fixture.runSearch();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(7, 100);
        fixture.dmaTickAtReaderPosition(0);
        fixture.search.start();
        fixture.readerPosition = 1;
        ComponentState<OamSearch> searchState = fixture.search.captureState();
        ComponentState<Dma> dmaState = fixture.dma.captureState();

        Fixture restored = new Fixture();
        restored.oam.setByte(0xfe00, 0);
        restored.oam.setByte(0xfe01, 24);
        restored.search.restoreState(searchState);
        restored.dma.restoreState(dmaState);
        restored.readerPosition = 1;
        restored.tickSearch();
        restored.tickSearch();

        assertTrue(restored.search.getSprites()[0].isEnabled());
        assertEquals(8, restored.search.getSprites()[0].getX());
    }

    @Test
    public void lcdEnableReconnectsReaderAfterDmaFinishesWhileClockedOff() {
        Fixture fixture = new Fixture();
        fixture.runSearch();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDma(8, 100);
        fixture.advanceDmaWithoutReader(640);
        fixture.oam.setByte(0xfe00, 16);
        fixture.oam.setByte(0xfe01, 8);

        fixture.search.onLcdEnabled();
        fixture.runSearch();

        assertTrue(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void lcdEnableReconnectsReaderToDmaThatStartedWhileClockedOff() {
        Fixture fixture = new Fixture();
        fixture.runSearch();
        fixture.dma.setByte(0xff46, 0x12);
        fixture.advanceDmaWithoutReader(8);

        fixture.search.onLcdEnabled();
        fixture.runSearch();

        assertFalse(fixture.search.getSprites()[0].isEnabled());
    }

    @Test
    public void spriteHeightIsSampledWithTheYCoordinate() {
        Fixture fixture = new Fixture();
        fixture.registers.put(GpuRegister.LY, 8);
        fixture.lcdc.set(0x97);
        fixture.settleLcdc();
        fixture.beginSearchLine();

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
        fixture.beginSearchLine();
        fixture.tickSearch();
        ComponentState<OamSearch> state = fixture.search.captureState();

        Fixture restored = new Fixture(true);
        restored.registers.put(GpuRegister.LY, 8);
        restored.lcdc.set(0x93);
        restored.settleLcdc();
        restored.search.restoreState(state);
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
        fixture.beginSearchLine();

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
        fixture.beginSearchLine();

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
        fixture.beginSearchLine();

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
        fixture.beginSearchLine();

        fixture.tickSearch();
        assertFalse(fixture.search.hadSpriteHeightTransition());

        fixture.lcdc.set(0x97);
        fixture.tickSearch();
        fixture.tickSearch();
        fixture.tickSearch();
        assertFalse(fixture.search.hadSpriteHeightTransition());
        fixture.tickSearch();
        assertTrue(fixture.search.hadSpriteHeightTransition());

        ComponentState<OamSearch> state = fixture.search.captureState();
        Fixture restored = new Fixture(true);
        restored.search.restoreState(state);
        assertTrue(restored.search.hadSpriteHeightTransition());

        restored.beginSearchLine();
        assertFalse(restored.search.hadSpriteHeightTransition());
    }

    @Test
    public void performanceNoDmaSpanMatchesScalarAcrossHalfSlotBoundaries() {
        int[] starts = {13, 14, 27, 38, 77, 78};
        int[] requestedLengths = {1, 2, 3, 7, 19, 54};
        for (int start : starts) {
            for (int requested : requestedLengths) {
                int ticks = Math.min(requested, 79 - start);
                if (ticks <= 0) {
                    continue;
                }
                Fixture scalar = performanceFixture();
                Fixture bulk = performanceFixture();
                for (int i = 0; i < start; i++) {
                    scalar.tickSearch();
                    bulk.tickSearch();
                }
                assertTrue("bulk preflight rejected dot " + start,
                        bulk.search.isPerformanceNoDmaStableSpanEligible(
                                start, bulk.lcdc.getSpriteHeight()));

                for (int i = 0; i < ticks; i++) {
                    scalar.tickSearch();
                }
                bulk.search.advancePerformanceNoDmaStableSpanTrusted(
                        start, ticks, bulk.lcdc.getSpriteHeight());

                assertSearchStateEquals("dot " + start + " + " + ticks,
                        scalar.search, bulk.search);
            }
        }
    }

    private static Fixture performanceFixture() {
        Fixture fixture = new Fixture(true, true);
        fixture.registers.put(GpuRegister.LY, 24);
        fixture.lcdc.set(0x97);
        fixture.settleLcdc();
        for (int entry = 0; entry < 40; entry++) {
            int y = entry % 3 == 0 ? 32 : entry % 3 == 1 ? 40 : 8;
            fixture.oam.setByte(0xfe00 + 4 * entry, y);
            fixture.oam.setByte(0xfe01 + 4 * entry, 8 + entry);
        }
        fixture.beginSearchLine();
        return fixture;
    }

    private static void assertSearchStateEquals(
            String message, OamSearch expected, OamSearch actual) {
        assertArrayEquals(message + " reader Y",
                intArrayField(expected, "oamReaderY"), intArrayField(actual, "oamReaderY"));
        assertArrayEquals(message + " reader X",
                intArrayField(expected, "oamReaderX"), intArrayField(actual, "oamReaderX"));
        String[] intFields = {
                "spritePosIndex", "spriteY", "spriteHeight", "previousOamSpriteHeight",
                "spriteX", "oamReaderBusY", "oamReaderBusX",
                "oamReaderSourceChangeTicks", "i"
        };
        for (String name : intFields) {
            assertEquals(message + ' ' + name, intField(expected, name), intField(actual, name));
        }
        String[] booleanFields = {
                "oamReaderInitialized", "oamReaderDmaSource",
                "spriteHeightTransitionThisLine", "dmaBlockedThisLine",
                "selectSprites", "spriteCandidateSeen"
        };
        for (String name : booleanFields) {
            assertEquals(message + ' ' + name,
                    booleanField(expected, name), booleanField(actual, name));
        }
        assertEquals(message + " half-slot state",
                objectField(expected, "state"), objectField(actual, "state"));
        for (int i = 0; i < expected.getSprites().length; i++) {
            SpritePosition expectedSprite = expected.getSprites()[i];
            SpritePosition actualSprite = actual.getSprites()[i];
            assertEquals(message + " sprite " + i + " enabled",
                    expectedSprite.isEnabled(), actualSprite.isEnabled());
            assertEquals(message + " sprite " + i + " x",
                    expectedSprite.getX(), actualSprite.getX());
            assertEquals(message + " sprite " + i + " y",
                    expectedSprite.getY(), actualSprite.getY());
            assertEquals(message + " sprite " + i + " address",
                    expectedSprite.getAddress(), actualSprite.getAddress());
        }
    }

    private static Field field(OamSearch search, String name) {
        try {
            Field field = OamSearch.class.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static int intField(OamSearch search, String name) {
        try {
            return field(search, name).getInt(search);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static int[] intArrayField(OamSearch search, String name) {
        try {
            return (int[]) field(search, name).get(search);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean booleanField(OamSearch search, String name) {
        try {
            return field(search, name).getBoolean(search);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
    }

    private static Object objectField(OamSearch search, String name) {
        try {
            return field(search, name).get(search);
        } catch (IllegalAccessException e) {
            throw new AssertionError(e);
        }
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

        private int readerPosition;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean gbc) {
            this(gbc, false);
        }

        private Fixture(boolean gbc, boolean doubleSpeed) {
            speedMode = doubleSpeed ? new SpeedMode(gbc) {
                @Override
                public int getSpeedMode() {
                    return 2;
                }
            } : new SpeedMode(gbc);
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
            beginSearchLine();
            while (tickSearch()) {
                // Complete the 80-dot OAM scan.
            }
        }

        private void fillOamPositions(int y, int x) {
            for (int entry = 0; entry < 40; entry++) {
                oam.setByte(0xfe00 + 4 * entry, y);
                oam.setByte(0xfe01 + 4 * entry, x);
            }
        }

        private void beginSearchLine() {
            dma.tick();
            search.trackDmaSource(0);
            search.start();
            readerPosition = 1;
        }

        private boolean tickSearch() {
            dma.tick();
            search.trackDmaSource(readerPosition++);
            tickLcdc();
            return search.tick();
        }

        private void advanceDma(int ticks, int readerPosition) {
            for (int i = 0; i < ticks; i++) {
                dmaTickAtReaderPosition(readerPosition);
            }
        }

        private void advanceDmaWithoutReader(int ticks) {
            for (int i = 0; i < ticks; i++) {
                dma.tick();
            }
        }

        private void dmaTickAtReaderPosition(int readerPosition) {
            dma.tick();
            search.trackDmaSource(readerPosition);
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
