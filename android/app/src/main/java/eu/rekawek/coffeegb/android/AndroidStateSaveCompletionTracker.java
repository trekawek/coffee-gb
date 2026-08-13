package eu.rekawek.coffeegb.android;

import java.util.HashMap;
import java.util.Map;

/**
 * Owner-thread bookkeeping for Activity callbacks attached to quick-state saves.
 *
 * <p>The controller coalesces superseded saves for the same slot and may not emit a terminal
 * event for the older request. Replacing a slot therefore must release the older callback before
 * retaining the newer one.</p>
 */
final class AndroidStateSaveCompletionTracker {

    private final Map<Long, Runnable> callbacks = new HashMap<>();
    private final Map<Long, Integer> slotsByRequest = new HashMap<>();
    private final Map<Integer, Long> latestBySlot = new HashMap<>();

    void register(int slot, long requestId, Runnable callback) {
        Long previous = latestBySlot.put(slot, requestId);
        if (previous != null) {
            callbacks.remove(previous);
            slotsByRequest.remove(previous);
        }
        slotsByRequest.put(requestId, slot);
        if (callback != null) {
            callbacks.put(requestId, callback);
        }
    }

    Runnable complete(long requestId) {
        Integer slot = slotsByRequest.remove(requestId);
        if (slot != null && Long.valueOf(requestId).equals(latestBySlot.get(slot))) {
            latestBySlot.remove(slot);
        }
        return callbacks.remove(requestId);
    }

    void clear() {
        callbacks.clear();
        slotsByRequest.clear();
        latestBySlot.clear();
    }

    int pendingCount() {
        return slotsByRequest.size();
    }
}
