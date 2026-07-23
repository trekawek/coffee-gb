package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotEquals;

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
    public void transmissionBroadcastsPreviousPacketWithoutCrossPacketSplicing() {
        Rig rig = new Rig();

        enterTransmission(rig, 1);

        // Games load their first data byte after receiving byte 0. Packet 1 captures that reply
        // during byte 1, and packet 2 broadcasts it without substituting a byte from packet 2.
        rig.transfer(0, 0, 0, 0);
        rig.transfer(0x11, 0x22, 0x33, 0x44);
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

        // Byte 0 is the pipeline slot in which software receives old data and loads byte 1.
        rig.transfer(0, 0, 0, 0);
        for (int dataByte = 0; dataByte < 4; dataByte++) {
            rig.transfer(0x10 + dataByte, 0x20 + dataByte,
                    0x30 + dataByte, 0x40 + dataByte);
        }
        // The remaining eleven transfers belong to the other players' outgoing slots. Their
        // replies must not displace or overwrite the four bytes each player submitted above.
        for (int i = 5; i < 16; i++) {
            rig.transfer(0xee, 0xee, 0xee, 0xee);
        }

        int[] physicalReplyStream = {
                0x10, 0x11, 0x12, 0x13,
                0x20, 0x21, 0x22, 0x23,
                0x30, 0x31, 0x32, 0x33,
                0x40, 0x41, 0x42, 0x43
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
    public void transmissionCommandFinishesCurrentPingAndKeepsConfiguredRate() {
        Rig rate28 = new Rig();
        enterTransmission(rate28, 4, 0x28);
        Rig rate38 = new Rig();
        enterTransmission(rate38, 4, 0x38);

        // RATE affects inter-byte timing. Distinct configured rates must remain distinct after the
        // crossed AA command instead of both latching its final 0xAA as a new rate.
        for (int i = 0; i < 32; i++) {
            assertArrayEquals(rate28.transfer(0, 0, 0, 0),
                    rate38.transfer(0, 0, 0, 0));
            assertNotEquals(rate28.lastTransferTicks, rate38.lastTransferTicks);
        }
    }

    private static void enterTransmission(Rig rig, int size) {
        enterTransmission(rig, size, 0x10);
    }

    private static void enterTransmission(Rig rig, int size, int rate) {
        configurePing(rig, size, rate);

        // The previous SIZE reply is still in SB for the next FE header. Three command bytes then
        // complete that ping packet; the fourth AA is transferred alongside the first CC.
        rig.transfer(size, size, size, size);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        rig.transfer(0xaa, 0xaa, 0xaa, 0xaa);
        for (int i = 0; i < 4; i++) {
            assertArrayEquals(new int[]{0xcc, 0xcc, 0xcc, 0xcc},
                    rig.transfer(i == 0 ? 0xaa : 0, 0, 0, 0));
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
