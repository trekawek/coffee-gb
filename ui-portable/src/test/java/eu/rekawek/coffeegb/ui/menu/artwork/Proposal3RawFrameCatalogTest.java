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
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class Proposal3RawFrameCatalogTest {

    private static final Map<MenuRoute, String> EXPECTED_FILENAMES = Map.ofEntries(
            Map.entry(MenuRoute.PAUSE_CONSOLE, "00-pause-console.png"),
            Map.entry(MenuRoute.SAVE_STATES, "01-save-states.png"),
            Map.entry(MenuRoute.RECENT_GAMES, "16-recent-games.png"),
            Map.entry(MenuRoute.SETTINGS, "02-settings.png"),
            Map.entry(MenuRoute.AUDIO, "03-audio.png"),
            Map.entry(MenuRoute.DISPLAY, "14-display.png"),
            Map.entry(MenuRoute.TOUCH_CONTROLS, "04-touch-controls.png"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING, "05-controller-mapping.png"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES, "06-optional-devices.png"),
            Map.entry(MenuRoute.OPTION_PICKER, "15-option-picker.png"),
            Map.entry(MenuRoute.DATA_MEDIA, "07-data-media.png"),
            Map.entry(MenuRoute.LIBRARY, "08-library.png"),
            Map.entry(MenuRoute.FILE_BROWSER, "full-width-menu-frame.png"),
            Map.entry(MenuRoute.CHOOSE_ROM, "09-choose-rom.png"),
            Map.entry(MenuRoute.SYSTEM, "10-system.png"),
            Map.entry(MenuRoute.ABOUT, "11-about.png"),
            Map.entry(MenuRoute.CONFIRM_ACTION, "12-confirm-action.png"),
            Map.entry(MenuRoute.PRINTER_PAPER, "13-printer-paper.png"));

    private static final Map<MenuRoute, String> EXPECTED_PNG_SHA256 = Map.ofEntries(
            Map.entry(MenuRoute.PAUSE_CONSOLE,
                    "4a5290606e9996f8a809fc7ae5884392ad156a0b83c11c354db531993c188ee0"),
            Map.entry(MenuRoute.SAVE_STATES,
                    "54604ecec4c6d82136df85baf2d147956a69bd0bf9f0d387e46ac03bcde2e5c3"),
            Map.entry(MenuRoute.RECENT_GAMES,
                    "54604ecec4c6d82136df85baf2d147956a69bd0bf9f0d387e46ac03bcde2e5c3"),
            Map.entry(MenuRoute.SETTINGS,
                    "2147214dfe918aed51772d24bd80b37c18c0e4a46daa2d52fb2a7fcdfb70b01d"),
            Map.entry(MenuRoute.AUDIO,
                    "c53859fd02cc7240e4bfcb3c62f206e0fe2a3f05a1de46d582375ff739bcd8f2"),
            Map.entry(MenuRoute.DISPLAY,
                    "0275726843612152d073142a94b0fa8b36be83a07bd1ef52449885c547231fd9"),
            Map.entry(MenuRoute.TOUCH_CONTROLS,
                    "6a7a6c34c81ef7e074ec287e0de7ea52ea8eba0d6c6ad0c689b98b1d8a989a8f"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING,
                    "f623f59f23c57efc1cbfa362d3f75efea0d5d06af6a268097e5ce00d4685cabd"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES,
                    "8be519127d8dd026b69e6c7dcc966348fabd3fbe38b75b282768b25d4adafb80"),
            Map.entry(MenuRoute.OPTION_PICKER,
                    "2147214dfe918aed51772d24bd80b37c18c0e4a46daa2d52fb2a7fcdfb70b01d"),
            Map.entry(MenuRoute.DATA_MEDIA,
                    "bd05587acebe48c7fdeb8ce0811012cd56c7a40b2520310768a8d1ffad84d06d"),
            Map.entry(MenuRoute.LIBRARY,
                    "4395062535e3558c82f0bd28bb20c658c35d4f7874aa7c6b20c37775e9d5809b"),
            Map.entry(MenuRoute.FILE_BROWSER,
                    "c7fc759e8b23a10558ff1d400de69e3e80cb3bd7a46ab4092995bfc6fd9de075"),
            Map.entry(MenuRoute.CHOOSE_ROM,
                    "0660216b920eff3cf41bc0564a0e77eb38b58cb23c4c64dd7b37102625b86fb2"),
            Map.entry(MenuRoute.SYSTEM,
                    "0275726843612152d073142a94b0fa8b36be83a07bd1ef52449885c547231fd9"),
            Map.entry(MenuRoute.ABOUT,
                    "2d15c8ac18b6dc936855fea668350976e95bf6ca1431f16994dc473dd2da66b9"),
            Map.entry(MenuRoute.CONFIRM_ACTION,
                    "d950227a270e971fe2749e0520340c9e467595047608563633f658d36928b12f"),
            Map.entry(MenuRoute.PRINTER_PAPER,
                    "5d0601a3d91a3550c5a9eb0312eab497c700633e6b9a28726826cd013499b3aa"));

    /* SHA-256 of row-major bytes [A, R, G, B] for each decoded frame. */
    private static final Map<MenuRoute, String> EXPECTED_ARGB_SHA256 = Map.ofEntries(
            Map.entry(MenuRoute.PAUSE_CONSOLE,
                    "3af63a8088dc02fe5969cd0697c3b9a5c4467838c5f043eb2276196f15a85d93"),
            Map.entry(MenuRoute.SAVE_STATES,
                    "0f7a943b3e06fb03f775fa4ed682ad4e5f42ce460b009d519e1ae36f1c7ceccd"),
            Map.entry(MenuRoute.RECENT_GAMES,
                    "0f7a943b3e06fb03f775fa4ed682ad4e5f42ce460b009d519e1ae36f1c7ceccd"),
            Map.entry(MenuRoute.SETTINGS,
                    "6316813b576d758fcecf25131956ca28665e0faf2cd9e31f587c82fc2df7803a"),
            Map.entry(MenuRoute.AUDIO,
                    "8f952da794d14dfab890b01eb353f422b677722270f766bbd6440d2c7d89bcf4"),
            Map.entry(MenuRoute.DISPLAY,
                    "a417d5f353783e154fd39297f54ecff1e75bba4eff799695620e7e079810cd48"),
            Map.entry(MenuRoute.TOUCH_CONTROLS,
                    "543637b08ea63d96a8da024614ac2160c3073afa8e88d1f0013a810fc3595680"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING,
                    "8f6129923780caad80b6648f7d2604c48d549ce9459292ae297fe3f170ed0225"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES,
                    "7bc7c9189221053a16671b873a58f600e961d9c416cb4364c61abd877cf77d48"),
            Map.entry(MenuRoute.OPTION_PICKER,
                    "6316813b576d758fcecf25131956ca28665e0faf2cd9e31f587c82fc2df7803a"),
            Map.entry(MenuRoute.DATA_MEDIA,
                    "f2bff9bbe9011fcffb191d374eac4d8e3d2436a11eec59b1ef8bd402f400d0f1"),
            Map.entry(MenuRoute.LIBRARY,
                    "765484db5aeb03e7122e8eb728a55d05a2bbd44e4f4281a11ffedd913d991084"),
            Map.entry(MenuRoute.FILE_BROWSER,
                    "530a8c478c5df38197a7ea14866a6513e4e0e49460fd8151e15816e93eb795fb"),
            Map.entry(MenuRoute.CHOOSE_ROM,
                    "a97a9e4af5a765da80e3883133d1e91250b724173081c11b6d6c4c76047ada9d"),
            Map.entry(MenuRoute.SYSTEM,
                    "a417d5f353783e154fd39297f54ecff1e75bba4eff799695620e7e079810cd48"),
            Map.entry(MenuRoute.ABOUT,
                    "2c877b80e285d7ec8b548cd403e25bd35a7b39be1d6aa86613e024ccca7e87c5"),
            Map.entry(MenuRoute.CONFIRM_ACTION,
                    "103197c2453e22b670c723bf2be893811621bfcf29140f2cb96d264f2a76a9a0"),
            Map.entry(MenuRoute.PRINTER_PAPER,
                    "3a1d090dc70fa8ec7b626741f36180ff6afdab3474ff7b5bb61456cfcef2e500"));

    @Test
    public void everyRouteHasOneRawPathAndOnDemandDecode() throws Exception {
        Set<String> paths = new HashSet<>();
        for (MenuRoute route : MenuRoute.values()) {
            String path = Proposal3RawFrameCatalog.resourcePath(route);
            assertTrue(paths.add(path));
            String expectedRoot = route == MenuRoute.FILE_BROWSER
                    ? "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates/"
                    : "/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw/";
            assertEquals(expectedRoot + EXPECTED_FILENAMES.get(route), path);

            byte[] encoded;
            try (InputStream stream = Proposal3RawFrameCatalog.class.getResourceAsStream(path)) {
                assertTrue(stream != null);
                encoded = readAll(stream);
            }
            assertEquals(EXPECTED_PNG_SHA256.get(route), sha256(encoded));

            MenuArgbFrame first = Proposal3RawFrameCatalog.decode(route);
            MenuArgbFrame second = Proposal3RawFrameCatalog.decode(route);
            assertNotSame(first, second);
            assertEquals(924, first.width());
            assertEquals(736, first.height());
            assertEquals(EXPECTED_ARGB_SHA256.get(route), sha256Argb(first.copyPixels()));
            for (int pixel : first.copyPixels()) {
                assertEquals(0xff, pixel >>> 24);
            }
        }
        assertEquals(MenuRoute.values().length, paths.size());
    }

    @Test
    public void rejectsDecodedFramesWithUnexpectedPackagedDimensions() throws Exception {
        MenuArgbFrame valid = new MenuArgbFrame(MenuArtworkCatalog.PACKAGED_WIDTH,
                MenuArtworkCatalog.PACKAGED_HEIGHT,
                new int[MenuArtworkCatalog.PACKAGED_WIDTH * MenuArtworkCatalog.PACKAGED_HEIGHT]);
        assertSame(valid, Proposal3RawFrameCatalog.validatePackagedDimensions(
                MenuRoute.PAUSE_CONSOLE, valid));

        try {
            Proposal3RawFrameCatalog.validatePackagedDimensions(
                    MenuRoute.PAUSE_CONSOLE, new MenuArgbFrame(1, 1, new int[1]));
            fail("Expected unexpected packaged dimensions to be rejected");
        } catch (IOException expected) {
            // Expected malformed decoded frame.
        }
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
        return hex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String sha256Argb(int[] pixels) throws Exception {
        byte[] bytes = new byte[pixels.length * 4];
        for (int index = 0; index < pixels.length; index++) {
            int pixel = pixels[index];
            int offset = index * 4;
            bytes[offset] = (byte) (pixel >>> 24);
            bytes[offset + 1] = (byte) (pixel >>> 16);
            bytes[offset + 2] = (byte) (pixel >>> 8);
            bytes[offset + 3] = (byte) pixel;
        }
        return sha256(bytes);
    }

    private static String hex(byte[] bytes) {
        char[] alphabet = "0123456789abcdef".toCharArray();
        char[] result = new char[bytes.length * 2];
        for (int index = 0; index < bytes.length; index++) {
            int value = bytes[index] & 0xff;
            result[index * 2] = alphabet[value >>> 4];
            result[index * 2 + 1] = alphabet[value & 0x0f];
        }
        return new String(result);
    }
}
