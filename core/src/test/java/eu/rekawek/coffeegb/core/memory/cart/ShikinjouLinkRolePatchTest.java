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

public class ShikinjouLinkRolePatchTest {

    @Test
    public void detectsOnlyTheKnownLinkNegotiationRoutine() throws IOException {
        Rom rom = new Rom(shikinjouRom());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.SHIKINJOU_LINK_ROLE_PATCH));

        byte[] changed = shikinjouRom();
        changed[0x22ca] = 0;
        assertFalse(new Rom(changed).getCartridgeProperties().has(
                CartridgeProperties.Feature.SHIKINJOU_LINK_ROLE_PATCH));
    }

    @Test
    public void assignsTheExistingSlaveBranchToTheSecondLinkedPlayer() throws IOException {
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy primary = gameboy(shikinjouRom());
             Gameboy secondary = gameboy(shikinjouRom())) {
            primary.init(EventBus.NULL_EVENT_BUS, first, null);
            secondary.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0x3e, primary.getAddressSpace().getByte(0x22cb));
            assertEquals(0x54, primary.getAddressSpace().getByte(0x22cc));
            assertEquals(0xea, primary.getAddressSpace().getByte(0x22cd));
            assertEquals(0xc3, secondary.getAddressSpace().getByte(0x22cb));
            assertEquals(0xf3, secondary.getAddressSpace().getByte(0x22cc));
            assertEquals(0x22, secondary.getAddressSpace().getByte(0x22cd));
        }
    }

    @Test
    public void leavesAnotherCartridgeUnchangedOnTheSecondEndpoint() throws IOException {
        byte[] data = shikinjouRom();
        data[0x0134] = 'X';
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy gameboy = gameboy(data)) {
            gameboy.init(EventBus.NULL_EVENT_BUS, second, null);

            assertEquals(0x3e, gameboy.getAddressSpace().getByte(0x22cb));
            assertEquals(0x54, gameboy.getAddressSpace().getByte(0x22cc));
            assertEquals(0xea, gameboy.getAddressSpace().getByte(0x22cd));
        }
    }

    private static Gameboy gameboy(byte[] data) throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(data))
                .setSupportBatterySave(false)
                .build();
    }

    private static byte[] shikinjouRom() {
        byte[] data = new byte[0x10000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        byte[] title = "SHIKINJYO".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0146] = 0x00;
        data[0x0147] = 0x01;
        data[0x0148] = 0x01;
        data[0x0149] = 0x00;
        int[] linkRoleNegotiation = {
                0xc5, 0x3e, 0x01, 0xe0, 0x90, 0x3e, 0x54, 0xea,
                0xa1, 0xc0, 0xe0, 0x95, 0x3e, 0x01, 0xe0, 0x91,
                0xcd, 0x2e, 0x23, 0xcd, 0x4e, 0x23, 0xf0, 0x92,
                0xa7, 0x20, 0xfb, 0xfa, 0xa4, 0xc0, 0xfe, 0xff,
                0x28, 0x44, 0xfe, 0xaa, 0x20, 0x07, 0x3e, 0x01,
                0xcd, 0xed, 0x23, 0x18, 0x2e, 0x3e, 0xaa, 0xea,
                0xa1, 0xc0, 0xe0, 0x95, 0xaf, 0xe0, 0x91, 0xea,
                0xa4, 0xc0
        };
        for (int i = 0; i < linkRoleNegotiation.length; i++) {
            data[0x22c6 + i] = (byte) linkRoleNegotiation[i];
        }
        return data;
    }
}
