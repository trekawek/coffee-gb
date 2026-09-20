package eu.rekawek.coffeegb.core.state.bess;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.sgb.Background;
import org.junit.Test;
import static org.junit.Assert.*;

public class BessBackgroundTest {
    @Test
    public void restoresSnesTilesMapAndPaletteWithoutDependingOnPublishedEvents() {
        Background background = new Background(EventBus.NULL_EVENT_BUS);
        byte[] tiles = new byte[0x2000];
        tiles[0] = (byte) 0x80; // Tile 0, pixel (0, 0), color 1.
        byte[] tilemap = new byte[0x800];
        tilemap[1] = 0x10; // Border palette 4.
        byte[] palettes = new byte[0x80];
        palettes[2] = 0x34;
        palettes[3] = 0x12;
        var border = background.restoreBessState(tiles, tilemap, palettes);
        assertEquals(1, border.mask()[0]);
        assertEquals(0x1234, border.buffer()[0]);
        assertEquals(0, border.mask()[1]);
        assertArrayEquals(tiles, background.captureBessTiles());
        assertArrayEquals(tilemap, background.captureBessTilemap());
        assertArrayEquals(palettes, background.captureBessPalettes());
        Background restored = new Background(EventBus.NULL_EVENT_BUS);
        restored.restoreState(background.captureState());
        assertArrayEquals(tilemap, restored.captureBessTilemap());
        assertArrayEquals(palettes, restored.captureBessPalettes());
    }

    @Test
    public void missingBorderUsesAnEmptyDefault() {
        Background background = new Background(EventBus.NULL_EVENT_BUS);
        assertNull(background.restoreBessState(new byte[0], new byte[0], new byte[0]));
        assertEquals(0, background.captureBessTilemap().length);
        assertEquals(0, background.captureBessPalettes().length);
    }
}
