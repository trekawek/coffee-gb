package eu.rekawek.coffeegb.android;

import eu.rekawek.coffeegb.core.joypad.Button;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Pointer-owned visual feedback, published as one immutable bit mask to the renderer. */
final class TouchPressState {
    private final Map<Integer, Integer> pointers = new HashMap<>();
    private volatile int mask;

    int mask() {
        return mask;
    }

    synchronized boolean update(int pointerId, Collection<Button> buttons) {
        int pointerMask = 0;
        for (Button button : buttons) {
            pointerMask |= bit(button);
        }
        if (pointerMask == 0) {
            pointers.remove(pointerId);
        } else {
            pointers.put(pointerId, pointerMask);
        }
        return publish();
    }

    synchronized boolean release(int pointerId) {
        pointers.remove(pointerId);
        return publish();
    }

    synchronized boolean clear() {
        pointers.clear();
        return publish();
    }

    static int bit(Button button) {
        return 1 << button.ordinal();
    }

    static boolean contains(int mask, Button button) {
        return (mask & bit(button)) != 0;
    }

    private boolean publish() {
        int next = 0;
        for (int pointerMask : pointers.values()) {
            next |= pointerMask;
        }
        if (mask == next) {
            return false;
        }
        mask = next;
        return true;
    }
}
