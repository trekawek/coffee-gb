package eu.rekawek.coffeegb.core.memory.cart;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;

public class RomSourceDataTest {

    @Test
    public void preservesBytesUsedForExternalGameIdentification() throws Exception {
        byte[] source = new byte[0x8000];
        source[0x0147] = 0;
        source[0x0148] = 0;
        source[0x0149] = 0;
        source[0x014d] = 0x55; // deliberately invalid; the emulated copy will be repaired

        Rom rom = new Rom(source);

        assertArrayEquals(source, rom.getSourceData());
        assertNotEquals(source[0x014d] & 0xff, rom.getRom()[0x014d]);
    }
}
