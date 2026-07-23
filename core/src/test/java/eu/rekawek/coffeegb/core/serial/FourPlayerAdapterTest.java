package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class FourPlayerAdapterTest {

    @Test
    public void pingReportsPhysicalPlayerNumbersAndConnectedMask() {
        Rig rig = new Rig();

        // The first valid ACK packet makes all four ports present.
        rig.transfer(0x01, 0x01, 0x01, 0x01);
        rig.transfer(0x88, 0x88, 0x88, 0x88);
        rig.transfer(0x88, 0x88, 0x88, 0x88);
        rig.transfer(0x10, 0x10, 0x10, 0x10);

        assertArrayEquals(new int[]{0xfe, 0xfe, 0xfe, 0xfe},
                rig.transfer(0x88, 0x88, 0x88, 0x88));
        assertArrayEquals(new int[]{0xf1, 0xf2, 0xf3, 0xf4},
                rig.transfer(0x88, 0x88, 0x88, 0x88));
    }

    @Test
    public void transmissionReplyStreamLeadsCaptureByOneByte() {
        Rig rig = new Rig();

        enterTransmission(rig, 1);

        // Packet 1 fills the adapter's next buffer from its first transfer; packet 2 broadcasts
        // it to everyone. Later replies in packet 1 are outside every player's SIZE=1 slot.
        rig.transfer(0x11, 0x22, 0x33, 0x44);
        rig.transfer(0, 0, 0, 0);
        rig.transfer(0, 0, 0, 0);
        rig.transfer(0, 0, 0, 0);

        // The physical reply stream leads the logical packet by one byte. The Game Boy receive
        // ring wraps the last reply back to byte zero, reconstructing 11, 22, 33, 44.
        for (int expected : new int[]{0x22, 0x33, 0x44, 0x11}) {
            assertArrayEquals(new int[]{expected, expected, expected, expected},
                    rig.transfer(0, 0, 0, 0));
        }
    }

    @Test
    public void transmissionCapturesSizeFourDataAtStartOfPacket() {
        Rig rig = new Rig();

        enterTransmission(rig, 4);

        for (int dataByte = 0; dataByte < 4; dataByte++) {
            rig.transfer(0x10 + dataByte, 0x20 + dataByte,
                    0x30 + dataByte, 0x40 + dataByte);
        }
        // The remaining twelve transfers belong to the other players' outgoing slots. Their
        // replies must not displace or overwrite the four bytes each player submitted above.
        for (int i = 4; i < 16; i++) {
            rig.transfer(0xee, 0xee, 0xee, 0xee);
        }

        int[] physicalReplyStream = {
                0x11, 0x12, 0x13,
                0x20, 0x21, 0x22, 0x23,
                0x30, 0x31, 0x32, 0x33,
                0x40, 0x41, 0x42, 0x43,
                0x10
        };
        for (int expected : physicalReplyStream) {
            assertArrayEquals(new int[]{expected, expected, expected, expected},
                    rig.transfer(0, 0, 0, 0));
        }
    }

    @Test
    public void restartSequenceReturnsToPingAfterFullFfIndicatorPacket() {
        Rig rig = new Rig();
        enterTransmission(rig, 1);

        rig.transfer(0, 0, 0, 0);
        rig.transfer(0xff, 0xff, 0xff, 0xff);
        rig.transfer(0xff, 0xff, 0xff, 0xff);
        rig.transfer(0xff, 0xff, 0xff, 0xff);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(new int[]{0xff, 0xff, 0xff, 0xff},
                    rig.transfer(0, 0, 0, 0));
        }
        assertArrayEquals(new int[]{0xfe, 0xfe, 0xfe, 0xfe},
                rig.transfer(0, 0, 0, 0));
    }

    @Test
    public void boundaryAlignedTransmissionCommandDoesNotReplaceRateWithAa() {
        Rig reference = new Rig();
        enterTransmission(reference, 4, 0x28);

        Rig boundaryAligned = new Rig();
        configurePing(boundaryAligned, 4, 0x28);
        // Software loads the first AA after receiving the header. It is sent alongside STAT1,
        // so three command bytes finish this ping packet and the fourth crosses into the next.
        boundaryAligned.transfer(4, 4, 4, 4);
        boundaryAligned.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        boundaryAligned.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        boundaryAligned.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        boundaryAligned.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(new int[]{0xcc, 0xcc, 0xcc, 0xcc},
                    boundaryAligned.transfer(0, 0, 0, 0));
        }

        // RATE affects both inter-byte and inter-packet timing. Matching a command that began on
        // a packet boundary proves the crossed command retained 0x28 instead of latching 0xAA.
        for (int i = 0; i < 32; i++) {
            assertArrayEquals(reference.transfer(0, 0, 0, 0),
                    boundaryAligned.transfer(0, 0, 0, 0));
            assertEquals(reference.lastTransferTicks, boundaryAligned.lastTransferTicks);
        }
    }

    private static void enterTransmission(Rig rig, int size) {
        enterTransmission(rig, size, 0x10);
    }

    private static void enterTransmission(Rig rig, int size, int rate) {
        configurePing(rig, size, rate);

        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(new int[]{0xcc, 0xcc, 0xcc, 0xcc},
                    rig.transfer(0, 0, 0, 0));
        }
    }

    private static void configurePing(Rig rig, int size, int rate) {
        rig.transfer(size, size, size, size);
        rig.transfer(0x88, 0x88, 0x88, 0x88);
        rig.transfer(0x88, 0x88, 0x88, 0x88);
        rig.transfer(rate, rate, rate, rate);
    }

    private static final class Rig {
        private final SerialPort[] ports = new SerialPort[FourPlayerAdapter.PLAYER_COUNT];

        private int lastTransferTicks;

        private Rig() {
            FourPlayerAdapter adapter = new FourPlayerAdapter();
            for (int i = 0; i < ports.length; i++) {
                ports[i] = new SerialPort(new InterruptManager(false), false, new SpeedMode(false));
                ports[i].init(adapter.endpoint(i));
            }
        }

        private int[] transfer(int... outgoing) {
            for (int i = 0; i < ports.length; i++) {
                ports[i].setByte(0xff01, outgoing[i]);
                ports[i].setByte(0xff02, 0x80);
            }
            int timeout = 100_000;
            while ((ports[0].getByte(0xff02) & 0x80) != 0 && timeout-- > 0) {
                for (SerialPort port : ports) {
                    port.tick();
                }
            }
            if (timeout == 0) {
                throw new AssertionError("DMG-07 transfer timed out");
            }
            lastTransferTicks = 100_000 - timeout;
            int[] result = new int[ports.length];
            for (int i = 0; i < ports.length; i++) {
                result[i] = ports[i].getByte(0xff01);
            }
            return result;
        }
    }
}
