package eu.rekawek.coffeegb.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import eu.rekawek.coffeegb.android.menu.MenuRenderer;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.ui.menu.MenuRoute;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Real-Paint smoke coverage for every common-template route in both Android layouts. */
@RunWith(AndroidJUnit4.class)
public class AllRouteRendererAndroidTest {

    @Test
    public void allRoutesRenderClippedInPortraitAndLandscape() {
        int[] previewPixels = new int[16 * 96];
        java.util.Arrays.fill(previewPixels, Color.BLACK);
        MenuPreview readyPaper = MenuPreview.ready(16, 96, previewPixels);
        List<MenuPageSpec> pages = pages(readyPaper);
        assertEquals(MenuRoute.values().length, pages.size());
        for (int index = 0; index < pages.size(); index++) {
            assertEquals(MenuRoute.values()[index], pages.get(index).route());
        }

        for (MenuPageSpec page : pages) {
            MenuPresentation presentation = presentation(page);
            renderAndAssertClipped(presentation, 620, 960);
            renderAndAssertClipped(presentation, 960, 620);
        }
        Bundle arguments = InstrumentationRegistry.getArguments();
        if ("true".equals(arguments.getString("captureMenuRoutes"))) {
            writeContactSheet(pages, true);
            writeContactSheet(pages, false);
        }
    }

    private static List<MenuPageSpec> pages(MenuPreview preview) {
        ArrayList<MenuPageSpec> pages = new ArrayList<>();
        pages.add(page(MenuRoute.PAUSE_CONSOLE, "PAUSE", buttons("pause", 7), preview));
        pages.add(page(MenuRoute.SAVE_STATES, "SAVE STATES", buttons("slot", 10), preview));
        pages.add(AndroidMenuModel.recentGamesPage(List.of(new MenuPageSpec.RecentGame(
                "recent", "RECENT GAME", "TODAY", true, preview)), "recent"));
        pages.add(AndroidMenuModel.settingsPage());
        pages.add(AndroidMenuModel.audioPage(AndroidMenuModel.audioDraft(100, true)));
        pages.add(AndroidMenuModel.displayPage(true, false));
        pages.add(AndroidMenuModel.touchPage(AndroidMenuModel.resetTouchDraft(), true));
        pages.add(AndroidMenuModel.controllerPage("PHYSICAL BLUETOOTH GAME CONTROLLER",
                Map.of(Button.A, "BUTTON A", Button.B, "BUTTON B"), null, false));
        pages.add(AndroidMenuModel.optionalDevicesPage(
                "rear", "auto", true, true, List.of()));
        pages.add(AndroidMenuModel.optionPickerPage("OPTION PICKER", List.of(
                new AndroidMenuModel.ChoiceValue("one", "ONE"),
                new AndroidMenuModel.ChoiceValue("two", "TWO"),
                new AndroidMenuModel.ChoiceValue("three", "THREE"),
                new AndroidMenuModel.ChoiceValue("four", "FOUR"),
                new AndroidMenuModel.ChoiceValue("five", "FIVE"),
                new AndroidMenuModel.ChoiceValue("six", "SIX"),
                new AndroidMenuModel.ChoiceValue("seven", "SEVEN"),
                new AndroidMenuModel.ChoiceValue("eight", "EIGHT")), "one"));
        pages.add(AndroidMenuModel.printerPaperPage(preview,
                "BOUNDED PREVIEW / FULL EXPORT"));
        pages.add(AndroidMenuModel.dataMediaPage(new AndroidMenuModel.TransferAvailability(
                false, true, "SAVE FLUSH PENDING")));
        pages.add(AndroidMenuModel.libraryPage(true));
        pages.add(page(MenuRoute.CHOOSE_ROM, "CHOOSE ROM", buttons("rom", 3), preview));
        pages.add(AndroidMenuModel.systemPage("execution-mode"));
        pages.add(AndroidMenuModel.aboutPage("2026.08.11-LONG-VERSION",
                "NO BROWSER AVAILABLE"));
        pages.add(page(MenuRoute.CONFIRM_ACTION, "CONFIRM", buttons("confirm", 2), preview));
        return List.copyOf(pages);
    }

    private static MenuPageSpec page(MenuRoute route, String title,
            List<MenuPageSpec.Item> items, MenuPreview preview) {
        return new MenuPageSpec(route, "COFFEE GB", title, "", "COMMON TEMPLATE",
                List.of("ANDROID RENDERER"), items, 1,
                List.of("D-PAD MOVE", "A CHOOSE", "B BACK"), items.get(0).id(), preview);
    }

    private static List<MenuPageSpec.Item> buttons(String prefix, int count) {
        ArrayList<MenuPageSpec.Item> items = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            items.add(MenuPageSpec.Item.button(prefix + "-" + index,
                    prefix.toUpperCase(java.util.Locale.US) + " " + index, "", true));
        }
        return List.copyOf(items);
    }

    private static MenuPresentation presentation(MenuPageSpec page) {
        MenuController controller = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation presentation) {
            }

            @Override
            public void onItemSelected(MenuRoute route, String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(MenuRoute route) {
            }
        });
        controller.setPage(page);
        controller.show(page.route());
        return controller.presentation();
    }

    private static void renderAndAssertClipped(MenuPresentation presentation,
            int width, int height) {
        int untouched = Color.MAGENTA;
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(untouched);
        RectF bounds = new RectF(20, 20, width - 20, height - 20);
        new MenuRenderer().draw(new Canvas(bitmap), presentation, bounds);

        assertEquals(presentation.route() + " outside top", untouched, bitmap.getPixel(10, 10));
        assertEquals(presentation.route() + " outside bottom", untouched,
                bitmap.getPixel(width - 10, height - 10));
        assertNotEquals(presentation.route() + " content", untouched,
                bitmap.getPixel(width / 2, height / 2));
        bitmap.recycle();
    }

    private static void writeContactSheet(List<MenuPageSpec> pages, boolean portrait) {
        int columns = 5;
        int rows = (pages.size() + columns - 1) / columns;
        int cellWidth = portrait ? 300 : 480;
        int cellHeight = portrait ? 400 : 320;
        Bitmap sheet = Bitmap.createBitmap(cellWidth * columns, cellHeight * rows,
                Bitmap.Config.ARGB_8888);
        sheet.eraseColor(Color.DKGRAY);
        Canvas canvas = new Canvas(sheet);
        MenuRenderer renderer = new MenuRenderer();
        for (int index = 0; index < pages.size(); index++) {
            int column = index % columns;
            int row = index / columns;
            RectF bounds = new RectF(column * cellWidth + 4, row * cellHeight + 4,
                    (column + 1) * cellWidth - 4, (row + 1) * cellHeight - 4);
            renderer.draw(canvas, presentation(pages.get(index)), bounds);
        }
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null);
        File destination = new File(directory,
                portrait ? "common-menu-all-routes-portrait.png"
                        : "common-menu-all-routes-landscape.png");
        try (FileOutputStream output = new FileOutputStream(destination)) {
            if (!sheet.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                throw new AssertionError("Could not encode " + destination);
            }
        } catch (IOException failure) {
            throw new AssertionError(failure);
        } finally {
            sheet.recycle();
        }
    }
}
