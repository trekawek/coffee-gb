package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;

public class LcdcHistoryTest {

    private static final int HISTORY_LENGTH = 8;

    @Test
    public void circularHistoriesMatchLinearReferenceAcrossWrapsAndRestore() {
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(true);
        HistoryReference reference = new HistoryReference();

        for (int dot = 0; dot < 37; dot++) {
            tick(lcdc, reference, dot);
        }

        ComponentState<Lcdc> beforeRestore = lcdc.captureState();
        assertCaptureMatches(beforeRestore, reference);

        Lcdc restored = new Lcdc();
        restored.setGbc(true);
        restored.restoreState(beforeRestore);
        assertHistoriesMatch(restored, reference);

        for (int dot = 37; dot < 128; dot++) {
            tick(restored, reference, dot);
        }
        assertHistoriesMatch(restored, reference);
        assertCaptureMatches(restored.captureState(), reference);
    }

    @Test
    public void clearTileSelectGlitchAtNonzeroHeadPreservesOamHistory() {
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(true);
        HistoryReference reference = new HistoryReference();

        for (int dot = 0; dot < 13; dot++) {
            tick(lcdc, reference, dot);
        }
        int[] oamBeforeClear = reference.oamSizeHistory.clone();

        lcdc.set(0);
        reference.clearTileSelectGlitch();

        for (int dotsAgo = 0; dotsAgo < HISTORY_LENGTH; dotsAgo++) {
            assertFalse(lcdc.isTileSelectGlitch(dotsAgo));
        }
        assertArrayEquals(oamBeforeClear, oamSizeHistory(lcdc.captureState()));
        assertCaptureMatches(lcdc.captureState(), reference);
    }

    @Test
    public void ordinaryAndPooledCapturesExposeLogicalOrderWithoutSharingMutableArrays() {
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(true);
        HistoryReference reference = new HistoryReference();

        for (int dot = 0; dot < 19; dot++) {
            tick(lcdc, reference, dot);
        }

        ComponentState<Lcdc> first = lcdc.captureState();
        ComponentState<Lcdc> second = lcdc.captureState();
        assertCaptureMatches(first, reference);
        assertCaptureMatches(second, reference);
        assertNotSame(tileSelectGlitchHistory(first), tileSelectGlitchHistory(second));
        assertNotSame(oamSizeHistory(first), oamSizeHistory(second));

        tileSelectGlitchHistory(first)[0] = !tileSelectGlitchHistory(first)[0];
        oamSizeHistory(first)[0] ^= 0x4000_0000;
        assertCaptureMatches(lcdc.captureState(), reference);

        CapturedHistories pooled = MachineStateCapture.withVerifiedView(
                capture -> { },
                capture -> lcdc.captureState(capture),
                (state, capture) -> new CapturedHistories(
                        tileSelectGlitchHistory(state).clone(),
                        oamSizeHistory(state).clone()));
        assertArrayEquals(reference.tileSelectGlitchHistory, pooled.tileSelectGlitchHistory);
        assertArrayEquals(reference.oamSizeHistory, pooled.oamSizeHistory);
    }

    private static void tick(Lcdc lcdc, HistoryReference reference, int dot) {
        int value = historyValue(dot);
        lcdc.set(value);
        reference.set(value);
        if ((dot % 5) == 1 || (dot % 11) == 7) {
            lcdc.triggerTileSelectGlitch();
            reference.triggerTileSelectGlitch();
        }
        lcdc.tickConflicts();
        reference.tickConflicts();
        assertHistoriesMatch(lcdc, reference);
    }

    private static int historyValue(int dot) {
        return switch (dot & 3) {
            case 0 -> 0x1_0000 | (dot << 8) | ((dot & 1) << 2) | 0x91;
            case 1 -> 0x7000_0000 | (dot << 4) | ((dot & 1) << 2) | 0x80;
            case 2 -> 0x8000_0000 | (dot << 12) | ((dot & 1) << 2) | 0x93;
            default -> -0x10000 | (dot << 2) | ((dot & 1) << 2) | 0xa2;
        };
    }

    private static void assertHistoriesMatch(Lcdc lcdc, HistoryReference reference) {
        for (int dotsAgo = 0; dotsAgo < HISTORY_LENGTH; dotsAgo++) {
            assertEquals(reference.tileSelectGlitchHistory[dotsAgo], lcdc.isTileSelectGlitch(dotsAgo));
            assertEquals(reference.oamSpriteHeight(dotsAgo), lcdc.getOamSpriteHeight(dotsAgo));
        }
    }

    private static void assertCaptureMatches(ComponentState<Lcdc> state, HistoryReference reference) {
        assertArrayEquals(reference.tileSelectGlitchHistory, tileSelectGlitchHistory(state));
        assertArrayEquals(reference.oamSizeHistory, oamSizeHistory(state));
    }

    private static boolean[] tileSelectGlitchHistory(ComponentState<Lcdc> state) {
        return (boolean[]) recordComponent(state, "tileSelectGlitchHistory");
    }

    private static int[] oamSizeHistory(ComponentState<Lcdc> state) {
        return (int[]) recordComponent(state, "oamSizeHistory");
    }

    private static Object recordComponent(Object state, String name) {
        try {
            Method accessor = state.getClass().getDeclaredMethod(name);
            accessor.setAccessible(true);
            return accessor.invoke(state);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private record CapturedHistories(boolean[] tileSelectGlitchHistory, int[] oamSizeHistory) {
    }

    private static final class HistoryReference {

        private int value = 0x91;

        private int tileSelectGlitchTicks;

        private int pendingTileSelectGlitchTicks;

        private final boolean[] tileSelectGlitchHistory = new boolean[HISTORY_LENGTH];

        private final int[] oamSizeHistory = new int[HISTORY_LENGTH];

        private HistoryReference() {
            Arrays.fill(oamSizeHistory, value);
        }

        private void set(int value) {
            this.value = value;
        }

        private void triggerTileSelectGlitch() {
            pendingTileSelectGlitchTicks = 1;
        }

        private void clearTileSelectGlitch() {
            tileSelectGlitchTicks = 0;
            pendingTileSelectGlitchTicks = 0;
            Arrays.fill(tileSelectGlitchHistory, false);
        }

        private void tickConflicts() {
            if (pendingTileSelectGlitchTicks > 0) {
                tileSelectGlitchTicks = pendingTileSelectGlitchTicks;
                pendingTileSelectGlitchTicks = 0;
            } else if (tileSelectGlitchTicks > 0) {
                tileSelectGlitchTicks--;
            }
            System.arraycopy(tileSelectGlitchHistory, 0, tileSelectGlitchHistory, 1,
                    HISTORY_LENGTH - 1);
            tileSelectGlitchHistory[0] = tileSelectGlitchTicks > 0;
            System.arraycopy(oamSizeHistory, 0, oamSizeHistory, 1, HISTORY_LENGTH - 1);
            oamSizeHistory[0] = value;
        }

        private int oamSpriteHeight(int dotsAgo) {
            return (oamSizeHistory[dotsAgo] & 0x04) == 0 ? 8 : 16;
        }
    }
}
