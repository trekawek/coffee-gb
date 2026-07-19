package eu.rekawek.coffeegb.core.memory;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OamEchoRamTest {

    @Test
    public void cgbAliasesFec0ThroughFeffInSixteenByteWindows() {
        OamEchoRam ram = new OamEchoRam(true);

        ram.setByte(0xfea0, 0x12);
        ram.setByte(0xfebf, 0x34);
        ram.setByte(0xfec0, 0x56);
        ram.setByte(0xfeff, 0x78);

        assertEquals(0x12, ram.getByte(0xfea0));
        assertEquals(0x34, ram.getByte(0xfebf));
        assertEquals(0x56, ram.getByte(0xfec0));
        assertEquals(0x56, ram.getByte(0xfed0));
        assertEquals(0x56, ram.getByte(0xfef0));
        assertEquals(0x78, ram.getByte(0xfecf));
        assertEquals(0x78, ram.getByte(0xfeff));
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
