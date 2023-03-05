package eu.rekawek.coffeegb.integration.dmgacid2;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static eu.rekawek.coffeegb.integration.support.RomTestUtils.*;

public class DmgAcid2RomTest {

    @Test
    public void testDmgAcid2() throws Exception {
        testRomWithImage(getPath("dmg-acid2.gb"));
    }


    private static Path getPath(String name) {
        return Paths.get("src/test/resources/roms/dmg-acid2", name);
    }
}
