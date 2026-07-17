package eu.rekawek.coffeegb.core.genie;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class CheatDatabaseTest {

    @Test
    public void parsesLibretroCheatFiles() throws Exception {
        String contents = """
                cheats = 2
                cheat0_desc = "Infinite lives &amp; health"
                cheat0_code = "010999C0"
                cheat0_enable = false
                cheat1_desc = "Start at level 5"
                cheat1_code = "045-ABC-123"
                cheat1_enable = false
                """;

        CheatDatabase database = CheatDatabase.readZip(zip("cht/Tetris (World).cht", contents));

        assertEquals(1, database.getCheatLists().size());
        CheatDatabase.CheatList list = database.getCheatLists().get(0);
        assertEquals("Tetris (World)", list.name());
        assertEquals(List.of(
                new CheatDatabase.Cheat("Infinite lives & health", "010999C0"),
                new CheatDatabase.Cheat("Start at level 5", "045-ABC-123")
        ), list.cheats());
    }

    @Test
    public void proposesEntriesFromRomFilename() throws Exception {
        try (var input = CheatDatabaseTest.class.getResourceAsStream("/cheats/libretro-game-boy.zip")) {
            CheatDatabase database = CheatDatabase.readZip(input);

            List<CheatDatabase.CheatList> matches =
                    database.findCheatLists(List.of("Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb"), 10);

            assertFalse(matches.isEmpty());
            assertEquals("Pokemon - Red Version (USA, Europe) (SGB Enhanced)", matches.get(0).name());
        }
    }

    @Test
    public void cartridgeTitleFindsRelatedEntries() throws Exception {
        try (var input = CheatDatabaseTest.class.getResourceAsStream("/cheats/libretro-game-boy.zip")) {
            CheatDatabase database = CheatDatabase.readZip(input);

            List<CheatDatabase.CheatList> matches = database.findCheatLists(List.of("TETRIS"), 10);

            assertFalse(matches.isEmpty());
            assertEquals("Tetris (Japan) (En)", matches.get(0).name());
        }
    }

    private static ByteArrayInputStream zip(String name, String contents) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry(name));
            zip.write(contents.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return new ByteArrayInputStream(bytes.toByteArray());
    }
}
