package eu.rekawek.coffeegb.integration.blargg;

import eu.rekawek.coffeegb.GameboyType;
import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;

import static eu.rekawek.coffeegb.integration.support.RomTestUtils.testRomWithMemory;
import static eu.rekawek.coffeegb.integration.support.RomTestUtils.testRomWithSerial;

public class BlarggRomTest {

    @Test
    public void testCgbSound() throws IOException {
        testRomWithMemory(getPath("cgb_sound.gb"), GameboyType.CGB);
    }

    @Test
    public void testCpuInstrs() throws IOException {
        testRomWithSerial(getPath("cpu_instrs.gb"), GameboyType.DMG);
    }

    @Test
    public void testDmgSound2() throws IOException {
        testRomWithMemory(getPath("dmg_sound-2.gb"), GameboyType.DMG);
    }

    @Test
    public void testHaltBug() throws IOException {
        testRomWithMemory(getPath("halt_bug.gb"), GameboyType.DMG);
    }

    @Test
    public void testInstrTiming() throws IOException {
        testRomWithSerial(getPath("instr_timing.gb"), GameboyType.DMG);
    }

    @Test
    @Ignore
    public void testInterruptTime() throws IOException {
        testRomWithMemory(getPath("interrupt_time.gb"), GameboyType.DMG);
    }

    @Test
    public void testMemTiming2() throws IOException {
        testRomWithMemory(getPath("mem_timing-2.gb"), GameboyType.DMG);
    }

    @Test
    public void testOamBug2() throws IOException {
        testRomWithMemory(getPath("oam_bug-2.gb"), GameboyType.DMG);
    }

    private static Path getPath(String name) {
        return Paths.get("src/test/resources/roms/blargg", name);
    }
}
