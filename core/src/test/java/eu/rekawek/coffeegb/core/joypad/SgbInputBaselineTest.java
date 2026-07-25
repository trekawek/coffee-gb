package eu.rekawek.coffeegb.core.joypad;

import eu.rekawek.coffeegb.core.sgb.SgbPacketTestBuilder;
import org.junit.Test;

import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SgbInputBaselineTest {

    @Test
    public void fakeFourPlayerSourceSamplesAndDisconnectsDeterministically() {
        FakeFourPlayerInputSource source = new FakeFourPlayerInputSource();
        source.setPressed(0, Button.START, Button.A);
        source.setPressed(1, Button.LEFT);
        source.setPressed(3, Button.B, Button.DOWN);

        assertEquals(Set.of(Button.A, Button.START), source.sample(0));
        assertEquals(Set.of(Button.LEFT), source.sample(1));
        assertEquals(1, source.sampleCount(0));
        assertEquals("P1=[A, START] samples=1;P2=[LEFT] samples=1;" +
                "P3=[] samples=0;P4=[B, DOWN] samples=0", source.diagnostic());

        source.disconnect(1);
        assertTrue(source.sample(1).isEmpty());
        assertEquals(2, source.sampleCount(1));
    }

    @Test
    public void currentOnePlayerUsesHeldButtonsButSelectedPlayerTwoFallsBackToIdle() {
        FakeFourPlayerInputSource source = new FakeFourPlayerInputSource();
        source.setPressed(0, Button.A);
        source.setPressed(1, Button.A, Button.B);

        try (SgbPacketTestBuilder fixture = new SgbPacketTestBuilder()) {
            Joypad joypad = fixture.joypad();
            joypad.setPressedButtons(source.sample(0));
            joypad.setByte(0xff00, 0x10); // select A/B/Select/Start
            assertEquals(0x0e, joypad.getByte(0xff00) & 0x0f);

            fixture.sendCommand(0x11, 1, 1); // two-player mode
            joypad.setByte(0xff00, 0x10);
            joypad.setByte(0xff00, 0x30); // advance to player 2
            assertEquals(0x0e, joypad.getByte(0xff00) & 0x0f); // selected-player ID

            // The fixture proves player 2 has input, but current production owns only one held
            // button set and deliberately returns released lines for selected players above 1.
            assertFalse(source.sample(1).isEmpty());
            joypad.setByte(0xff00, 0x10);
            assertEquals(0x0f, joypad.getByte(0xff00) & 0x0f);

            fixture.sendCommand(0x11, 1, 0); // back to one-player mode
            joypad.setByte(0xff00, 0x10);
            assertEquals(0x0e, joypad.getByte(0xff00) & 0x0f);
        }
    }
}
