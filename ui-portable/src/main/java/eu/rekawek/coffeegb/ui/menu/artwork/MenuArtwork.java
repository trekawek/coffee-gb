package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.util.Objects;

/**
 * Immutable metadata for one menu route and its shared packaged frame.
 *
 * <p>The original route-specific Proposal 3 filename and {@link #sourceVisibleCrop()} remain as
 * provenance and test-fixture metadata. Runtime composition starts from the common 924x736 frame
 * returned by {@link #templateFilename()}. Platform adapters consume the resulting ARGB pixels
 * and map input through {@link MenuViewport}.
 */
public final class MenuArtwork {

    private final MenuRoute route;
    private final String sourceFilename;
    private final MenuRect sourceVisibleCrop;

    MenuArtwork(MenuRoute route, String sourceFilename, MenuRect sourceVisibleCrop) {
        this.route = Objects.requireNonNull(route, "route");
        this.sourceFilename = Objects.requireNonNull(sourceFilename, "sourceFilename");
        this.sourceVisibleCrop = Objects.requireNonNull(sourceVisibleCrop, "sourceVisibleCrop");
        if (sourceFilename.isEmpty()) {
            throw new IllegalArgumentException("sourceFilename must not be empty");
        }
    }

    public MenuRoute route() {
        return route;
    }

    /** Returns the original Proposal 3 source filename associated with this crop. */
    public String sourceFilename() {
        return sourceFilename;
    }

    /** Returns the one common text-free frame used to compose every menu route. */
    public String templateFilename() {
        return MenuArtworkCatalog.COMMON_TEMPLATE_FILENAME;
    }

    /** Returns the crop coordinates in the original 1672x941 source composition. */
    public MenuRect sourceVisibleCrop() {
        return sourceVisibleCrop;
    }

    /** Returns the dimensions of the already-cropped packaged image. */
    public int packagedWidth() {
        return MenuArtworkCatalog.PACKAGED_WIDTH;
    }

    /** Returns the dimensions of the already-cropped packaged image. */
    public int packagedHeight() {
        return MenuArtworkCatalog.PACKAGED_HEIGHT;
    }
}
