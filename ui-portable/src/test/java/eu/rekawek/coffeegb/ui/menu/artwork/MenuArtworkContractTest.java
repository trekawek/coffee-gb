package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MenuArtworkContractTest {

    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'
    };

    private static final Map<MenuRoute, String> EXPECTED_FILENAMES = Map.ofEntries(
            Map.entry(MenuRoute.PAUSE_CONSOLE, "00-pause-console.png"),
            Map.entry(MenuRoute.SAVE_STATES, "01-save-states.png"),
            Map.entry(MenuRoute.SETTINGS, "02-settings.png"),
            Map.entry(MenuRoute.AUDIO, "03-audio.png"),
            Map.entry(MenuRoute.TOUCH_CONTROLS, "04-touch-controls.png"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING, "05-controller-mapping.png"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES, "06-optional-devices.png"),
            Map.entry(MenuRoute.DATA_MEDIA, "07-data-media.png"),
            Map.entry(MenuRoute.LIBRARY, "08-library.png"),
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
            Map.entry(MenuRoute.SETTINGS,
                    "2147214dfe918aed51772d24bd80b37c18c0e4a46daa2d52fb2a7fcdfb70b01d"),
            Map.entry(MenuRoute.AUDIO,
                    "c53859fd02cc7240e4bfcb3c62f206e0fe2a3f05a1de46d582375ff739bcd8f2"),
            Map.entry(MenuRoute.TOUCH_CONTROLS,
                    "6a7a6c34c81ef7e074ec287e0de7ea52ea8eba0d6c6ad0c689b98b1d8a989a8f"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING,
                    "f623f59f23c57efc1cbfa362d3f75efea0d5d06af6a268097e5ce00d4685cabd"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES,
                    "8be519127d8dd026b69e6c7dcc966348fabd3fbe38b75b282768b25d4adafb80"),
            Map.entry(MenuRoute.DATA_MEDIA,
                    "bd05587acebe48c7fdeb8ce0811012cd56c7a40b2520310768a8d1ffad84d06d"),
            Map.entry(MenuRoute.LIBRARY,
                    "4395062535e3558c82f0bd28bb20c658c35d4f7874aa7c6b20c37775e9d5809b"),
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

    /* SHA-256 of ffmpeg -vf crop=924:736:374:102 -f rawvideo -pix_fmt rgb24. */
    private static final Map<MenuRoute, String> EXPECTED_RAW_RGB_SHA256 = Map.ofEntries(
            Map.entry(MenuRoute.PAUSE_CONSOLE,
                    "7528e7c5f0b7a45fd7abe92d86d0e4187bd06195d7683a9ccb343c5469a7d162"),
            Map.entry(MenuRoute.SAVE_STATES,
                    "d874929badb1c9979d8ae5efc51a369744cf3771c04a8ccb1023303a8b63c87a"),
            Map.entry(MenuRoute.SETTINGS,
                    "855be49fc9d280e5e3cee79fa22d25726aa3b2c07e97b1e1bd7869451b06e98e"),
            Map.entry(MenuRoute.AUDIO,
                    "8592d8c1ec2452fe50ab7349ecf5e4bbee6aa6c907dc087fb040eab5d141128f"),
            Map.entry(MenuRoute.TOUCH_CONTROLS,
                    "413a46f1c55cdb5d9e47c2afc770de34579dd9451df14bf2b1b54f63667ae0a8"),
            Map.entry(MenuRoute.CONTROLLER_MAPPING,
                    "b8231ed6af6ce13d3c439e8fd720bb773ce8403889859bd357adcd5d8e93eaf5"),
            Map.entry(MenuRoute.OPTIONAL_DEVICES,
                    "0716094621a76e9dd0e8695cef6c47082dcc4978c4695b53696680a461ecd57c"),
            Map.entry(MenuRoute.DATA_MEDIA,
                    "6390e91b7c633553b9b24ff5870a01bc276a102d08ff9e894fd5074bcde09d7d"),
            Map.entry(MenuRoute.LIBRARY,
                    "158b8d81e0e92a13789af0fde57cafb28ef4c64f118ca6fb0537d9a2822976d0"),
            Map.entry(MenuRoute.CHOOSE_ROM,
                    "fd236047e29222824b07eb31086bbd69015cb5a64e33680ccba0ca9b8a057b95"),
            Map.entry(MenuRoute.SYSTEM,
                    "c687a08d0359e32b7eefc4860d9ae0f81cb9f890e35301c5f432bf66ca0d785a"),
            Map.entry(MenuRoute.ABOUT,
                    "36bb5a4d8d4e113d569ea47c9e5329214212f630099c450d9cfc65537c4ab03d"),
            Map.entry(MenuRoute.CONFIRM_ACTION,
                    "dde1bac79798f8bab52f708901538b07edc307042bb1cd4241caee150d788ae7"),
            Map.entry(MenuRoute.PRINTER_PAPER,
                    "e6600a553d5be20d96048ca75a9811e866226d5b08b25eef7df79112fae6c1cb"));

    @Test
    public void everyRouteMapsToOneUniqueCanonicalArtwork() {
        Map<MenuRoute, MenuArtwork> catalog = MenuArtworkCatalog.all();
        assertEquals(EnumSet.allOf(MenuRoute.class), catalog.keySet());
        assertEquals(MenuRoute.values().length, catalog.size());

        Set<String> sourceFilenames = new HashSet<>();
        for (MenuRoute route : MenuRoute.values()) {
            MenuArtwork artwork = catalog.get(route);
            assertNotNull(artwork);
            assertSame(artwork, MenuArtworkCatalog.artwork(route));
            assertEquals(route, artwork.route());
            assertTrue(sourceFilenames.add(artwork.sourceFilename()));
            assertEquals(EXPECTED_FILENAMES.get(route), artwork.sourceFilename());
            assertEquals(MenuArtworkCatalog.SOURCE_VISIBLE_CROP, artwork.sourceVisibleCrop());
            assertEquals(MenuArtworkCatalog.PACKAGED_WIDTH, artwork.packagedWidth());
            assertEquals(MenuArtworkCatalog.PACKAGED_HEIGHT, artwork.packagedHeight());
            assertEquals("/eu/rekawek/coffeegb/ui/menu/artwork/proposal3/source/"
                    + EXPECTED_FILENAMES.get(route), artwork.resourcePath());
        }
        assertEquals(MenuRoute.values().length, sourceFilenames.size());
    }

    @Test
    public void fixturePngsHaveExpectedHeadersBytesAndLosslessRawFrames() throws Exception {
        long totalBytes = 0;
        for (MenuRoute route : MenuRoute.values()) {
            MenuArtwork artwork = MenuArtworkCatalog.artwork(route);
            byte[] encoded;
            try (InputStream stream = artwork.openStream()) {
                assertNotNull(stream);
                encoded = readAll(stream);
            }
            totalBytes += encoded.length;
            assertEquals(EXPECTED_PNG_SHA256.get(route), sha256(encoded));

            PngFrame frame = decodePng(encoded);
            assertEquals(MenuArtworkCatalog.PACKAGED_WIDTH, frame.width());
            assertEquals(MenuArtworkCatalog.PACKAGED_HEIGHT, frame.height());
            assertEquals(8, frame.bitDepth());
            assertEquals(2, frame.colorType());
            assertEquals(0, frame.compressionMethod());
            assertEquals(0, frame.filterMethod());
            assertEquals(0, frame.interlaceMethod());
            assertEquals(924 * 736 * 3, frame.rgb().length);
            assertEquals(EXPECTED_RAW_RGB_SHA256.get(route), sha256(frame.rgb()));
        }
        assertTrue("test artwork fixtures exceed 14 MiB: " + totalBytes,
                totalBytes <= 14L * 1024L * 1024L);
    }

    @Test
    public void sourceGeometryAndPackagedGeometryAreDistinctAndPinned() {
        assertEquals(1672, MenuArtworkCatalog.SOURCE_WIDTH);
        assertEquals(941, MenuArtworkCatalog.SOURCE_HEIGHT);
        assertEquals(new MenuRect(374, 102, 924, 736), MenuArtworkCatalog.SOURCE_VISIBLE_CROP);
        assertEquals(new MenuRect(0, 0, 924, 736), MenuArtworkCatalog.PACKAGED_BOUNDS);
        assertEquals(1298, MenuArtworkCatalog.SOURCE_VISIBLE_CROP.right());
        assertEquals(838, MenuArtworkCatalog.SOURCE_VISIBLE_CROP.bottom());
        assertTrue(MenuArtworkCatalog.SOURCE_VISIBLE_CROP.right() <= MenuArtworkCatalog.SOURCE_WIDTH);
        assertTrue(MenuArtworkCatalog.SOURCE_VISIBLE_CROP.bottom() <= MenuArtworkCatalog.SOURCE_HEIGHT);
    }

    @Test
    public void integerViewportMatchesTheReviewVectors() {
        assertBounds(new MenuViewport(924, 736), 0, 0, 924, 736);
        assertBounds(new MenuViewport(1848, 1472), 0, 0, 1848, 1472);
        assertBounds(new MenuViewport(919, 717), 9, 0, 900, 717);
        assertBounds(new MenuViewport(758, 685), 0, 41, 758, 603);
        assertBounds(new MenuViewport(2400, 1080), 522, 0, 1355, 1080);
        assertBounds(new MenuViewport(1080, 2400), 0, 770, 1080, 860);

        MenuRect oddWidthRemainder = new MenuViewport(919, 717).contentBounds();
        assertEquals(9, oddWidthRemainder.x());
        assertEquals(10, 919 - oddWidthRemainder.right());
        MenuRect oddHeightRemainder = new MenuViewport(758, 685).contentBounds();
        assertEquals(41, oddHeightRemainder.y());
        assertEquals(41, 685 - oddHeightRemainder.bottom());
        MenuRect desktopRemainder = new MenuViewport(2400, 1080).contentBounds();
        assertEquals(522, desktopRemainder.x());
        assertEquals(523, 2400 - desktopRemainder.right());
    }

    @Test
    public void inverseMappingUsesHalfOpenEdgesAndRejectsBars() {
        MenuViewport viewport = new MenuViewport(758, 685);
        MenuRect bounds = viewport.contentBounds();

        assertTrue(viewport.containsView(bounds.x(), bounds.y()));
        assertTrue(viewport.containsView(bounds.right() - 1, bounds.bottom() - 1));
        assertFalse(viewport.containsView(bounds.right(), bounds.y()));
        assertFalse(viewport.containsView(bounds.x(), bounds.bottom()));
        assertFalse(viewport.containsView(0, bounds.y() - 1));

        assertTrue(viewport.viewToSource(bounds.x(), bounds.y()).isPresent());
        assertEquals(new MenuPoint(0.0, 0.0), viewport.viewToSource(bounds.x(), bounds.y()).get());
        assertFalse(viewport.viewToSource(bounds.right(), bounds.y()).isPresent());
        assertFalse(viewport.viewToSource(bounds.x(), bounds.bottom()).isPresent());
        assertFalse(viewport.viewToSource(0, bounds.y() - 1).isPresent());
        assertFalse(viewport.viewToSource(new MenuPoint(bounds.right(), bounds.y())).isPresent());
        assertFalse(viewport.viewToSource(new MenuPoint(bounds.x(), bounds.bottom())).isPresent());
        assertTrue(viewport.viewToSource(new MenuPoint(bounds.right() - 0.5, bounds.bottom() - 0.5))
                .isPresent());

        OptionalInt sourceX = viewport.sourceX(bounds.right() - 1);
        OptionalInt sourceY = viewport.sourceY(bounds.bottom() - 1);
        assertTrue(sourceX.isPresent());
        assertTrue(sourceY.isPresent());
        assertTrue(sourceX.getAsInt() < MenuViewport.SOURCE_WIDTH);
        assertTrue(sourceY.getAsInt() < MenuViewport.SOURCE_HEIGHT);
        assertFalse(viewport.sourceX(bounds.right()).isPresent());
        assertFalse(viewport.sourceY(bounds.bottom()).isPresent());
    }

    @Test
    public void integerForwardAndInverseMappingRoundTripsAtIntegerScale() {
        MenuViewport viewport = new MenuViewport(1848, 1472);
        for (int sourceX : new int[]{0, 1, 100, 923}) {
            for (int sourceY : new int[]{0, 1, 200, 735}) {
                MenuPoint viewPoint = viewport.sourceToView(sourceX, sourceY);
                Optional<MenuPoint> sourcePoint = viewport.viewToSource(
                        (int) viewPoint.x(), (int) viewPoint.y());
                assertTrue(sourcePoint.isPresent());
                assertEquals(sourceX, (int) sourcePoint.get().x());
                assertEquals(sourceY, (int) sourcePoint.get().y());
            }
        }
        assertEquals(new MenuPoint(1848.0, 1472.0), viewport.sourceToView(924, 736));
        assertEquals(new MenuPoint(0.0, 0.0), viewport.sourceToView(0, 0));
        expectIllegalArgument(() -> viewport.sourceToViewX(-1));
        expectIllegalArgument(() -> viewport.sourceToViewY(MenuViewport.SOURCE_HEIGHT + 1));
    }

    @Test
    public void viewportUsesLongCrossProductsForNearMaximumDimensions() {
        int maximum = Integer.MAX_VALUE;
        MenuViewport viewport = new MenuViewport(maximum, maximum);
        MenuRect actual = viewport.contentBounds();
        MenuRect expected = expectedBounds(maximum, maximum);
        assertEquals(expected, actual);
        assertTrue((long) actual.right() <= maximum);
        assertTrue((long) actual.bottom() <= maximum);
        assertTrue(actual.width() > 0);
        assertTrue(actual.height() > 0);
    }

    @Test
    public void productionClassesContainNoArtworkResources() throws Exception {
        Path classes = Paths.get(MenuArtwork.class.getProtectionDomain().getCodeSource()
                .getLocation().toURI());
        if (Files.isDirectory(classes)) {
            assertFalse(Files.exists(classes.resolve("eu/rekawek/coffeegb/ui/menu/artwork/proposal3")));
            assertFalse(Files.exists(classes.resolve("eu/rekawek/coffeegb/ui/artwork")));
        }
    }

    @Test
    public void publicArtworkApiIsPlatformNeutralAndHidesFixtureAccess() {
        assertEquals(0, MenuArtwork.class.getConstructors().length);
        for (Class<?> type : new Class<?>[]{
                MenuRect.class,
                MenuPoint.class,
                MenuArtwork.class,
                MenuArtworkCatalog.class,
                MenuViewport.class
        }) {
            assertNeutralSignature(type.getName());
            for (Constructor<?> constructor : type.getConstructors()) {
                assertNeutralSignature(constructor.toGenericString());
            }
            for (Method method : type.getMethods()) {
                assertNeutralSignature(method.toGenericString());
                assertNeutralSignature(method.getReturnType().getName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertNeutralSignature(parameterType.getName());
                }
            }
            for (Field field : type.getFields()) {
                assertNeutralSignature(field.toGenericString());
                assertNeutralSignature(field.getType().getName());
                assertFalse("fixture path leaked as public field", field.getName().contains("ROOT"));
            }
        }
    }

    private static void assertBounds(MenuViewport viewport, int x, int y, int width, int height) {
        MenuRect expected = new MenuRect(x, y, width, height);
        assertEquals(expected, viewport.contentBounds());
        assertSame(viewport.contentBounds(), viewport.destinationRect());
    }

    private static MenuRect expectedBounds(int viewWidth, int viewHeight) {
        long widthCrossProduct = (long) viewWidth * MenuViewport.SOURCE_HEIGHT;
        long heightCrossProduct = (long) viewHeight * MenuViewport.SOURCE_WIDTH;
        int width;
        int height;
        if (widthCrossProduct <= heightCrossProduct) {
            width = viewWidth;
            height = (int) (widthCrossProduct / MenuViewport.SOURCE_WIDTH);
        } else {
            height = viewHeight;
            width = (int) (heightCrossProduct / MenuViewport.SOURCE_HEIGHT);
        }
        return new MenuRect((viewWidth - width) / 2, (viewHeight - height) / 2, width, height);
    }

    private static void expectIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected IllegalArgumentException");
        } catch (IllegalArgumentException expected) {
            // Expected validation failure.
        }
    }

    private static void assertNeutralSignature(String signature) {
        assertFalse(signature, signature.contains("android."));
        assertFalse(signature, signature.contains("java.awt."));
        assertFalse(signature, signature.contains("javax.imageio."));
        assertFalse(signature, signature.contains("javafx."));
        assertFalse(signature, signature.contains("org."));
    }

    private static PngFrame decodePng(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            byte[] signature = new byte[PNG_SIGNATURE.length];
            input.readFully(signature);
            assertArrayEquals(PNG_SIGNATURE, signature);

            int width = -1;
            int height = -1;
            int bitDepth = -1;
            int colorType = -1;
            int compressionMethod = -1;
            int filterMethod = -1;
            int interlaceMethod = -1;
            ByteArrayOutputStream idat = new ByteArrayOutputStream();
            boolean sawIend = false;
            while (!sawIend) {
                int length = input.readInt();
                if (length < 0) {
                    throw new IOException("Negative PNG chunk length");
                }
                byte[] type = new byte[4];
                input.readFully(type);
                byte[] data = new byte[length];
                input.readFully(data);
                input.readInt();
                String chunkType = new String(type, java.nio.charset.StandardCharsets.US_ASCII);
                if ("IHDR".equals(chunkType)) {
                    if (data.length != 13) {
                        throw new IOException("Invalid IHDR length");
                    }
                    try (DataInputStream header = new DataInputStream(new ByteArrayInputStream(data))) {
                        width = header.readInt();
                        height = header.readInt();
                        bitDepth = header.readUnsignedByte();
                        colorType = header.readUnsignedByte();
                        compressionMethod = header.readUnsignedByte();
                        filterMethod = header.readUnsignedByte();
                        interlaceMethod = header.readUnsignedByte();
                    }
                } else if ("IDAT".equals(chunkType)) {
                    idat.write(data);
                } else if ("IEND".equals(chunkType)) {
                    sawIend = true;
                }
            }
            if (width <= 0 || height <= 0 || idat.size() == 0) {
                throw new IOException("PNG is missing IHDR or IDAT");
            }
            byte[] scanlines;
            try (InputStream inflater = new java.util.zip.InflaterInputStream(
                    new ByteArrayInputStream(idat.toByteArray()))) {
                scanlines = readAll(inflater);
            }
            int rowBytes = width * 3;
            if ((long) (rowBytes + 1) * height != scanlines.length) {
                throw new IOException("Unexpected PNG scanline size");
            }
            byte[] rgb = new byte[rowBytes * height];
            byte[] previous = new byte[rowBytes];
            byte[] current = new byte[rowBytes];
            int scanlineOffset = 0;
            for (int row = 0; row < height; row++) {
                int filter = scanlines[scanlineOffset++] & 0xff;
                for (int column = 0; column < rowBytes; column++) {
                    int filtered = scanlines[scanlineOffset++] & 0xff;
                    int left = column >= 3 ? current[column - 3] & 0xff : 0;
                    int above = previous[column] & 0xff;
                    int aboveLeft = column >= 3 ? previous[column - 3] & 0xff : 0;
                    int value;
                    switch (filter) {
                        case 0 -> value = filtered;
                        case 1 -> value = filtered + left;
                        case 2 -> value = filtered + above;
                        case 3 -> value = filtered + ((left + above) / 2);
                        case 4 -> value = filtered + paeth(left, above, aboveLeft);
                        default -> throw new IOException("Unsupported PNG filter: " + filter);
                    }
                    current[column] = (byte) value;
                }
                System.arraycopy(current, 0, rgb, row * rowBytes, rowBytes);
                byte[] swap = previous;
                previous = current;
                current = swap;
            }
            return new PngFrame(width, height, bitDepth, colorType, compressionMethod,
                    filterMethod, interlaceMethod, rgb);
        }
    }

    private static int paeth(int left, int above, int aboveLeft) {
        int predictor = left + above - aboveLeft;
        int leftDistance = Math.abs(predictor - left);
        int aboveDistance = Math.abs(predictor - above);
        int aboveLeftDistance = Math.abs(predictor - aboveLeft);
        if (leftDistance <= aboveDistance && leftDistance <= aboveLeftDistance) {
            return left;
        }
        if (aboveDistance <= aboveLeftDistance) {
            return above;
        }
        return aboveLeft;
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        char[] hex = "0123456789abcdef".toCharArray();
        char[] result = new char[digest.length * 2];
        for (int index = 0; index < digest.length; index++) {
            int value = digest[index] & 0xff;
            result[index * 2] = hex[value >>> 4];
            result[index * 2 + 1] = hex[value & 0x0f];
        }
        return new String(result);
    }

    private record PngFrame(
            int width,
            int height,
            int bitDepth,
            int colorType,
            int compressionMethod,
            int filterMethod,
            int interlaceMethod,
            byte[] rgb) {
    }
}
