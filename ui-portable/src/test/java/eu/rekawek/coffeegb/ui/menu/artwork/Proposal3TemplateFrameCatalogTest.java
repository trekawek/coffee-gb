package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import eu.rekawek.coffeegb.ui.menu.MenuPageLayout;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class Proposal3TemplateFrameCatalogTest {

    private static final String EXPECTED_TEMPLATE_PATH =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates/common-menu-frame.png";
    private static final String EXPECTED_TEMPLATE_PNG_SHA256 =
            "b8bacd0c2db9a996a8977c708f5daafe3036e02a44acb5f6f846b0e62d8eedb5";
    private static final String EXPECTED_FULL_WIDTH_TEMPLATE_PATH =
            "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates/full-width-menu-frame.png";
    private static final String EXPECTED_FULL_WIDTH_TEMPLATE_PNG_SHA256 =
            "c7fc759e8b23a10558ff1d400de69e3e80cb3bd7a46ab4092995bfc6fd9de075";

    @Test
    public void everyRouteMapsToItsLayoutTemplateAndDecodesToThePackagedSize() throws Exception {
        Set<String> templatePaths = new HashSet<>();
        assertEquals(18, MenuRoute.values().length);

        for (MenuRoute route : MenuRoute.values()) {
            String templatePath = Proposal3TemplateFrameCatalog.resourcePath(route);
            String expectedPath = route == MenuRoute.FILE_BROWSER
                    ? EXPECTED_FULL_WIDTH_TEMPLATE_PATH : EXPECTED_TEMPLATE_PATH;
            String expectedHash = route == MenuRoute.FILE_BROWSER
                    ? EXPECTED_FULL_WIDTH_TEMPLATE_PNG_SHA256 : EXPECTED_TEMPLATE_PNG_SHA256;
            assertEquals(route + " changed its template resource", expectedPath, templatePath);
            templatePaths.add(templatePath);
            assertNotNull(route + " template resource is missing",
                    Proposal3TemplateFrameCatalog.class.getResource(templatePath));
            try (InputStream stream = Proposal3TemplateFrameCatalog.class
                    .getResourceAsStream(templatePath)) {
                assertNotNull(route + " template resource stream is missing", stream);
                assertEquals(route + " template PNG SHA-256", expectedHash,
                        sha256(readAll(stream)));
            }

            MenuArgbFrame frame = Proposal3TemplateFrameCatalog.decode(route);
            assertEquals(route + " template width", 924, frame.width());
            assertEquals(route + " template height", 736, frame.height());
        }

        assertEquals(Set.of(EXPECTED_TEMPLATE_PATH, EXPECTED_FULL_WIDTH_TEMPLATE_PATH),
                templatePaths);

        assertEquals(EXPECTED_FULL_WIDTH_TEMPLATE_PATH,
                Proposal3TemplateFrameCatalog.resourcePath(MenuPageLayout.FULL_WIDTH_LIST));
        try (InputStream stream = Proposal3TemplateFrameCatalog.class
                .getResourceAsStream(EXPECTED_FULL_WIDTH_TEMPLATE_PATH)) {
            assertNotNull("full-width template resource is missing", stream);
            assertEquals(EXPECTED_FULL_WIDTH_TEMPLATE_PNG_SHA256, sha256(readAll(stream)));
        }
        MenuArgbFrame fullWidth = Proposal3TemplateFrameCatalog.decode(
                MenuPageLayout.FULL_WIDTH_LIST);
        assertEquals(924, fullWidth.width());
        assertEquals(736, fullWidth.height());
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
