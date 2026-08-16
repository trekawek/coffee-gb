package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class Proposal3TemplateFrameCatalogTest {

    private static final Map<MenuRoute, String> EXPECTED_TEMPLATE_PNG_SHA256 = Map.ofEntries(
            Map.entry(MenuRoute.PAUSE_CONSOLE,
                    "bd1721e79d2f7e701a79643a39f572c654195aff15503159cf854d75b722b044"),
            Map.entry(MenuRoute.SAVE_STATES,
                    "f7c22fa13f2ecc2d6f57f64c87de2d1af72e6185c8ff6c60d6fb97870cdce943"),
            Map.entry(MenuRoute.SETTINGS,
                    "bc3e688fa0a155bc5e7493f9d4d988f1245c59d9ded2e68896204dfb14596fbf"),
            Map.entry(MenuRoute.AUDIO,
                    "6954d94c6ffb565c724bc71d94e1fb220279ec57aa44e05a777b4dbef57a0138"),
            Map.entry(MenuRoute.TOUCH_CONTROLS,
                    "72600e3d26060d37c8a8d7f49c4ae719445ec71b591e6835209199c4f003eb42"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING,
                    "f63f936748721d92bafe8ab0e845fa79ae61c6b6cc1a3c085716f4c530374f60"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES,
                    "9d6905ea93562ae63894f843bea0e7a140de8f959d0dabd4cb816f24bb45cdd8"),
            Map.entry(MenuRoute.DATA_MEDIA,
                    "d4f1e25cff448338014405f2cc0358211bed7fb1b274b25b06f473da5bb54ab0"),
            Map.entry(MenuRoute.LIBRARY,
                    "a09b720e27a28f29f21939dfd89d469faed3ac78bb1378f44e5f75c9279366ea"),
            Map.entry(MenuRoute.CHOOSE_ROM,
                    "c1299934061703afe90e093d2059cc71fdacde87356a2518eb0f82154263f728"),
            Map.entry(MenuRoute.SYSTEM,
                    "398f7a9acdbf11af13a157f45c44ab7a3403ebb1135cb8962209c64c540591e4"),
            Map.entry(MenuRoute.ABOUT,
                    "f6064339d1cecfa6b8c5a705152c075f56a3539cd3f43563c65aa81d91f3ea47"),
            Map.entry(MenuRoute.CONFIRM_ACTION,
                    "e9f9ab6a0aec546a96ddebf9951d22dffbaa62f72e8639e734a151484ed211cf"),
            Map.entry(MenuRoute.PRINTER_PAPER,
                    "e8c98c2d15c6b8010cfd71c0cefb637ad1b144f4213f9862b12c94b0db876357"));

    @Test
    public void everyRouteMapsToItsRawFilenameAndDecodesToThePackagedSize() throws Exception {
        Set<String> templatePaths = new HashSet<>();
        assertEquals(14, MenuRoute.values().length);
        assertEquals(MenuRoute.values().length, EXPECTED_TEMPLATE_PNG_SHA256.size());

        for (MenuRoute route : MenuRoute.values()) {
            String rawPath = Proposal3RawFrameCatalog.resourcePath(route);
            String templatePath = Proposal3TemplateFrameCatalog.resourcePath(route);
            String rawFilename = filename(rawPath);
            String templateFilename = filename(templatePath);

            assertEquals(route + " changed the route filename", rawFilename, templateFilename);
            assertEquals(route + " changed the raw/template resource mapping",
                    rawPath.replace("/routes/raw/", "/routes/templates/"), templatePath);
            assertTrue(route + " has a duplicate template resource path",
                    templatePaths.add(templatePath));
            assertNotNull(route + " template resource is missing",
                    Proposal3TemplateFrameCatalog.class.getResource(templatePath));
            try (InputStream stream = Proposal3TemplateFrameCatalog.class
                    .getResourceAsStream(templatePath)) {
                assertNotNull(route + " template resource stream is missing", stream);
                assertEquals(route + " template PNG SHA-256",
                        EXPECTED_TEMPLATE_PNG_SHA256.get(route), sha256(readAll(stream)));
            }

            MenuArgbFrame frame = Proposal3TemplateFrameCatalog.decode(route);
            assertEquals(route + " template width", 924, frame.width());
            assertEquals(route + " template height", 736, frame.height());
        }

        assertEquals(MenuRoute.values().length, templatePaths.size());
    }

    private static String filename(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            int value = digest[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }
}
