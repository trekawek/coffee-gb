package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.io.InputStream;
import java.util.Objects;

/**
 * Immutable metadata for one packaged canonical Proposal 3 menu crop.
 *
 * <p>The packaged image is already the complete 924x736 visible screen. The original 1672x941
 * composition and its {@link #sourceVisibleCrop()} are retained only as provenance. The portable
 * module owns the future decode/composition contract; platform adapters consume the resulting
 * ARGB pixels and map input through {@link MenuViewport}.
 */
public final class MenuArtwork {

    private final MenuRoute route;
    private final String sourceFilename;
    private final String resourcePath;
    private final MenuRect sourceVisibleCrop;

    MenuArtwork(MenuRoute route, String sourceFilename, String resourcePath, MenuRect sourceVisibleCrop) {
        this.route = Objects.requireNonNull(route, "route");
        this.sourceFilename = Objects.requireNonNull(sourceFilename, "sourceFilename");
        this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
        this.sourceVisibleCrop = Objects.requireNonNull(sourceVisibleCrop, "sourceVisibleCrop");
        if (sourceFilename.isEmpty()) {
            throw new IllegalArgumentException("sourceFilename must not be empty");
        }
        if (resourcePath.isEmpty() || resourcePath.charAt(0) != '/') {
            throw new IllegalArgumentException("Artwork resource paths must be absolute classpath paths");
        }
    }

    public MenuRoute route() {
        return route;
    }

    /** Returns the original Proposal 3 source filename associated with this crop. */
    public String sourceFilename() {
        return sourceFilename;
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

    /* Fixture-only access; production runtime paths belong to the later compositor integration. */
    String resourcePath() {
        return resourcePath;
    }

    /* Fixture-only access; callers receive a 924x736 image and must close the stream. */
    InputStream openStream() {
        InputStream stream = MenuArtwork.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IllegalStateException("Missing menu artwork resource: " + resourcePath);
        }
        return stream;
    }
}
