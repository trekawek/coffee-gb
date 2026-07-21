package eu.rekawek.coffeegb.core.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OamEchoRamTest {

    @Test
    public void cgbMasksAddressBitsThreeAndFour() {
        OamEchoRam ram = new OamEchoRam(true);

        ram.setByte(0xfea0, 0x12);
        assertEquals(0x12, ram.getByte(0xfea0));
        assertEquals(0x12, ram.getByte(0xfea8));
        assertEquals(0x12, ram.getByte(0xfeb0));
        assertEquals(0x12, ram.getByte(0xfeb8));

        ram.setByte(0xfeff, 0x34);
        assertEquals(0x34, ram.getByte(0xfee7));
        assertEquals(0x34, ram.getByte(0xfeef));
        assertEquals(0x34, ram.getByte(0xfef7));
    }

    @Test
    public void dmgReadsZeroThroughoutProhibitedArea() {
        OamEchoRam ram = new OamEchoRam(false);

        ram.setByte(0xfea0, 0x12);
        ram.setByte(0xfeff, 0x34);

        assertEquals(0x00, ram.getByte(0xfea0));
        assertEquals(0x00, ram.getByte(0xfeff));
    }
}
