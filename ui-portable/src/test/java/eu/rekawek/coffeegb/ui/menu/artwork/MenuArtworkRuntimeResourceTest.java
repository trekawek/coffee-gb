package eu.rekawek.coffeegb.ui.menu.artwork;

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

    private static final long RUNTIME_ARTWORK_BUDGET = 14_680_064L;
    private static final String PROPOSAL3_ROOT =
            "eu/rekawek/coffeegb/ui/menu/artwork/proposal3";
    private static final String RAW_ROOT =
            "eu/rekawek/coffeegb/ui/menu/artwork/proposal3/routes/raw";
    private static final Set<String> EXPECTED_RAW_FILES = Set.of(
            "00-pause-console.png",
            "01-save-states.png",
            "02-settings.png",
            "03-audio.png",
            "04-touch-controls.png",
            "05-controller-mapping.png",
            "06-optional-devices.png",
            "07-data-media.png",
            "08-library.png",
            "09-choose-rom.png",
            "10-system.png",
            "11-about.png",
            "12-confirm-action.png",
            "13-printer-paper.png");

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
        assertTrue("runtime Proposal 3 artwork exceeds 14,680,064 bytes: " + bytes,
                bytes <= RUNTIME_ARTWORK_BUDGET);
    }

    @Test
    public void rawRouteDirectoryContainsExactlyExpectedDirectPngFiles() throws Exception {
        Path rawRoot = productionClasses().resolve(RAW_ROOT);
        assertTrue(Files.isDirectory(rawRoot));
        List<Path> entries;
        try (java.util.stream.Stream<Path> stream = Files.list(rawRoot)) {
            entries = stream.sorted().collect(Collectors.toList());
        }
        assertEquals(EXPECTED_RAW_FILES.size(), entries.size());
        Set<String> actualNames = entries.stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toSet());
        assertEquals(EXPECTED_RAW_FILES, actualNames);
        for (Path entry : entries) {
            assertTrue("unexpected non-file route resource: " + entry,
                    Files.isRegularFile(entry));
            assertCroppedPng(entry);
        }
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
