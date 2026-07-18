package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.cpu.SpeedMode;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memento.Memento;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class Peer2PeerInfraredEndpointTest {

    private final InfraredPort firstPort = new InfraredPort(true, new SpeedMode(true));

    private final InfraredPort secondPort = new InfraredPort(true, new SpeedMode(true));

    @Before
    public void setUp() {
        Peer2PeerInfraredEndpoint firstEndpoint = new Peer2PeerInfraredEndpoint();
        Peer2PeerInfraredEndpoint secondEndpoint = new Peer2PeerInfraredEndpoint();
        firstEndpoint.init(secondEndpoint);
        firstPort.init(new EventBusImpl(), firstEndpoint);
        secondPort.init(new EventBusImpl(), secondEndpoint);
    }

    @Test
    public void lightFromPeerReachesEnabledSensor() {
        firstPort.setByte(0xff56, 0x01);
        secondPort.setByte(0xff56, 0xc0);

        assertEquals(0, secondPort.getByte(0xff56) & 0x02);

        firstPort.setByte(0xff56, 0x00);
        assertEquals(0x02, secondPort.getByte(0xff56) & 0x02);
    }

    @Test
    public void peerLightIsIgnoredWhileSensorIsDisabled() {
        firstPort.setByte(0xff56, 0x01);
        secondPort.setByte(0xff56, 0x00);

        assertEquals(0x02, secondPort.getByte(0xff56) & 0x02);
    }

    @Test
    public void communicationWorksInBothDirections() {
        firstPort.setByte(0xff56, 0xc0);
        secondPort.setByte(0xff56, 0x01);

        assertEquals(0, firstPort.getByte(0xff56) & 0x02);
    }

    @Test
    public void restoringPortStateRestoresEmittedLight() {
        firstPort.setByte(0xff56, 0x01);
        Memento<InfraredPort> lightOn = firstPort.saveToMemento();
        firstPort.setByte(0xff56, 0x00);
        secondPort.setByte(0xff56, 0xc0);
        assertEquals(0x02, secondPort.getByte(0xff56) & 0x02);

        firstPort.restoreFromMemento(lightOn);

        assertEquals(0, secondPort.getByte(0xff56) & 0x02);
    }

    @Test
    public void closingPortTurnsOffEmittedLight() {
        firstPort.setByte(0xff56, 0x01);
        secondPort.setByte(0xff56, 0xc0);
        assertEquals(0, secondPort.getByte(0xff56) & 0x02);

        firstPort.close();

        assertEquals(0x02, secondPort.getByte(0xff56) & 0x02);
    }
}
