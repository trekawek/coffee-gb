package eu.rekawek.coffeegb.integration.support;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class RomTestUtils {

    private RomTestUtils() {
    }

    public static void testRomWithMemory(Path romPath) throws IOException {
        System.out.println("\n### Running test rom " + romPath.getFileName() + " ###");
        MemoryTestRunner runner = new MemoryTestRunner(romPath.toFile(), System.out);
        MemoryTestRunner.TestResult result = runner.runTest();
        assertEquals("Non-zero return value", 0, result.getStatus());
    }

    public static void testRomWithSerial(Path romPath) throws IOException {
        System.out.println("\n### Running test rom " + romPath.getFileName() + " ###");
        SerialTestRunner runner = new SerialTestRunner(romPath.toFile(), System.out);
        String result = runner.runTest();
        assertTrue(result.contains("Passed"));
    }

    public static void testMooneyeRom(Path romPath) throws IOException {
        System.out.println("\n### Running test rom " + romPath.getFileName() + " ###");
        MooneyeTestRunner runner = new MooneyeTestRunner(romPath.toFile(), System.out);
        assertTrue(runner.runTest());
    }
}
