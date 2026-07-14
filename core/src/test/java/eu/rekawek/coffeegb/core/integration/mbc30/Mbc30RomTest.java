package eu.rekawek.coffeegb.core.integration.mbc30;

import eu.rekawek.coffeegb.core.integration.support.Mbc30TestRunner;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Mbc30RomTest {

    private static final Path ROM_PATH = Paths.get("src/test/resources/roms/mbc30/MBC3_Test.gbc");

    @Test(timeout = 30000)
    public void testMbc30() throws IOException {
        Mbc30TestRunner.TestResult result = new Mbc30TestRunner(ROM_PATH.toFile()).runTest();
        assertFalse(result.getDiagnostic(), result.isTimedOut());
        assertTrue(result.getDiagnostic(), result.isPassed());
    }
}
