package eu.rekawek.coffeegb.integration.support;

import java.io.IOException;
import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.Assert.*;

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

    public static void testRomWithImage(Path romPath) throws Exception {
        System.out.println("\n### Running test rom " + romPath.getFileName() + " ###");
        ImageTestRunner runner = new ImageTestRunner(romPath.toFile());
        ImageTestRunner.TestResult result = runner.runTest();
        assertArrayEquals(result.getErrorMessage(),result.getExpectedRGB(),result.getResultRGB());
    }

    public static void testMooneyeRom(Path romPath) throws IOException {
        System.out.println("\n### Running test rom " + romPath.getFileName() + " ###");
        MooneyeTestRunner runner = new MooneyeTestRunner(romPath.toFile(), System.out);
        assertTrue(runner.runTest());
    }
}
