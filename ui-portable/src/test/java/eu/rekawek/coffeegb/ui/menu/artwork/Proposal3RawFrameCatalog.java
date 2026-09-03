package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Package-private raw reference input catalog; it is not a host-facing renderer or cache. */
final class Proposal3RawFrameCatalog {

    private static final String RESOURCE_ROOT =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw/";

    private Proposal3RawFrameCatalog() {
    }

    static MenuArgbFrame decode(MenuRoute route) throws IOException {
        String resourcePath = resourcePath(route);
        InputStream stream = Proposal3RawFrameCatalog.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Missing Proposal 3 raw frame: " + resourcePath);
        }
        try (InputStream input = stream) {
            return validatePackagedDimensions(route, PngArgbDecoder.decode(input));
        }
    }

    static String resourcePath(MenuRoute route) {
        Objects.requireNonNull(route, "route");
        if (route == MenuRoute.FILE_BROWSER) {
            return "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates/"
                    + MenuArtworkCatalog.FULL_WIDTH_TEMPLATE_FILENAME;
        }
        return RESOURCE_ROOT + MenuArtworkCatalog.artwork(route).sourceFilename();
    }

    static MenuArgbFrame validatePackagedDimensions(MenuRoute route, MenuArgbFrame frame)
            throws IOException {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(frame, "frame");
        if (frame.width() != MenuArtworkCatalog.PACKAGED_WIDTH
                || frame.height() != MenuArtworkCatalog.PACKAGED_HEIGHT) {
            throw new IOException("Unexpected Proposal 3 raw frame dimensions for " + route
                    + ": " + frame.width() + "x" + frame.height());
        }
        return frame;
    }
}
