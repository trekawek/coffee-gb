package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RomOriginTest {

    @Test
    public void archiveEntriesHaveDistinctStablePersistenceAnchors() {
        Path archive = Path.of("library", "collection.zip");
        RomOrigin first = RomOrigin.archiveEntry(archive, "games/red/game.gb");
        RomOrigin second = RomOrigin.archiveEntry(archive, "games/blue/game.gb");

        assertNotEquals(first.stableIdentity(), second.stableIdentity());
        assertNotEquals(
                first.persistencePath(".sav").orElseThrow(),
                second.persistencePath(".sav").orElseThrow());
        assertEquals("game.gb", first.displayName());
        assertEquals(archive.toAbsolutePath().normalize(), first.containerPath().orElseThrow());
    }

    @Test
    public void exactEntrySpellingCannotCollapseAndSidecarNameSurvivesFolderMoves() {
        RomOrigin repeatedSeparator =
                RomOrigin.archiveEntry(Path.of("first", "collection.zip"), "a//game.gb");
        RomOrigin ordinary =
                RomOrigin.archiveEntry(Path.of("first", "collection.zip"), "a/game.gb");
        RomOrigin moved =
                RomOrigin.archiveEntry(Path.of("second", "collection.zip"), "a/game.gb");

        assertNotEquals(repeatedSeparator.stableIdentity(), ordinary.stableIdentity());
        assertNotEquals(
                repeatedSeparator.persistencePath(".sav").orElseThrow(),
                ordinary.persistencePath(".sav").orElseThrow());
        assertEquals(
                ordinary.persistencePath(".sav").orElseThrow().getFileName(),
                moved.persistencePath(".sav").orElseThrow().getFileName());
    }

    @Test
    public void duplicateArchiveRecordsHaveDistinctOccurrenceIdentities() {
        Path archive = Path.of("collection.zip");
        RomOrigin first = RomOrigin.archiveEntry(archive, "game.gb", 0, false);
        RomOrigin second = RomOrigin.archiveEntry(archive, "game.gb", 1, false);

        assertNotEquals(first.stableIdentity(), second.stableIdentity());
        assertNotEquals(
                first.persistencePath(".sav").orElseThrow(),
                second.persistencePath(".sav").orElseThrow());
        assertEquals(1, second.archiveEntryOccurrence());
    }

    @Test
    public void archiveIdentityComponentsCannotCollideThroughDelimiterText() {
        RomOrigin delimiterInEntry =
                RomOrigin.archiveEntry(Path.of("library", "collection"), "nested!/game.gb");
        RomOrigin delimiterInContainer =
                RomOrigin.archiveEntry(
                        Path.of("library", "collection!/nested"), "game.gb");

        assertNotEquals(
                delimiterInEntry.stableIdentity(),
                delimiterInContainer.stableIdentity());
        assertNotEquals(
                delimiterInEntry.persistencePath(".sav").orElseThrow(),
                delimiterInContainer.persistencePath(".sav").orElseThrow());
        assertNotEquals(delimiterInEntry, delimiterInContainer);
    }

    @Test
    public void onlyInventoryProvenSingleArchiveEntryExposesLegacyAnchor() {
        RomOrigin ambiguous =
                RomOrigin.archiveEntry(Path.of("games.zip"), "one.gb", false);
        RomOrigin unambiguous =
                RomOrigin.archiveEntry(Path.of("games.zip"), "one.gb", true);

        assertFalse(ambiguous.legacyArchivePersistencePath(".sav").isPresent());
        assertEquals(
                Path.of("games.sav").toAbsolutePath().normalize(),
                unambiguous.legacyArchivePersistencePath(".sav").orElseThrow());
    }

    @Test
    public void rejectsTraversalAbsoluteAndDriveQualifiedArchiveEntries() {
        Path archive = Path.of("games.zip");

        assertThrows(
                IllegalArgumentException.class,
                () -> RomOrigin.archiveEntry(archive, "../escape.gb"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RomOrigin.archiveEntry(archive, "/absolute.gb"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RomOrigin.archiveEntry(archive, "C:/drive.gb"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RomOrigin.archiveEntry(archive, "C:drive.gb"));
        assertThrows(
                IllegalArgumentException.class,
                () -> RomOrigin.archiveEntry(archive, "safe/../escape.gb"));
    }

    @Test
    public void memoryOriginHasIdentityWithoutInventedPersistencePath() {
        RomOrigin origin = RomOrigin.memory("sha256:abc", "received.gb");

        assertEquals(RomOrigin.Kind.MEMORY, origin.kind());
        assertEquals("memory:sha256:abc", origin.stableIdentity());
        assertTrue(origin.containerPath().isEmpty());
        assertTrue(origin.persistencePath(".sav").isEmpty());
    }

    @Test
    public void hiddenAndLongContainerNamesProduceSafeCompatibleSidecarPaths() {
        RomOrigin hiddenDirect = RomOrigin.directFile(Path.of("library", ".gb"));
        RomOrigin hiddenArchive =
                RomOrigin.archiveEntry(Path.of("library", ".zip"), "game.gb", true);
        RomOrigin longArchive =
                RomOrigin.archiveEntry(
                        Path.of("library", "a".repeat(240) + ".zip"),
                        "folder/game.gb");

        assertEquals(
                Path.of("library", ".sav").toAbsolutePath().normalize(),
                hiddenDirect.persistencePath(".sav").orElseThrow());
        assertEquals(
                Path.of("library", ".sav").toAbsolutePath().normalize(),
                hiddenArchive.legacyArchivePersistencePath(".sav").orElseThrow());
        assertTrue(
                longArchive
                                .persistencePath(".sav")
                                .orElseThrow()
                                .getFileName()
                                .toString()
                                .length()
                        < 180);
    }
}
