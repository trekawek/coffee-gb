package eu.rekawek.coffeegb.core.serial;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Peer2PeerSerialEndpointTest {

    @Test
    public void reportsWhetherTrafficDependsOnALivePeer() {
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();

        assertFalse(first.isConnected());
        assertFalse(second.isConnected());

        first.init(second);

        assertTrue(first.isConnected());
        assertTrue(second.isConnected());
        assertEquals(0, first.linkPlayerIndex());
        assertEquals(1, second.linkPlayerIndex());
    }
}
