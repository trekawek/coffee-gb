package eu.rekawek.coffeegb.core.integration.rtc3;

import eu.rekawek.coffeegb.core.integration.support.Rtc3TestRunner;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;

public class Rtc3RomTest {

    private static final Path ROM_PATH = Paths.get("src/test/resources/roms/rtc3/rtc3test.gb");

    @Test(timeout = 120000)
    public void testBasic() throws IOException {
        testMenu(0);
    }

    @Test(timeout = 120000)
    public void testRange() throws IOException {
        testMenu(1);
    }

    @Test(timeout = 120000)
    public void testSubSecondWrites() throws IOException {
        testMenu(2);
    }

    private static void testMenu(int menuIndex) throws IOException {
        Rtc3TestRunner.TestResult result = new Rtc3TestRunner(ROM_PATH.toFile()).runTest(menuIndex);
        assertTrue(result.getOutput(), result.isPassed());
    }
}
