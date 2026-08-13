package eu.rekawek.coffeegb.ui.menu;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PauseMenuSnapshotTest {

    @Test
    public void formatsElapsedTimeAtBothDisplayWidthsAndCapsIt() {
        assertEquals("00:00", PauseMenuSnapshot.formatPlayTime(0));
        assertEquals("59:07", PauseMenuSnapshot.formatPlayTime(3_547L * 1_000_000_000L));
        assertEquals("1:00:00", PauseMenuSnapshot.formatPlayTime(3_600L * 1_000_000_000L));
        assertEquals("12:34:56", PauseMenuSnapshot.formatPlayTime(
                (12L * 3_600L + 34L * 60L + 56L) * 1_000_000_000L));
        assertEquals("999:59:59", PauseMenuSnapshot.formatPlayTime(Long.MAX_VALUE));
    }

    @Test
    public void retainsDetachedImmutablePreviewAndMapperCapability() {
        int[] producerPixels = {0xff123456};
        PauseMenuSnapshot snapshot = new PauseMenuSnapshot("TETRIS", 42L,
                true, MenuPreview.ready(1, 1, producerPixels));
        producerPixels[0] = 0;

        assertEquals("TETRIS", snapshot.romTitle());
        assertEquals(42L, snapshot.playTimeNanos());
        assertEquals(true, snapshot.batterySaveActive());
        assertEquals(0xff123456, snapshot.preview().copyPixels()[0]);
    }
}
