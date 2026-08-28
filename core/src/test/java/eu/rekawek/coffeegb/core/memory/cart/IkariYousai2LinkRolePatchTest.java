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

public class IkariYousai2LinkRolePatchTest {

    @Test
    public void detectsOnlyTheKnownLinkNegotiationRoutine() throws IOException {
        Rom rom = new Rom(ikariYousai2Rom());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.IKARI_YOUSAI_2_LINK_ROLE_PATCH));

        byte[] changed = ikariYousai2Rom();
        changed[0x08ca] = 0;
        assertFalse(new Rom(changed).getCartridgeProperties().has(
                CartridgeProperties.Feature.IKARI_YOUSAI_2_LINK_ROLE_PATCH));
    }

    @Test
    public void makesOnlyTheSecondLinkedPlayerUseTheExternalClockRole() throws IOException {
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy primary = gameboy(ikariYousai2Rom());
             Gameboy secondary = gameboy(ikariYousai2Rom())) {
            primary.init(EventBus.NULL_EVENT_BUS, first, null);
            secondary.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0x20, primary.getAddressSpace().getByte(0x08fd));
            assertEquals(0x06, primary.getAddressSpace().getByte(0x08fe));
            assertEquals(0x00, secondary.getAddressSpace().getByte(0x08fd));
            assertEquals(0x00, secondary.getAddressSpace().getByte(0x08fe));
            assertEquals(0xf0, secondary.getAddressSpace().getByte(0x08f9));
        }
    }

    @Test
    public void leavesAnotherCartridgeUnchangedOnTheSecondEndpoint() throws IOException {
        byte[] data = ikariYousai2Rom();
        data[0x0134] = 'X';
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy gameboy = gameboy(data)) {
            gameboy.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0x20, gameboy.getAddressSpace().getByte(0x08fd));
            assertEquals(0x06, gameboy.getAddressSpace().getByte(0x08fe));
        }
    }

    private static Gameboy gameboy(byte[] data) throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(data))
                .setSupportBatterySave(false)
                .build();
    }

    private static byte[] ikariYousai2Rom() {
        byte[] data = new byte[0x20000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        byte[] title = "FORTIFIED ZONE2".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0146] = 0x00;
        data[0x0147] = 0x01;
        data[0x0148] = 0x02;
        data[0x0149] = 0x00;
        int[] linkRoleNegotiation = {
                0x7e, 0xcb, 0x47, 0x28, 0x08, 0xf0, 0x01, 0xfe,
                0x25, 0x28, 0x0c, 0x18, 0x13, 0xcb, 0x7f, 0x20,
                0x0f, 0xf0, 0x01, 0xfe, 0x4a, 0x20, 0x09, 0x7e,
                0xe6, 0x01, 0xe0, 0xd2, 0xaf, 0xe0, 0x01, 0xc9,
                0x3e, 0xff, 0xe0, 0xd2, 0xcb, 0xbe, 0xf0, 0xd4,
                0x3c, 0xe0, 0xd4, 0xe6, 0x03, 0x20, 0x0c, 0xf0,
                0x04, 0xcb, 0x47, 0x20, 0x06, 0x36, 0x00, 0x3e,
                0x25, 0x18, 0x04, 0x36, 0x01, 0x3e, 0x4a, 0xe0,
                0x01, 0xcb, 0xfe, 0xc9
        };
        for (int i = 0; i < linkRoleNegotiation.length; i++) {
            data[0x08ca + i] = (byte) linkRoleNegotiation[i];
        }
        return data;
    }
}
