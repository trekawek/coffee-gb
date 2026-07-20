package eu.rekawek.coffeegb.core.integration.miscgb;

import eu.rekawek.coffeegb.core.integration.support.MooneyeTestRunner;
import eu.rekawek.coffeegb.core.integration.support.ParallelParameterized;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Executes every ROM in alyosha-tas/Misc.-GB-Tests through its Mooneye protocol. */
@RunWith(ParallelParameterized.class)
public class MiscGbRomTest {

    private static final String ARCHIVE = "/roms/misc-gb-tests/misc-gb-tests-be3cbb8.zip";

    private static final long MAX_TICKS = 40_000_000L;

    private final String name;

    private final byte[] rom;

    public MiscGbRomTest(String name, byte[] rom) {
        this.name = name;
        this.rom = rom;
    }

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> data() throws IOException {
        InputStream input = MiscGbRomTest.class.getResourceAsStream(ARCHIVE);
        if (input == null) {
            throw new IOException("Missing Misc.-GB-Tests archive: " + ARCHIVE);
        }
        List<Object[]> parameters = new ArrayList<>();
        try (input; ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (!entry.isDirectory() && entry.getName().endsWith(".gb")) {
                    parameters.add(new Object[]{entry.getName(), zip.readAllBytes()});
                }
            }
        }
        parameters.sort(Comparator.comparing(parameter -> (String) parameter[0]));

        if (parameters.size() != 17) {
            throw new IOException("Expected 17 Misc.-GB-Tests ROMs, found " + parameters.size());
        }
        return parameters;
    }

    @Test(timeout = 10000)
    public void test() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        MooneyeTestRunner runner = new MooneyeTestRunner(rom, name, output);
        Boolean result = runner.runTest(MAX_TICKS);
        assertNotNull(name + " did not reach its test breakpoint: " + runner.dumpRegs()
                + ", output=" + output, result);
        assertTrue(name + " failed: " + runner.dumpRegs() + ", output=" + output, result);
    }

}
