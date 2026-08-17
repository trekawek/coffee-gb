package eu.rekawek.coffeegb.android;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.os.Bundle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import eu.rekawek.coffeegb.ui.menu.MenuController;
import eu.rekawek.coffeegb.ui.menu.MenuPageSpec;
import eu.rekawek.coffeegb.ui.menu.MenuPresentation;
import eu.rekawek.coffeegb.ui.menu.MenuPreview;
import eu.rekawek.coffeegb.android.menu.MenuRenderer;
import eu.rekawek.coffeegb.core.joypad.Button;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/** Real-Paint smoke coverage for the complete Proposal 3 route tree in both layouts. */
@RunWith(AndroidJUnit4.class)
public class NineRouteRendererAndroidTest {

    @Test
    public void allNineRoutesRenderClippedInPortraitAndLandscape() {
        int[] previewPixels = new int[16 * 96];
        java.util.Arrays.fill(previewPixels, Color.BLACK);
        MenuPreview readyPaper = MenuPreview.ready(16, 96, previewPixels);
        List<MenuPageSpec> pages = List.of(
                AndroidMenuModel.settingsPage(true),
                AndroidMenuModel.audioPage(AndroidMenuModel.audioDraft(100, true)),
                AndroidMenuModel.touchPage(AndroidMenuModel.resetTouchDraft(), true),
                AndroidMenuModel.controllerPage("PHYSICAL BLUETOOTH GAME CONTROLLER",
                        Map.of(Button.A, "BUTTON A", Button.B, "BUTTON B"),
                        null, false),
                AndroidMenuModel.optionalDevicesPage(
                        new AndroidMenuModel.DevicesDraft(true, false, true),
                        "CAMERA DENIED / DISABLED", readyPaper),
                AndroidMenuModel.printerPaperPage(readyPaper,
                        "BOUNDED PREVIEW / FULL EXPORT"),
                AndroidMenuModel.systemPage("rewind-save-status"),
                AndroidMenuModel.dataMediaPage(new AndroidMenuModel.TransferAvailability(
                        false, true, "SAVE FLUSH PENDING")),
                AndroidMenuModel.aboutPage("2026.08.11-LONG-VERSION", "NO BROWSER AVAILABLE"));

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

    private static MenuPresentation presentation(MenuPageSpec page) {
        MenuController controller = new MenuController(new MenuController.Listener() {
            @Override
            public void onPresentation(MenuPresentation presentation) {
            }

            @Override
            public void onItemSelected(eu.rekawek.coffeegb.ui.menu.MenuRoute route,
                    String id, boolean secondary) {
            }

            @Override
            public void onHeaderSelected(eu.rekawek.coffeegb.ui.menu.MenuRoute route) {
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
        int cellWidth = portrait ? 360 : 640;
        int cellHeight = portrait ? 480 : 400;
        Bitmap sheet = Bitmap.createBitmap(cellWidth * 3, cellHeight * 3,
                Bitmap.Config.ARGB_8888);
        sheet.eraseColor(Color.DKGRAY);
        Canvas canvas = new Canvas(sheet);
        MenuRenderer renderer = new MenuRenderer();
        for (int index = 0; index < pages.size(); index++) {
            int column = index % 3;
            int row = index / 3;
            RectF bounds = new RectF(column * cellWidth + 4, row * cellHeight + 4,
                    (column + 1) * cellWidth - 4, (row + 1) * cellHeight - 4);
            renderer.draw(canvas, presentation(pages.get(index)), bounds);
        }
        File directory = InstrumentationRegistry.getInstrumentation().getTargetContext()
                .getExternalFilesDir(null);
        File destination = new File(directory,
                portrait ? "pr3-nine-routes-portrait.png" : "pr3-nine-routes-landscape.png");
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
