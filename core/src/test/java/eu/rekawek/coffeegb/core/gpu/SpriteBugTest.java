package eu.rekawek.coffeegb.core.gpu;

import eu.rekawek.coffeegb.core.memory.Ram;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class SpriteBugTest {

    @Test
    public void regularReadCorruptsTheScannedAndPreviousRows() {
        Ram oam = sequentialOam();
        setWord(oam, 3, 0, 0xaaaa);
        setWord(oam, 2, 0, 0x0f0f);
        setWord(oam, 2, 2, 0xf0f0);

        SpriteBug.corruptOamRead(oam, 0xfe00, 3);

        assertWord(oam, 2, 0, 0xafaf);
        assertRowEquals(oam, 2, 3);
    }

    @Test
    public void firstRowReadUsesTheCpuAddressedOamRow() {
        Ram oam = sequentialOam();
        setWord(oam, 0, 0, 0x0f0f);
        setWord(oam, 4, 0, 0x3333);
        setWord(oam, 4, 3, 0xffff);

        SpriteBug.corruptOamRead(oam, 0xfe26, 0);

        assertWord(oam, 0, 0, 0x3f3f);
        assertRowEquals(oam, 4, 0);
    }

    @Test
    public void readAfterLastScanRowCopiesTheOamLatchToAddressedRow() {
        Ram oam = sequentialOam();
        setWord(oam, 19, 0, 0x0f0f);
        setWord(oam, 19, 2, 0xaaaa);
        setWord(oam, 4, 0, 0xf0f0);

        SpriteBug.corruptOamRead(oam, 0xfe20, 20);

        assertWord(oam, 4, 0, 0xaaaa);
        assertRowEquals(oam, 19, 4);
    }

    @Test
    public void blockedWritePropagatesThePreviousRow() {
        Ram oam = sequentialOam();
        setWord(oam, 3, 0, 0xaaaa);
        setWord(oam, 2, 0, 0xcccc);
        setWord(oam, 2, 2, 0xf0f0);

        SpriteBug.corruptOamWrite(oam, 3);

        assertWord(oam, 3, 0, 0xe8e8);
        for (int i = 2; i < 8; i++) {
            assertEquals(oam.getByte(0xfe10 + i), oam.getByte(0xfe18 + i));
        }
    }

    private static Ram sequentialOam() {
        Ram oam = new Ram(0xfe00, 0xa0);
        for (int i = 0; i < 0xa0; i++) {
            oam.setByte(0xfe00 + i, i);
        }
        return oam;
    }

    private static void setWord(Ram oam, int row, int word, int value) {
        int address = 0xfe00 + row * 8 + word * 2;
        oam.setByte(address, value & 0xff);
        oam.setByte(address + 1, value >>> 8);
    }

    private static void assertWord(Ram oam, int row, int word, int expected) {
        int address = 0xfe00 + row * 8 + word * 2;
        assertEquals(expected & 0xff, oam.getByte(address));
        assertEquals(expected >>> 8, oam.getByte(address + 1));
    }

    private static void assertRowEquals(Ram oam, int expectedRow, int actualRow) {
        for (int i = 0; i < 8; i++) {
            assertEquals(
                    oam.getByte(0xfe00 + expectedRow * 8 + i),
                    oam.getByte(0xfe00 + actualRow * 8 + i));
        }
    }
}
