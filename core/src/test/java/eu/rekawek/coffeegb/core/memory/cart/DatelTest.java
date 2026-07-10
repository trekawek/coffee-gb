package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.Datel;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

/**
 * The Datel "Orbit V2" Action Replay mapper (issue #66): 8 KB banking, ASIC RAM, the
 * status page, and the pass-through game slot.
 */
public class DatelTest {

    /** A 128 KB image with a bad logo (Datel detection) and a marker byte per 8 KB bank. */
    private static byte[] datelRom() {
        byte[] rom = new byte[0x20000];
        rom[0x100] = 0x00;
        rom[0x101] = (byte) 0xc3;
        rom[0x104] = 0x44; // deliberately not the Nintendo logo
        rom[0x147] = 0x00; // "ROM only"
        rom[0x148] = 0x02;
        for (int bank = 0; bank < 16; bank++) {
            rom[bank * 0x2000 + 0x1000] = (byte) (0xA0 + bank);
        }
        return rom;
    }

    private static byte[] gameRom() {
        byte[] rom = new byte[0x8000];
        int[] logo = {0xCE, 0xED, 0x66, 0x66};
        for (int i = 0; i < logo.length; i++) {
            rom[0x104 + i] = (byte) logo[i];
        }
        rom[0x134] = 'G';
        rom[0x147] = 0x00;
        return rom;
    }

    private static Cartridge build() throws IOException {
        return new Cartridge(new Rom(datelRom()), Battery.NULL_BATTERY);
    }

    @Test
    public void detectedAsDatel() throws IOException {
        assertNotNull(build().getDatel());
        assertEquals(true, Cartridge.isDatel(new Rom(datelRom())));
    }

    @Test
    public void eightKilobyteWindows() throws IOException {
        Cartridge cart = build();
        // power-on: 0x4000 window holds bank 2 (identity), 0x6000 window bank 1
        assertEquals(0xA2, cart.getByte(0x5000));
        assertEquals(0xA1, cart.getByte(0x7000));
        cart.setByte(0x7fe1, 5);
        assertEquals(0xA5, cart.getByte(0x5000));
        cart.setByte(0x7fe0, 3);
        assertEquals(0xA3, cart.getByte(0x7000));
        // the fixed low area always maps the first 16 KB
        assertEquals(0xA0, cart.getByte(0x1000));
        assertEquals(0xA1, cart.getByte(0x3000));
    }

    @Test
    public void asicRamAndRegisterFile() throws IOException {
        Cartridge cart = build();
        cart.setByte(0x7900, 0x42);
        assertEquals(0x42, cart.getByte(0x7900));
        cart.setByte(0x7fe2, 0x37);
        assertEquals(0x37, cart.getByte(0x7fe2));
        // the status page reads pulled up (0x7FEE bit 0 boots the software to the launch)
        assertEquals(0xff, cart.getByte(0x7fee));
    }

    @Test
    public void slotPeekAndOpenBus() throws IOException {
        Cartridge cart = build();
        // empty slot: selecting it reads open bus
        cart.setByte(0x7fe5, 0x10);
        assertEquals(0xff, cart.getByte(0x0104));
        cart.setByte(0x7fe5, 0x00);

        Cartridge game = new Cartridge(new Rom(gameRom()), Battery.NULL_BATTERY);
        cart.getDatel().setSlotCartridge(game.getMemoryController(), true);
        cart.setByte(0x7fe5, 0x10);
        assertEquals(0xCE, cart.getByte(0x0104)); // the game's real logo
        assertEquals('G', cart.getByte(0x0134));
        cart.setByte(0x7fe5, 0x00);
        assertEquals(0x44, cart.getByte(0x0104)); // back to the AR's fake logo
    }

    @Test
    public void launchLatchFiresTheResetEvent() throws IOException {
        Cartridge cart = build();
        Cartridge game = new Cartridge(new Rom(gameRom()), Battery.NULL_BATTERY);
        cart.getDatel().setSlotCartridge(game.getMemoryController(), true);
        EventBusImpl bus = new EventBusImpl(null, null, false);
        cart.init(bus);
        AtomicInteger launches = new AtomicInteger();
        bus.register(e -> launches.incrementAndGet(), Datel.LaunchEvent.class);

        // the game-launch stub's sequence: latch the slot, then the final 0x7FF4 write
        cart.setByte(0x7fe4, 0x11);
        cart.setByte(0x7ff5, 0x10);
        cart.setByte(0x7ff2, 0x01);
        cart.setByte(0x7ff2, 0x00);
        cart.setByte(0x7ff4, 0x10);
        assertEquals(1, launches.get());
        // the slot stays latched: the game occupies the whole bus
        assertEquals(0xCE, cart.getByte(0x0104));
    }

    @Test
    public void mementoRoundTrip() throws IOException {
        Cartridge cart = build();
        cart.setByte(0x7fe1, 7);
        cart.setByte(0x7a00, 0x55);
        Memento<Cartridge> memento = cart.saveToMemento();

        Cartridge other = build();
        other.setByte(0x7fe1, 3);
        other.restoreFromMemento(memento);
        assertEquals(0xA7, other.getByte(0x5000));
        assertEquals(0x55, other.getByte(0x7a00));
    }
}
