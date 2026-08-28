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

public class RevengeGatorLinkRolePatchTest {

    @Test
    public void detectsOnlyTheKnownLinkNegotiationRoutine() throws IOException {
        Rom rom = new Rom(revengeGatorRom());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.REVENGE_GATOR_LINK_ROLE_PATCH));

        byte[] changed = revengeGatorRom();
        changed[0x0216] = 0;
        assertFalse(new Rom(changed).getCartridgeProperties().has(
                CartridgeProperties.Feature.REVENGE_GATOR_LINK_ROLE_PATCH));
    }

    @Test
    public void assignsOppositeNegotiationBranchesToPairedPlayers() throws IOException {
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy primary = gameboy(revengeGatorRom());
             Gameboy secondary = gameboy(revengeGatorRom())) {
            primary.init(EventBus.NULL_EVENT_BUS, first, null);
            secondary.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0x3e, primary.getAddressSpace().getByte(0x0214));
            assertEquals(0x81, primary.getAddressSpace().getByte(0x0215));
            assertEquals(0x18, secondary.getAddressSpace().getByte(0x0214));
            assertEquals(0x17, secondary.getAddressSpace().getByte(0x0215));
        }
    }

    @Test
    public void doesNotPatchAnotherCartridgeOnTheSecondaryEndpoint() throws IOException {
        byte[] data = revengeGatorRom();
        data[0x0134] = 'X';
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy gameboy = gameboy(data)) {
            gameboy.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0x3e, gameboy.getAddressSpace().getByte(0x0214));
            assertEquals(0x81, gameboy.getAddressSpace().getByte(0x0215));
        }
    }

    private static Gameboy gameboy(byte[] data) throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(data))
                .setSupportBatterySave(false)
                .build();
    }

    private static byte[] revengeGatorRom() {
        byte[] data = new byte[0x10000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        byte[] title = "PINBALL".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0146] = 0x00;
        data[0x0147] = 0x01;
        data[0x0148] = 0x01;
        data[0x0149] = 0x00;
        int[] linkRoleNegotiation = {
                0x3e, 0x81, 0xe0, 0xc9, 0xcd, 0x7a, 0x02, 0x21,
                0x88, 0x77, 0x3e, 0x0a, 0xcd, 0xac, 0x02, 0x30,
                0x30, 0x21, 0x88, 0x77, 0x11, 0x5a, 0xa5, 0x18,
                0x17, 0x3e, 0x41, 0xe0, 0xc9, 0xcd, 0x7a, 0x02,
                0x21, 0x77, 0x88, 0x3e, 0x0a, 0xcd, 0xac, 0x02
        };
        for (int i = 0; i < linkRoleNegotiation.length; i++) {
            data[0x0214 + i] = (byte) linkRoleNegotiation[i];
        }
        return data;
    }
}
