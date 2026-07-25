package eu.rekawek.coffeegb.core.serial;

import eu.rekawek.coffeegb.core.cpu.InterruptManager;
import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.ComponentState;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class NaiveSerialPortClockTest {

    @Test
    public void internalTransferDelayUsesTheOwningCustomClock() {
        ClockSpec custom = new ClockSpec(8_192, 60, 1);
        CountingEndpoint endpoint = new CountingEndpoint();
        NaiveSerialPort serial = new NaiveSerialPort(
                new InterruptManager(false), false, new SpeedMode(false), custom);
        serial.init(endpoint);
        serial.setByte(0xff01, 0x5a);
        serial.setByte(0xff02, 0x81);

        for (int tick = 0; tick < 8; tick++) {
            serial.tick();
        }
        assertEquals(0, endpoint.sentBytes);
        serial.tick(); // preserve the established divider++ == byteTicks boundary
        assertEquals(1, endpoint.sentBytes);
        assertEquals(0xa5, serial.getByte(0xff01));
    }

    private static final class CountingEndpoint implements SerialEndpoint {
        private int sentBytes;

        @Override
        public void setSb(int sb) {
        }

        @Override
        public int recvBit() {
            return -1;
        }

        @Override
        public void startSending() {
        }

        @Override
        public int sendBit() {
            return 1;
        }

        @Override
        public int sendByte() {
            sentBytes++;
            return 0xa5;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> state) {
        }
    }
}
