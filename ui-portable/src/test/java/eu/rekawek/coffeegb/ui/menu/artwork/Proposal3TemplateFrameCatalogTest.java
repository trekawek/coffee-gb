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
                    "aae4360ac3de01f5f18e5044dae3c20a0a122ec9555e62340fbb43a51c98683d"),
            Map.entry(MenuRoute.SAVE_STATES,
                    "be8790b26e3814659059215e9c42b2259a1bbbdf86884337baa7288afee08151"),
            Map.entry(MenuRoute.SETTINGS,
                    "dfa5c5c82c65a203344e22dce443b90ad059acda0b4d0d7af4e535259f06267a"),
            Map.entry(MenuRoute.AUDIO,
                    "988bb26099403903adb2b27388fcd7bcd9c8d77056e0573c4c1c6bb0bb911efd"),
            Map.entry(MenuRoute.TOUCH_CONTROLS,
                    "cecfe3ee6f06bf130293d1890be35f28af3efa121bdd736731632f986fd78bbf"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING,
                    "6c763d8f48251b42dbcd1ff836066ae485c9aa1e7e63b7e96a5e0d22cf66895c"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES,
                    "8ca5737565f4320499c6a5c173f1c705c8f9ce2b001dbdb917bcc6449e3b526f"),
            Map.entry(MenuRoute.DATA_MEDIA,
                    "125a4438c56f06fe358feb9fe0304132d314a9ce7ea8a3709f2f160ea07f7b7b"),
            Map.entry(MenuRoute.LIBRARY,
                    "563e3798feb1f74881460c46488f51b0abb8cacdf759a4b633b94c1c7bfaa4d3"),
            Map.entry(MenuRoute.CHOOSE_ROM,
                    "f50e8295d48ae8b0dfe382a2b08425a679a341703f9afb7c697089ee323da2e6"),
            Map.entry(MenuRoute.SYSTEM,
                    "37c98a566e800115ff41a50d4b3d6f5baef0ac088500efb650749ccfb5446855"),
            Map.entry(MenuRoute.ABOUT,
                    "84e300614ae269cadff1a77fb3f0e9d1081e9937dd627c0353894f669eaa3d30"),
            Map.entry(MenuRoute.CONFIRM_ACTION,
                    "80ec288d485e7d2547b886c0b027df48ba120c0bb13445498c8aaa1004f24130"),
            Map.entry(MenuRoute.PRINTER_PAPER,
                    "efe465bbca16b11ff2819aee64b4b67ca7cc8d9b57af4f1db7191ec10192d80a"));

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
