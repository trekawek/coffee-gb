package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.controller.state.StateCatalogEntry;
import eu.rekawek.coffeegb.controller.state.StateCatalogStatus;

/** Immutable, path-free state-slot row for Android UI presentation. */
record AndroidStateSlot(int index, String detail, boolean loadable) {

    static AndroidStateSlot from(int index, StateCatalogEntry entry) {
        if (entry == null) {
            return new AndroidStateSlot(index, "Empty", false);
        }
        StateCatalogStatus status = entry.getStatus();
        if (status != StateCatalogStatus.AVAILABLE) {
            String reason = entry.getDetail();
            return new AndroidStateSlot(index,
                    reason == null || reason.isBlank() ? "Unavailable: " + status : reason, false);
        }
        String saved = entry.getMetadata() == null
                ? "Saved state"
                : "Saved " + entry.getMetadata().getSavedAt();
        return new AndroidStateSlot(index, saved, true);
    }

    String label() {
        return "Slot " + index + ": " + detail;
    }
}
