package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint;
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

    @Test
    public void bit4ReflectsTheCgbLinkPortInputPin() {
        InfraredPort port = new InfraredPort(true, new SpeedMode(true));
        port.setSerialEndpoint(new LowSerialInput());

        assertEquals(0x2e, port.getByte(0xff56));
    }

    @Test
    public void performanceQuietSpanAdmitsOnlyDisconnectedPeerCable() {
        InfraredPort port = new InfraredPort(true, new SpeedMode(true));
        port.setSerialEndpoint(new Peer2PeerSerialEndpoint());
        assertEquals(3, port.performanceQuietSpanLimit(3));

        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        first.init(new Peer2PeerSerialEndpoint());
        port.setSerialEndpoint(first);
        assertEquals(0, port.performanceQuietSpanLimit(3));

        port.setSerialEndpoint(new LowSerialInput());
        assertEquals(0, port.performanceQuietSpanLimit(3));
    }

    @Test
    public void settledHaltSpanRejectsAnyAttachedInfraredEndpoint() {
        InfraredPort port = new InfraredPort(true, new SpeedMode(true));
        port.init(EventBus.NULL_EVENT_BUS, new Peer2PeerInfraredEndpoint());
        assertEquals(0, port.performanceSettledHaltSpanLimit(54));
    }

    private static class LowSerialInput implements SerialEndpoint {
        @Override
        public boolean isSerialInputHigh() {
            return false;
        }

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
            return 0;
        }

        @Override
        public ComponentState<SerialEndpoint> captureState() {
            return null;
        }

        @Override
        public void restoreState(ComponentState<SerialEndpoint> memento) {
        }
    }
}
