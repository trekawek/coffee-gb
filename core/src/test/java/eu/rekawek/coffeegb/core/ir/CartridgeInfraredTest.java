package eu.rekawek.coffeegb.core.ir;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;
import org.junit.Test;
import static org.junit.Assert.*;

public class CartridgeInfraredTest {
    @Test
    public void huc1AndHuc3ExchangeLightOnDmgAndCgbAndRestoreOutputs() throws Exception {
        for (GameboyType type : new GameboyType[]{GameboyType.DMG, GameboyType.CGB}) {
            Peer2PeerInfraredEndpoint first = new Peer2PeerInfraredEndpoint();
            Peer2PeerInfraredEndpoint second = new Peer2PeerInfraredEndpoint();
            first.init(second);
            try (Gameboy a = machine(0xff, type); Gameboy b = machine(0xfe, type)) {
                a.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT, first, null);
                b.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT, second, null);
                var left = a.getAddressSpace();
                var right = b.getAddressSpace();
                left.setByte(0, 0x0e);
                right.setByte(0, 0x0e);
                assertEquals(0xc0, right.getByte(0xa000));
                left.setByte(0xbfff, 0x81);
                assertEquals(0xc1, right.getByte(0xa000));
                assertEquals(0xc0, left.getByte(0xa000)); // No loopback.
                right.setByte(0xa100, 1);
                assertEquals(0xc1, left.getByte(0xbfff));
                var state = a.captureState();
                left.setByte(0xa000, 0);
                assertEquals(0xc0, right.getByte(0xa000));
                a.restoreState(state);
                assertEquals(0xc1, right.getByte(0xa000));
                // Console RP must not overwrite the separate cartridge's output.
                left.setByte(0xff56, 0);
                assertEquals(0xc1, right.getByte(0xa000));
                a.setCartridgeInfraredEndpoint(InfraredEndpoint.NULL_ENDPOINT);
                assertEquals(0xc0, right.getByte(0xa000));
            }
        }
    }

    @Test
    public void ramAndRtcModesDoNotDriveInfrared() throws Exception {
        for (int mapper : new int[]{0xff, 0xfe}) {
            Peer2PeerInfraredEndpoint first = new Peer2PeerInfraredEndpoint();
            Peer2PeerInfraredEndpoint second = new Peer2PeerInfraredEndpoint();
            first.init(second);
            try (Gameboy gb = machine(mapper, GameboyType.DMG)) {
                gb.init(EventBus.NULL_EVENT_BUS, SerialEndpoint.NULL_ENDPOINT, first, null);
                var bus = gb.getAddressSpace();
                bus.setByte(0, 0x0a);
                bus.setByte(0xa000, 0x55);
                assertFalse(second.isLightOn());
                bus.setByte(0, 0x0e);
                bus.setByte(0xa000, 1);
                bus.setByte(0, 0x0a);
                assertEquals(0x55, bus.getByte(0xa000));
            }
            assertFalse(second.isLightOn());
        }
    }

    private Gameboy machine(int mapper, GameboyType type) throws Exception {
        byte[] rom = new byte[0x8000];
        rom[0x147] = (byte) mapper;
        rom[0x149] = 3;
        return new Gameboy.GameboyConfiguration(new Rom(rom)).setGameboyType(type)
                .setSupportBatterySave(false).build();
    }
}
