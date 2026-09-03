package eu.rekawek.coffeegb.ui.menu.artwork;

import eu.rekawek.coffeegb.ui.menu.MenuRoute;
import org.junit.Test;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MenuArtworkRuntimeResourceTest {

    private static final long RUNTIME_ARTWORK_BUDGET = 2_600_000L;
    private static final String PROPOSAL3_ROOT =
            "eu/rekawek/coffeegb/ui/menu/artwork/proposal3";
    private static final String TEMPLATE_ROOT =
            "eu/rekawek/coffeegb/ui/menu/artwork/proposal3/templates";
    private static final Set<String> EXPECTED_TEMPLATE_FILES =
            Set.of("common-menu-frame.png", "full-width-menu-frame.png");
    private static final Set<String> EXPECTED_WIDGET_FILES = Set.of(
            "dark-widget.png", "paper-widget.png", "selected-widget.png");
    private static final Set<String> EXPECTED_ILLUSTRATION_FILES = Set.of(
            "about.png", "archive.png", "audio.png", "controller.png",
            "data-media.png", "library.png", "peripherals.png", "printer.png",
            "settings.png", "system.png", "touch-controls.png", "warning.png");

    @Test
    public void allProposal3ResourcesStayWithinBudget() throws Exception {
        Path classes = productionClasses();
        Path proposal3Root = classes.resolve(PROPOSAL3_ROOT);
        assertTrue(Files.isDirectory(proposal3Root));
        long bytes = 0;
        try (java.util.stream.Stream<Path> stream = Files.walk(proposal3Root)) {
            for (java.util.Iterator<Path> paths = stream.iterator(); paths.hasNext(); ) {
                Path path = paths.next();
                if (Files.isRegularFile(path)) {
                    bytes = Math.addExact(bytes, Files.size(path));
                }
            }
        }
        assertTrue("runtime menu artwork exceeds 2,600,000 bytes: " + bytes,
                bytes <= RUNTIME_ARTWORK_BUDGET);
    }

    @Test
    public void templateDirectoryContainsExactlyTheSplitAndFullWidthFrames() throws Exception {
        Path templateRoot = productionClasses().resolve(TEMPLATE_ROOT);
        assertTrue(Files.isDirectory(templateRoot));
        List<Path> entries;
        try (java.util.stream.Stream<Path> stream = Files.list(templateRoot)) {
            entries = stream.sorted().collect(Collectors.toList());
        }
        assertFalse("baked-text raw routes must not ship at runtime",
                Files.exists(productionClasses().resolve(
                        "eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw")));
        assertFalse("obsolete route templates must not ship at runtime",
                Files.exists(productionClasses().resolve(
                        "eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/templates")));
        assertEquals(EXPECTED_TEMPLATE_FILES.size(), entries.size());
        Set<String> actualNames = entries.stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toSet());
        assertEquals(EXPECTED_TEMPLATE_FILES, actualNames);
        for (Path entry : entries) {
            assertTrue("unexpected non-file route resource: " + entry,
                    Files.isRegularFile(entry));
            assertCroppedPng(entry);
        }
    }

    @Test
    public void runtimeContainsOnlySharedWidgetSkinsAndCentralIllustrations() throws Exception {
        Path root = productionClasses().resolve(PROPOSAL3_ROOT);
        assertEquals(EXPECTED_WIDGET_FILES, directFileNames(root.resolve("widgets")));
        assertEquals(EXPECTED_ILLUSTRATION_FILES,
                directFileNames(root.resolve("illustrations")));
    }

    @Test
    public void everyCentralIllustrationDecodesInsideTheSharedPictureAperture() throws Exception {
        Set<String> decodedNames = new java.util.HashSet<>();
        int illustratedRoutes = 0;
        for (MenuRoute route : MenuRoute.values()) {
            String name = MenuIllustrationCatalog.resourceName(route);
            java.util.Optional<MenuArgbFrame> frame = MenuIllustrationCatalog.decode(route);
            if (name == null) {
                assertFalse(route + " unexpectedly has an illustration", frame.isPresent());
                continue;
            }
            illustratedRoutes++;
            decodedNames.add(name);
            assertTrue(route + " illustration did not decode", frame.isPresent());
            assertTrue(route + " illustration is wider than the common aperture",
                    frame.get().width() <= MenuScreenTemplate.PICTURE.width());
            assertTrue(route + " illustration is taller than the common aperture",
                    frame.get().height() <= MenuScreenTemplate.PICTURE.height());
        }
        assertEquals(14, illustratedRoutes);
        assertEquals(EXPECTED_ILLUSTRATION_FILES, decodedNames);
    }

    @Test
    public void runtimeTreeHasNoForbiddenSourceArtifacts() throws Exception {
        Path classes = productionClasses();
        assertFalse(Files.exists(classes.resolve("eu/rekawek/coffeegb/ui/menu/artwork/proposal3/source")));
        try (java.util.stream.Stream<Path> stream = Files.walk(classes)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String relative = classes.relativize(path).toString()
                        .replace(path.getFileSystem().getSeparator(), "/")
                        .toLowerCase(Locale.ROOT);
                assertFalse("test-only source path in runtime tree: " + relative,
                        relative.contains("/source/") || relative.endsWith("/source"));
                assertFalse("TTF artifact in runtime tree: " + relative, relative.endsWith(".ttf"));
                assertFalse("contact sheet artifact in runtime tree: " + relative,
                        relative.contains("contact-sheet") || relative.contains("contact_sheet"));
                assertFalse("original composition marker in runtime tree: " + relative,
                        relative.contains("1672") || relative.contains("941"));
            });
        }
    }

    private static Path productionClasses() throws Exception {
        Path classes = Paths.get(MenuArtwork.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        assertTrue("Maven production classes are not a directory", Files.isDirectory(classes));
        return classes;
    }

    private static Set<String> directFileNames(Path directory) throws IOException {
        assertTrue(Files.isDirectory(directory));
        try (java.util.stream.Stream<Path> stream = Files.list(directory)) {
            List<Path> entries = stream.collect(Collectors.toList());
            assertTrue(entries.stream().allMatch(Files::isRegularFile));
            return entries.stream().map(path -> path.getFileName().toString())
                    .collect(Collectors.toSet());
        }
    }

    private static void assertCroppedPng(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(Files.newInputStream(path))) {
            byte[] signature = new byte[8];
            input.readFully(signature);
            assertEquals((byte) 0x89, signature[0]);
            assertEquals('P', signature[1]);
            assertEquals('N', signature[2]);
            assertEquals('G', signature[3]);
            assertEquals(13, input.readInt());
            byte[] type = new byte[4];
            input.readFully(type);
            assertEquals('I', type[0]);
            assertEquals('H', type[1]);
            assertEquals('D', type[2]);
            assertEquals('R', type[3]);
            assertEquals(924, input.readInt());
            assertEquals(736, input.readInt());
            assertEquals(8, input.readUnsignedByte());
            assertEquals(2, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
            assertEquals(0, input.readUnsignedByte());
        }
    }
}
