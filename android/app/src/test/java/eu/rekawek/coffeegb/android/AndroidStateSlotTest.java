package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.controller.state.StateCatalogEntry;
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus;
import eu.rekawek.coffeegb.controller.state.StateMetadata;
import eu.rekawek.coffeegb.controller.state.StateRef;
import org.junit.Test;

import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class AndroidStateSlotTest {

    @Test
    public void missingPortableEntryIsPresentedAsAnEmptyNonLoadableSlot() {
        AndroidStateSlot slot = AndroidStateSlot.from(3, null);

        assertEquals("Slot 3: Empty", slot.label());
        assertFalse(slot.loadable());
        assertSame(MenuPreview.empty(), slot.preview());
        assertNull(slot.savedAt());
    }

    @Test
    public void persistedPreviewRetainsItsDetachedImmutableImage() {
        int color = 0xff204060;
        MenuPreview preview = MenuPreview.ready(1, 1, new int[]{color});
        StateCatalogEntry entry = new StateCatalogEntry(
                new StateRef.Slot(1), StateCatalogStatus.AVAILABLE, null, null,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                null, null, null, null);

        AndroidStateSlot slot = AndroidStateSlot.from(1, entry, preview);

        assertEquals(true, slot.loadable());
        assertSame(preview, slot.preview());
        assertEquals(color, slot.preview().copyPixels()[0]);
    }

    @Test
    public void availableSlotCarriesItsValidatedCatalogSavedTime() {
        Instant savedAt = Instant.parse("2026-08-13T12:34:00Z");
        String hash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        StateMetadata metadata = new StateMetadata(new StateRef.Slot(2), null, savedAt,
                null, 128, hash, null);
        StateCatalogEntry entry = new StateCatalogEntry(
                new StateRef.Slot(2), StateCatalogStatus.AVAILABLE, null, null,
                hash, metadata, null, null, null);

        AndroidStateSlot slot = AndroidStateSlot.from(2, entry, MenuPreview.empty());

        assertEquals(savedAt, slot.savedAt());
        assertTrue(MainActivity.formatStateSavedAt(savedAt).startsWith("SAVED "));
    }
}
