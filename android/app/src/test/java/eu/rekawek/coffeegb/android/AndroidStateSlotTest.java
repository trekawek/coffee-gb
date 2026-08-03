package eu.rekawek.coffeegb.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AndroidStateSlotTest {

    @Test
    public void missingPortableEntryIsPresentedAsAnEmptyNonLoadableSlot() {
        AndroidStateSlot slot = AndroidStateSlot.from(3, null);

        assertEquals("Slot 3: Empty", slot.label());
        assertFalse(slot.loadable());
    }
}
