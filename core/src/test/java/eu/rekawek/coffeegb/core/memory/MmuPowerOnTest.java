package eu.rekawek.coffeegb.core.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class MmuPowerOnTest {

    @Test
    public void powerOnWramContainsBothZeroSentinelsAndEntropy() {
        Mmu mmu = new Mmu(true);
        mmu.indexSpaces();

        // Older GBDK font code lazily initializes this BSS block (issue #111).
        for (int address = 0xc0f8; address < 0xc100; address++) {
            assertEquals(0, mmu.getByte(address));
        }

        // Minesweeper seeds its LFSR from this otherwise-uninitialized block (issue #48).
        boolean hasNonZeroSeed = false;
        for (int address = 0xc100; address < 0xc110; address++) {
            hasNonZeroSeed |= mmu.getByte(address) != 0;
        }
        assertTrue(hasNonZeroSeed);
    }
}
