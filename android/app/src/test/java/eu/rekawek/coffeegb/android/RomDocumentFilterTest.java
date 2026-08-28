package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RomDocumentFilterTest {

    @Test
    public void acceptsExactlyAndroidSupportedRomAndArchiveExtensions() {
        assertTrue(RomDocumentFilter.accepts("game.gb"));
        assertTrue(RomDocumentFilter.accepts("game.GBC"));
        assertTrue(RomDocumentFilter.accepts("multi.part.rom"));
        assertTrue(RomDocumentFilter.accepts("games.ZIP"));

        assertFalse(RomDocumentFilter.accepts("game.sav"));
        assertFalse(RomDocumentFilter.accepts("game.sn0"));
        assertFalse(RomDocumentFilter.accepts("game.cgbstate"));
        assertFalse(RomDocumentFilter.accepts("game.7z"));
        assertFalse(RomDocumentFilter.accepts("gbc"));
        assertFalse(RomDocumentFilter.accepts(null));
    }
}
