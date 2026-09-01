package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Package-private decoder for the audited common text-free Proposal 3 template. */
final class Proposal3TemplateFrameCatalog {

    private static final String RESOURCE_ROOT =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates/";

    private Proposal3TemplateFrameCatalog() {
    }

    static MenuArgbFrame decode(MenuRoute route) throws IOException {
        String resourcePath = resourcePath(route);
        InputStream stream = Proposal3TemplateFrameCatalog.class.getResourceAsStream(resourcePath);
        if (stream == null) {
            throw new IOException("Missing common Proposal 3 text-free template: " + resourcePath);
        }
        try (InputStream input = stream) {
            return validatePackagedDimensions(route, PngArgbDecoder.decode(input));
        }
    }

    static String resourcePath(MenuRoute route) {
        Objects.requireNonNull(route, "route");
        return RESOURCE_ROOT + MenuArtworkCatalog.artwork(route).templateFilename();
    }

    static MenuArgbFrame validatePackagedDimensions(MenuRoute route, MenuArgbFrame frame)
            throws IOException {
        Objects.requireNonNull(route, "route");
        Objects.requireNonNull(frame, "frame");
        if (frame.width() != MenuArtworkCatalog.PACKAGED_WIDTH
                || frame.height() != MenuArtworkCatalog.PACKAGED_HEIGHT) {
            throw new IOException("Unexpected Proposal 3 template dimensions for " + route
                    + ": " + frame.width() + "x" + frame.height());
        }
        return frame;
    }
}
