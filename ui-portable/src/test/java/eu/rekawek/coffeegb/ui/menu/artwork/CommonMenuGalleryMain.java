package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

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
            write(compositor.compose(controller.presentation()).orElseThrow(), new File(directory,
                    String.format("%02d-%s.png", route.ordinal(), route.name().toLowerCase())));
        }

        ArrayList<MenuPageSpec.Item> slots = new ArrayList<>();
        for (int slot = 0; slot < 10; slot++) {
            slots.add(MenuPageSpec.Item.button("slot-" + slot, "SLOT " + slot,
                    slot == 9 ? "SAVED" : "", true));
        }
        controller.setPage(new MenuPageSpec(MenuRoute.SAVE_STATES, "SAVE STATES", "", "", "",
                List.of(), slots, 1, List.of("D-PAD MOVE", "A SAVE", "B BACK"), "slot-9",
                MenuPreview.empty()));
        controller.show(MenuRoute.SAVE_STATES);
        write(compositor.compose(controller.presentation()).orElseThrow(),
                new File(directory, "17-save_states_occupied.png"));

        controller.setPage(new MenuPageSpec(MenuRoute.AUDIO, "AUDIO", "", "", "",
                List.of(), List.of(
                        MenuPageSpec.Item.slider("volume", "VOLUME", "100%", true, 100),
                        MenuPageSpec.Item.checkbox("mute-audio", "MUTE", false, true)),
                1, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), "volume",
                MenuPreview.empty()));
        controller.show(MenuRoute.AUDIO);
        write(compositor.compose(controller.presentation()).orElseThrow(),
                new File(directory, "18-audio_100.png"));

        controller.setPage(new MenuPageSpec(MenuRoute.SYSTEM, "SYSTEM", "", "", "",
                List.of(), List.of(MenuPageSpec.Item.dropdown(
                        "bootstrap", "BOOTSTRAP", "FAST-FORWARD", true)),
                1, List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), "bootstrap",
                MenuPreview.empty()));
        controller.show(MenuRoute.SYSTEM);
        write(compositor.compose(controller.presentation()).orElseThrow(),
                new File(directory, "19-system_fast_forward.png"));
    }

    private static void write(MenuArgbFrame frame, File target) throws Exception {
        BufferedImage image = new BufferedImage(frame.width(), frame.height(),
                BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, frame.width(), frame.height(), frame.copyPixels(), 0, frame.width());
        ImageIO.write(image, "png", target);
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
