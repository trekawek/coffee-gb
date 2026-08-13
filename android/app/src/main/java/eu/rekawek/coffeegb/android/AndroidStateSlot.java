package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.state.StateCatalogEntry;
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;

/** Immutable, path-free state-slot row for Android UI presentation. */
record AndroidStateSlot(int index, String detail, boolean loadable, MenuPreview preview) {

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
            return new AndroidStateSlot(index, "Empty", false, MenuPreview.empty());
        }
        StateCatalogStatus status = entry.getStatus();
        if (status != StateCatalogStatus.AVAILABLE) {
            String reason = entry.getDetail();
            return new AndroidStateSlot(index,
                    reason == null || reason.isBlank() ? "Unavailable: " + status : reason, false,
                    MenuPreview.empty());
        }
        String saved = entry.getMetadata() == null
                ? "Saved state"
                : "Saved " + entry.getMetadata().getSavedAt();
        return new AndroidStateSlot(index, saved, true, preview);
    }

    String label() {
        return "Slot " + index + ": " + detail;
    }
}
