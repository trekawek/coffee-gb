package eu.rekawek.coffeegb.core.integration.samesuite;

import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.integration.support.ParametersProvider;
import eu.rekawek.coffeegb.core.integration.support.SameSuiteTestRunner;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(Parameterized.class)
public class SameSuiteRomTest {

    private static final List<String> INCOMPATIBLE_REVISIONS = Arrays.asList(
            "-A.gb",
            "-cgb0.gb",
            "-cgbB.gb",
            "-cgb0B.gb",
            "-cgb0BC.gb"
    );

    private final Path romPath;

    private final GameboyType gameboyType;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("samesuite", INCOMPATIBLE_REVISIONS, Integer.MAX_VALUE);
    }

    public SameSuiteRomTest(String name, Path romPath) {
        this.romPath = romPath;
        Path relativePath = Paths.get(name);
        gameboyType = relativePath.getName(0).toString().equals("sgb") ? GameboyType.SGB : null;
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        SameSuiteTestRunner.TestResult result = new SameSuiteTestRunner(romPath.toFile(), gameboyType).runTest();
        assertFalse(result.getOutput(), result.isTimedOut());
        assertTrue(result.getOutput(), result.isPassed());
    }
}
