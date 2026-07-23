package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;

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
    public void transmissionBroadcastsPreviousPacketInPlayerOrder() {
        Rig rig = new Rig();

        enterTransmission(rig, 1);

        // Packet 1 fills the adapter's next buffer from its first transfer; packet 2 broadcasts
        // it to everyone. Later replies in packet 1 are outside every player's SIZE=1 slot.
        rig.transfer(0x11, 0x22, 0x33, 0x44);
        rig.transfer(0, 0, 0, 0);
        rig.transfer(0, 0, 0, 0);
        rig.transfer(0, 0, 0, 0);

        for (int expected : new int[]{0x11, 0x22, 0x33, 0x44}) {
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

        for (int player = 1; player <= 4; player++) {
            for (int dataByte = 0; dataByte < 4; dataByte++) {
                int expected = player * 0x10 + dataByte;
                assertArrayEquals(new int[]{expected, expected, expected, expected},
                        rig.transfer(0, 0, 0, 0));
            }
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

    private static void enterTransmission(Rig rig, int size) {
        rig.transfer(size, size, size, size);
        rig.transfer(0x88, 0x88, 0x88, 0x88);
        rig.transfer(0x88, 0x88, 0x88, 0x88);
        rig.transfer(0x10, 0x10, 0x10, 0x10);

        // Enter transmission mode with the command spanning the ping packet boundary, as real
        // software does after receiving FE and loading the first AA reply.
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(new int[]{0xcc, 0xcc, 0xcc, 0xcc},
                    rig.transfer(0, 0, 0, 0));
        }
    }

    private static final class Rig {
        private final SerialPort[] ports = new SerialPort[FourPlayerAdapter.PLAYER_COUNT];

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
            int[] result = new int[ports.length];
            for (int i = 0; i < ports.length; i++) {
                result[i] = ports[i].getByte(0xff01);
            }
            return result;
        }
    }
}
