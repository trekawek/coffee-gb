package eu.rekawek.coffeegb.core.integration.cgbacid2;

import eu.rekawek.coffeegb.core.integration.support.CgbAcid2TestRunner;
import org.junit.Test;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertArrayEquals;

public class CgbAcid2RomTest {

    private static final Path RESOURCE_DIR = Paths.get("src/test/resources/roms/cgb-acid2");

    @Test(timeout = 10000)
    public void testCgbAcid2() throws Exception {
        CgbAcid2TestRunner runner = new CgbAcid2TestRunner(
                RESOURCE_DIR.resolve("cgb-acid2.gbc").toFile(),
                RESOURCE_DIR.resolve("cgb-acid2.png").toFile());
        CgbAcid2TestRunner.TestResult result = runner.runTest();

        File resultFile = File.createTempFile("cgb-acid2-", "-result.png");
        result.writeResultToFile(resultFile);
        assertArrayEquals("The result image is different from expected: " + resultFile,
                result.getExpectedRgb(), result.getResultRgb());
        resultFile.delete();
    }
}
