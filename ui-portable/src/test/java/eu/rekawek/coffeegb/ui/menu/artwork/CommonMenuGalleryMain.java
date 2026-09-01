package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/** Manual visual-QA utility that renders every portable route through the common template. */
public final class CommonMenuGalleryMain {

    private CommonMenuGalleryMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: CommonMenuGalleryMain <output-directory>");
        }
        File directory = new File(args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("Unable to create output directory " + directory);
        }
        MenuController controller = new MenuController(new NoopListener());
        Proposal3MenuCompositor compositor = new Proposal3MenuCompositor();
        for (MenuRoute route : MenuRoute.values()) {
            controller.show(route);
            MenuArgbFrame frame = compositor.compose(controller.presentation()).orElseThrow();
            BufferedImage image = new BufferedImage(frame.width(), frame.height(),
                    BufferedImage.TYPE_INT_ARGB);
            image.setRGB(0, 0, frame.width(), frame.height(), frame.copyPixels(), 0, frame.width());
            ImageIO.write(image, "png", new File(directory,
                    String.format("%02d-%s.png", route.ordinal(), route.name().toLowerCase())));
        }
    }

    private static final class NoopListener implements MenuController.Listener {
        @Override
        public void onPresentation(MenuPresentation presentation) {
        }

        @Override
        public void onItemSelected(MenuRoute route, String id, boolean secondary) {
        }

        @Override
        public void onHeaderSelected(MenuRoute route) {
        }
    }
}
