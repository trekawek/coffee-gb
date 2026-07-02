package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.GameboyType;

import java.io.File;
import java.io.OutputStream;

public class BlarggMain {
    public static void main(String[] args) throws Exception {
        for (String rom : args) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] header = java.nio.file.Files.readAllBytes(new File(rom).toPath());
            GameboyType type = (header[0x143] & 0x80) != 0 ? GameboyType.CGB : GameboyType.DMG;
            MemoryTestRunner runner = new MemoryTestRunner(new File(rom), out, type);
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
