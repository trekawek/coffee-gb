package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.state.StateCatalogEntry;
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import java.time.Instant;

/** Immutable, path-free state-slot row for Android UI presentation. */
record AndroidStateSlot(int index, String detail, boolean loadable, MenuPreview preview,
        Instant savedAt) {

    AndroidStateSlot {
        if (preview == null) {
            preview = MenuPreview.empty();
        }
    }

    static AndroidStateSlot from(int index, StateCatalogEntry entry) {
        return from(index, entry, MenuPreview.empty());
    }

    static AndroidStateSlot from(int index, StateCatalogEntry entry, MenuPreview preview) {
        if (entry == null) {
            return new AndroidStateSlot(index, "Empty", false, MenuPreview.empty(), null);
        }
        StateCatalogStatus status = entry.getStatus();
        if (status != StateCatalogStatus.AVAILABLE) {
            String reason = entry.getDetail();
            return new AndroidStateSlot(index,
                    reason == null || reason.isBlank() ? "Unavailable: " + status : reason, false,
                    MenuPreview.empty(), null);
        }
        Instant savedAt = entry.getMetadata() == null ? null : entry.getMetadata().getSavedAt();
        String saved = savedAt == null ? "Saved state" : "Saved " + savedAt;
        return new AndroidStateSlot(index, saved, true, preview, savedAt);
    }

    String label() {
        return "Slot " + index + ": " + detail;
    }
}
