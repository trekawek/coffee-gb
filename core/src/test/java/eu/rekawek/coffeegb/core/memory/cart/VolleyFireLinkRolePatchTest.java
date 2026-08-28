package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.serial.Peer2PeerSerialEndpoint;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class VolleyFireLinkRolePatchTest {

    @Test
    public void detectsOnlyTheKnownLinkNegotiationRoutine() throws IOException {
        Rom rom = new Rom(volleyFireRom());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.VOLLEY_FIRE_LINK_ROLE_PATCH));

        byte[] changed = volleyFireRom();
        changed[0x2288] = 0;
        assertFalse(new Rom(changed).getCartridgeProperties().has(
                CartridgeProperties.Feature.VOLLEY_FIRE_LINK_ROLE_PATCH));
    }

    @Test
    public void returnsTheSecondLinkedPlayerToTheExistingListenerLoop() throws IOException {
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy primary = gameboy(volleyFireRom());
             Gameboy secondary = gameboy(volleyFireRom())) {
            primary.init(EventBus.NULL_EVENT_BUS, first, null);
            secondary.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0xcd, primary.getAddressSpace().getByte(0x2289));
            assertEquals(0x96, primary.getAddressSpace().getByte(0x228a));
            assertEquals(0x0e, primary.getAddressSpace().getByte(0x228b));
            assertEquals(0x28, primary.getAddressSpace().getByte(0x228c));
            assertEquals(0xf1, secondary.getAddressSpace().getByte(0x2289));
            assertEquals(0xc3, secondary.getAddressSpace().getByte(0x228a));
            assertEquals(0x43, secondary.getAddressSpace().getByte(0x228b));
            assertEquals(0x21, secondary.getAddressSpace().getByte(0x228c));
        }
    }

    @Test
    public void leavesAnotherCartridgeUnchangedOnTheSecondEndpoint() throws IOException {
        byte[] data = volleyFireRom();
        data[0x0134] = 'X';
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy gameboy = gameboy(data)) {
            gameboy.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0xcd, gameboy.getAddressSpace().getByte(0x2289));
            assertEquals(0x96, gameboy.getAddressSpace().getByte(0x228a));
            assertEquals(0x0e, gameboy.getAddressSpace().getByte(0x228b));
            assertEquals(0x28, gameboy.getAddressSpace().getByte(0x228c));
        }
    }

    private static Gameboy gameboy(byte[] data) throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(data))
                .setSupportBatterySave(false)
                .build();
    }

    private static byte[] volleyFireRom() {
        byte[] data = new byte[0x8000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        byte[] title = "VOLLEY FIRE".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0146] = 0x00;
        data[0x0147] = 0x00;
        data[0x0148] = 0x00;
        data[0x0149] = 0x00;
        int[] linkRoleNegotiation = {
                0xf0, 0x9c, 0xfe, 0x01, 0x20, 0x17, 0xf0, 0x9d,
                0xb7, 0x28, 0x0e, 0xcd, 0x96, 0x0e, 0x28, 0x09,
                0xf1, 0x3e, 0x80, 0x1e, 0x00, 0xc7, 0xc3, 0x43,
                0x21, 0x3e, 0x01, 0xe0, 0x9d
        };
        for (int i = 0; i < linkRoleNegotiation.length; i++) {
            data[0x227e + i] = (byte) linkRoleNegotiation[i];
        }
        return data;
    }
}
