package eu.rekawek.coffeegb.core.signal;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class HeldBus8Test {

    @Test
    public void exhaustivelyResolvesEveryPerBitDriveCombinationAndHeldValue() {
        HeldBus8 bus = new HeldBus8(0);
        for (int held = 0; held <= 0xff; held++) {
            bus.restore(held);
            for (int drivenHigh = 0; drivenHigh <= 0xff; drivenHigh++) {
                for (int drivenLow = 0; drivenLow <= 0xff; drivenLow++) {
                    bus.beginDrive();
                    bus.drive(drivenHigh, 0xff);
                    bus.drive(drivenLow, 0x00);

                    int driven = drivenHigh | drivenLow;
                    int expected = (held & ~driven) | (drivenHigh & ~drivenLow);
                    assertEquals(expected & 0xff, bus.resolve());
                    assertEquals(held, bus.held());
                    assertEquals(drivenHigh & drivenLow, bus.contentionMask());
                    assertEquals(driven, bus.drivenMask());
                }
            }
        }
    }

    @Test
    public void fullWidthDriverResolutionIsExhaustivelyOrderIndependent() {
        HeldBus8 first = new HeldBus8(0xa5);
        HeldBus8 second = new HeldBus8(0xa5);
        for (int a = 0; a <= 0xff; a++) {
            for (int b = 0; b <= 0xff; b++) {
                first.beginDrive();
                first.drive(a);
                first.drive(b);

                second.beginDrive();
                second.drive(b);
                second.drive(a);

                assertEquals(a & b, first.resolve());
                assertEquals(first.nextHeld(), second.resolve());
                assertEquals(a ^ b, first.contentionMask());
                assertEquals(first.contentionMask(), second.contentionMask());
            }
        }
    }

    @Test
    public void floatingLinesRetainOnlyTheLastCommittedResolution() {
        HeldBus8 bus = new HeldBus8(0xa5);
        bus.beginDrive();
        bus.drive(0x0f, 0x03);

        assertEquals(0xa3, bus.resolve());
        assertEquals(0xa5, bus.held());
        bus.commit();
        assertEquals(0xa3, bus.held());

        bus.beginDrive();
        assertEquals(0xa3, bus.resolve());
    }

    @Test
    public void rejectsValuesThatAreNotUnsignedBytes() {
        assertThrows(IllegalArgumentException.class, () -> new HeldBus8(-1));
        assertThrows(IllegalArgumentException.class, () -> new HeldBus8(0x100));

        HeldBus8 bus = new HeldBus8(0);
        assertThrows(IllegalArgumentException.class, () -> bus.drive(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> bus.drive(0, 0x100));
        assertThrows(IllegalArgumentException.class, () -> bus.restore(-1));
    }
}
