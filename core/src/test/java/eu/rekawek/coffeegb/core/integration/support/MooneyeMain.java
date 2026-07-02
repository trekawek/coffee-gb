package eu.rekawek.coffeegb.core.integration.support;

import java.io.File;

/**
 * Runs one or more mooneye ROMs with a tick cap. Prints PASS/FAIL/HANG per ROM.
 * Usage: MooneyeMain <rom> [<rom> ...]
 */
public class MooneyeMain {

    public static void main(String[] args) throws Exception {
        for (String rom : args) {
            String result;
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try {
                MooneyeTestRunner runner = new MooneyeTestRunner(new File(rom), out);
                Boolean passed = runner.runTest(40_000_000L);
                result = passed == null ? "HANG" : (passed ? "PASS" : "FAIL " + runner.dumpRegs());
            } catch (Exception e) {
                result = "ERROR " + e;
            }
            System.out.println(result + " " + new File(rom).getName());
            if (!result.startsWith("PASS")) {
                System.out.println("--- output: " + out.toString().trim().replace("\n", " / "));
            }
        }
        System.exit(0);
    }
}
