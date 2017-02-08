package eu.rekawek.coffeegb.blargg;

import org.junit.Ignore;
import org.junit.Test;

import java.io.IOException;

import static eu.rekawek.coffeegb.blargg.support.RomTestUtils.testRomWithMemory;
import static eu.rekawek.coffeegb.blargg.support.RomTestUtils.testRomWithSerial;

public class BlarggRomTest {

    @Test
    public void testCgbSound() throws IOException {
        testRomWithMemory("cgb_sound.gb");
    }

    @Test
    public void testCpuInstrs() throws IOException {
        testRomWithSerial("cpu_instrs.gb");
    }

    @Test
    public void testDmgSound2() throws IOException {
        testRomWithMemory("dmg_sound-2.gb");
    }

    @Test
    public void testHaltBug() throws IOException {
        testRomWithMemory("halt_bug.gb");
    }

    @Test
    public void testInstrTiming() throws IOException {
        testRomWithSerial("instr_timing.gb");
    }

    @Test
    public void testInterruptTime() throws IOException {
        testRomWithMemory("interrupt_time.gb");
    }

    @Test
    public void testMemTiming2() throws IOException {
        testRomWithMemory("mem_timing-2.gb");
    }

    @Test
    public void testOamBug2() throws IOException {
        testRomWithMemory("oam_bug-2.gb");
    }
}
