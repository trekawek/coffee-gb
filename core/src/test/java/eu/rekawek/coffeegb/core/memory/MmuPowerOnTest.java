package eu.rekawek.coffeegb.core.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

    @Test
    public void dmgHighWramPagesFollowTheMeasuredPowerOnBias() {
        Mmu mmu = new Mmu(false);
        mmu.indexSpaces();

        for (int address = 0xde00; address < 0xdf00; address++) {
            assertEquals(0xff, mmu.getByte(address));
        }
        for (int address = 0xdf00; address < 0xe000; address++) {
            assertEquals(0x00, mmu.getByte(address));
        }
    }

    @Test
    public void debugReadsUseOwnedWramEchoAndHramWithoutTheBusMap() {
        Mmu mmu = new Mmu(false);
        mmu.indexSpaces();
        mmu.setByte(0xc000, 0x12);
        mmu.setByte(0xd123, 0x34);
        mmu.setByte(0xfffe, 0x56);

        assertEquals(0x12, mmu.readDebugMemory(0xc000, 1)[0] & 0xff);
        assertEquals(0x12, mmu.readDebugMemory(0xe000, 1)[0] & 0xff);
        assertEquals(0x34, mmu.readDebugMemory(0xf123, 1)[0] & 0xff);
        assertEquals(0x56, mmu.readDebugMemory(0xfffe, 1)[0] & 0xff);
    }

    @Test
    public void debugReadsFollowTheSelectedCgbWramBank() {
        Mmu mmu = new Mmu(true);
        mmu.indexSpaces();
        mmu.setByte(0xff70, 2);
        mmu.setByte(0xd000, 0xa5);

        assertEquals(0xa5, mmu.readDebugMemory(0xd000, 1)[0] & 0xff);
        assertEquals(0xa5, mmu.readDebugMemory(0xf000, 1)[0] & 0xff);
    }

    @Test
    public void debugReadsRejectEveryNonOwnedRegionBeforeCopying() {
        Mmu mmu = new Mmu(false);
        mmu.indexSpaces();

        assertDebugReadRejected(mmu, 0x0000, 1);
        assertDebugReadRejected(mmu, 0x8000, 1);
        assertDebugReadRejected(mmu, 0xfe00, 1);
        assertDebugReadRejected(mmu, 0xff0f, 1);
        assertDebugReadRejected(mmu, 0xffff, 0);
        assertDebugReadRejected(mmu, 0xfdff, 2);
    }

    private static void assertDebugReadRejected(Mmu mmu, int address, int length) {
        try {
            mmu.readDebugMemory(address, length);
            fail("Expected debug read to be rejected at " + Integer.toHexString(address));
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }
}
