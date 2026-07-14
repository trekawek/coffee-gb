package eu.rekawek.coffeegb.core.integration.bullygb;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.BullyGbTestRunner;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BullyGbRomTest {

    private static final Path ROM_PATH = Paths.get("src/test/resources/roms/bullygb/bully.gb");

    @Test(timeout = 10000)
    public void testDmg() throws IOException {
        test(GameboyType.DMG);
    }

    @Test(timeout = 10000)
    public void testCgb() throws IOException {
        test(GameboyType.CGB);
    }

    private static void test(GameboyType gameboyType) throws IOException {
        BullyGbTestRunner.TestResult result = new BullyGbTestRunner(ROM_PATH.toFile(), gameboyType).runTest();
        assertFalse(result.getDiagnostic(), result.isTimedOut());
        assertTrue(result.getDiagnostic(), result.isPassed());
    }
}
