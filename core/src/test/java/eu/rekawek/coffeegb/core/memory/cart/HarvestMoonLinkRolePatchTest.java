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

public class HarvestMoonLinkRolePatchTest {

    @Test
    public void detectsOnlyTheKnownLinkNegotiationRoutine() throws IOException {
        Rom rom = new Rom(harvestMoonRom());

        assertTrue(rom.getCartridgeProperties().has(
                CartridgeProperties.Feature.HARVEST_MOON_LINK_ROLE_PATCH));

        byte[] changed = harvestMoonRom();
        changed[0x7e767] = 0;
        assertFalse(new Rom(changed).getCartridgeProperties().has(
                CartridgeProperties.Feature.HARVEST_MOON_LINK_ROLE_PATCH));
    }

    @Test
    public void keepsTheSecondLinkedPlayerListeningDuringTheFirstPlayersTimeout()
            throws IOException {
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy primary = gameboy(harvestMoonRom());
             Gameboy secondary = gameboy(harvestMoonRom())) {
            primary.init(EventBus.NULL_EVENT_BUS, first, null);
            secondary.init(EventBus.NULL_EVENT_BUS, second, null);
            primary.getAddressSpace().setByte(0x2000, 0x1f);
            secondary.getAddressSpace().setByte(0x2000, 0x1f);

            assertEquals(0x3e, primary.getAddressSpace().getByte(0x6768));
            assertEquals(0xc9, secondary.getAddressSpace().getByte(0x6768));
            assertEquals(0xfe, secondary.getAddressSpace().getByte(0x6769));
            assertEquals(0xe0, secondary.getAddressSpace().getByte(0x676a));

            secondary.getAddressSpace().setByte(0x2000, 0x02);
            assertEquals(0x42, secondary.getAddressSpace().getByte(0x6768));
        }
    }

    @Test
    public void leavesAnotherCartridgeUnchangedOnTheSecondEndpoint() throws IOException {
        byte[] data = harvestMoonRom();
        data[0x0134] = 'X';
        Peer2PeerSerialEndpoint first = new Peer2PeerSerialEndpoint();
        Peer2PeerSerialEndpoint second = new Peer2PeerSerialEndpoint();
        first.init(second);

        try (Gameboy gameboy = gameboy(data)) {
            gameboy.init(EventBus.NULL_EVENT_BUS, second, null);
            gameboy.getAddressSpace().setByte(0x2000, 0x1f);

            assertEquals(0x3e, gameboy.getAddressSpace().getByte(0x6768));
        }
    }

    private static Gameboy gameboy(byte[] data) throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(data))
                .setSupportBatterySave(false)
                .build();
    }

    private static byte[] harvestMoonRom() {
        byte[] data = new byte[0x80000];
        data[0x0100] = 0x00;
        data[0x0101] = (byte) 0xc3;
        data[0x0102] = 0x50;
        data[0x0103] = 0x01;
        byte[] title = "HARVEST-MOON GB".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, data, 0x0134, title.length);
        data[0x0143] = 0x00;
        data[0x0146] = 0x03;
        data[0x0147] = 0x03;
        data[0x0148] = 0x04;
        data[0x0149] = 0x02;
        int[] linkRoleNegotiation = {
                0x06, 0x80, 0x21, 0x06, 0xd6, 0x3e, 0x5e, 0xe0,
                0x01, 0x77, 0x78, 0xe0, 0x02, 0xc9, 0xaf, 0xe0,
                0x01, 0xea, 0x06, 0xd6, 0xea, 0x01, 0xd6, 0xc9,
                0x3e, 0xfe, 0xe0, 0x01, 0x3e, 0x81, 0xe0, 0x02,
                0xc9
        };
        for (int i = 0; i < linkRoleNegotiation.length; i++) {
            data[0x7e750 + i] = (byte) linkRoleNegotiation[i];
        }
        data[2 * 0x4000 + 0x2768] = 0x42;
        return data;
    }
}
