package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.GameboyType;

import java.io.File;
import java.io.OutputStream;

public class BlarggMain {
    public static void main(String[] args) throws Exception {
        for (String rom : args) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            MemoryTestRunner runner = new MemoryTestRunner(new File(rom), out, GameboyType.DMG);
            MemoryTestRunner.TestResult result = runner.runTest(400_000_000L);
            String status = result.getStatus() == 0 ? "PASS" : result.getStatus() == -1 ? "HANG" : "FAIL";
            System.out.println(status + " " + new File(rom).getName());
            if (result.getStatus() > 0) {
                System.out.println("--- output: " + out.toString().trim().replace("\n", " / "));
            }
        }
        System.exit(0);
    }
}
