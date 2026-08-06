package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memento.Memento;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class LcdcTest {

    @Test
    public void defaultOamSizeHistoryMatchesPowerOnLcdcValue() {
        Lcdc lcdc = new Lcdc();

        for (int dotsAgo = 0; dotsAgo < 8; dotsAgo++) {
            assertEquals(8, lcdc.getOamSpriteHeight(dotsAgo));
        }
    }

    @Test
    public void oamSizeHistorySurvivesMementoAndContinuesShifting() {
        Lcdc lcdc = new Lcdc();
        lcdc.setGbc(true);
        for (int tick = 0; tick < 8; tick++) {
            lcdc.set(0x91 | ((tick & 1) << 2));
            lcdc.tickConflicts();
        }
        assertAlternatingHistory(lcdc, 7);

        Memento<Lcdc> state = lcdc.saveToMemento();
        Lcdc restored = new Lcdc();
        restored.restoreFromMemento(state);
        assertAlternatingHistory(restored, 7);

        restored.set(0x91);
        restored.tickConflicts();
        assertEquals(8, restored.getOamSpriteHeight(0));
        for (int dotsAgo = 1; dotsAgo < 8; dotsAgo++) {
            int sourceTick = 8 - dotsAgo;
            assertEquals((sourceTick & 1) == 0 ? 8 : 16,
                    restored.getOamSpriteHeight(dotsAgo));
        }
    }

    private static void assertAlternatingHistory(Lcdc lcdc, int latestTick) {
        for (int dotsAgo = 0; dotsAgo < 8; dotsAgo++) {
            int sourceTick = latestTick - dotsAgo;
            assertEquals((sourceTick & 1) == 0 ? 8 : 16,
                    lcdc.getOamSpriteHeight(dotsAgo));
        }
    }
}
