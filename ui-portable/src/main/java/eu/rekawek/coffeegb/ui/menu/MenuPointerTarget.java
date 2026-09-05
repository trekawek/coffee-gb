package eu.rekawek.coffeegb.ui.menu;

import java.util.Objects;

/** A semantic target retained between pointer press and release, independent of host scaling. */
public record MenuPointerTarget(MenuRoute route, String itemId, MenuKey key) {

    public MenuPointerTarget {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(key, "key");
    }
}
