package eu.rekawek.coffeegb.blargg.support;

import org.apache.commons.io.filefilter.SuffixFileFilter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RomTestUtils {

    private RomTestUtils() {
    }

    public static Collection<Object[]> getParameters(String dirName) {
        File dir = new File("src/test/resources/roms", dirName);
        List<Object[]> list = new ArrayList<>();
        for (String name : dir.list(new SuffixFileFilter(".gb"))) {
            File rom = new File(dir, name);
            list.add(new Object[] {name, rom});
        }
        return list;
    }

    public static void testRomWithMemory(String romName) throws IOException {
        MemoryTestRunner runner = new MemoryTestRunner(new File("src/test/resources/roms", romName), System.out);
        MemoryTestRunner.TestResult result = runner.runTest();
        assertEquals("Non-zero return value", 0, result.getStatus());
    }

    public static void testRomWithSerial(String romName) throws IOException {
        SerialTestRunner runner = new SerialTestRunner(new File("src/test/resources/roms", romName), System.out);
        String result = runner.runTest();
        assertTrue(result.contains("Passed"));
    }
}
