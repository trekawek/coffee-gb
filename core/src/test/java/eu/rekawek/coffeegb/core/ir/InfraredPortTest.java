package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InfraredPortTest {

    @Test
    public void readModesMatchCgbRpPullLevelsWithoutLight() {
        InfraredPort port = new InfraredPort(true, new SpeedMode(true));

        port.setByte(0xff56, 0x00);
        assertEquals(0x3e, port.getByte(0xff56));
        port.setByte(0xff56, 0x40);
        assertEquals(0x7e, port.getByte(0xff56));
        port.setByte(0xff56, 0x80);
        assertEquals(0xbc, port.getByte(0xff56));
        port.setByte(0xff56, 0xc0);
        assertEquals(0xfe, port.getByte(0xff56));
    }
}
