package eu.rekawek.coffeegb.core.integration.samesuite;

import eu.rekawek.coffeegb.core.Gameboy;
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
import java.util.Set;
import java.util.stream.Collectors;

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

    /**
     * RGBDS 1.0.1 changed {@code ld [rDIV], a} from the 12-T-cycle LDH encoding used
     * by the original hardware captures to the 16-T-cycle absolute LD encoding. The
     * ROMs retained the original NOP counts and expected tables, making these six
     * rebuilds internally inconsistent. Their historical {@code -shootout} builds
     * preserve the instruction timing used to capture the expected results.
     */
    private static final Set<Path> INVALID_RGBDS_1_0_1_TIMING_BUILDS = Set.of(
            Paths.get("apu", "channel_1", "channel_1_stop_div.gb"),
            Paths.get("apu", "channel_1", "channel_1_sweep.gb"),
            Paths.get("apu", "channel_1", "channel_1_sweep_restart.gb"),
            Paths.get("apu", "channel_1", "channel_1_volume_div.gb"),
            Paths.get("apu", "channel_2", "channel_2_stop_div.gb"),
            Paths.get("apu", "channel_2", "channel_2_volume_div.gb")
    );

    private final Path romPath;

    private final GameboyType gameboyType;

    private final Gameboy.BootstrapMode bootstrapMode;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        return ParametersProvider.getParameters("samesuite", INCOMPATIBLE_REVISIONS, Integer.MAX_VALUE).stream()
                .filter(p -> !INVALID_RGBDS_1_0_1_TIMING_BUILDS.contains(Paths.get((String) p[0])))
                .collect(Collectors.toList());
    }

    public SameSuiteRomTest(String name, Path romPath) {
        this.romPath = romPath;
        Path relativePath = Paths.get(name);
        gameboyType = relativePath.getName(0).toString().equals("sgb") ? GameboyType.SGB : null;
        bootstrapMode = name.endsWith("-shootout.gb")
                ? Gameboy.BootstrapMode.FAST_FORWARD
                : Gameboy.BootstrapMode.SKIP;
    }

    @Test(timeout = 30000)
    public void test() throws IOException {
        SameSuiteTestRunner.TestResult result =
                new SameSuiteTestRunner(romPath.toFile(), gameboyType, bootstrapMode).runTest();
        assertFalse(result.getOutput(), result.isTimedOut());
        assertTrue(result.getOutput(), result.isPassed());
    }
}
