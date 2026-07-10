package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.type.Datel;

import org.junit.Test;

import java.io.IOException;

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
        rom[0x7fe1] = (byte) 0x5a; // a marker where the AR keeps its bank register
        return rom;
    }

    /** An MBC5 slot game with a distinct marker byte in each 16 KB bank. */
    private static byte[] mbcGameRom() {
        byte[] rom = new byte[0x40000]; // 256 KB, 16 banks
        int[] logo = {0xCE, 0xED, 0x66, 0x66};
        for (int i = 0; i < logo.length; i++) {
            rom[0x104 + i] = (byte) logo[i];
        }
        rom[0x134] = 'G';
        rom[0x147] = 0x19; // MBC5
        rom[0x148] = 0x04; // 256 KB
        for (int bank = 0; bank < 16; bank++) {
            rom[bank * 0x4000 + 0x0100] = (byte) (0xB0 + bank);
        }
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
        // writes to 0x7000-0x77FF are captured (the vblank hook seed) but reads keep
        // serving the flash window; the top 2 KB reads back what was written
        cart.setByte(0x7000, 0xd9);
        assertEquals(0xA1, cart.getByte(0x7000)); // still the 0x6000-window flash bank
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
    public void launchHandsOffTheBusForGood() throws IOException {
        Cartridge cart = build();
        Cartridge game = new Cartridge(new Rom(gameRom()), Battery.NULL_BATTERY);
        cart.getDatel().setSlotCartridge(game.getMemoryController(), true);
        eu.rekawek.coffeegb.core.events.EventBusImpl bus =
                new eu.rekawek.coffeegb.core.events.EventBusImpl(null, null, false);
        cart.init(bus);
        java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        bus.register(e -> launches.incrementAndGet(), eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent.class);

        // before the launch the AR is on the bus: its fake logo, register file visible
        assertEquals(0x44, cart.getByte(0x0104));
        assertEquals(0x02, cart.getByte(0x7fe1));

        // the flash-init restart stub: 0x7FE4 bit 0 arms, the 0x7FF4 write hands off
        cart.setByte(0x7fe4, 0x11);
        cart.setByte(0x7ff4, 0x10);
        assertEquals(1, launches.get());

        // now the game owns the whole bus, register-file addresses included (they are
        // ordinary ROM to the game); the AR is electrically gone
        assertEquals(0xCE, cart.getByte(0x0104));
        assertEquals('G', cart.getByte(0x0134));
        assertEquals(0x5a, cart.getByte(0x7fe1)); // the game's ROM there, not the AR reg (0x02)
    }

    @Test
    public void runLaunchHandsOffTheBusForGood() throws IOException {
        // the Action Replay Online "run launch" path: the 0xFF80 stub routes the bus with
        // 0x7FE7=02 then 0x7FE6=07 and jumps into the game without a console reset. (The
        // Xtreme's cart uses the 0x7FF4 reset path instead; both must hand off.)
        Cartridge cart = build();
        Cartridge game = new Cartridge(new Rom(gameRom()), Battery.NULL_BATTERY);
        cart.getDatel().setSlotCartridge(game.getMemoryController(), false);
        eu.rekawek.coffeegb.core.events.EventBusImpl bus =
                new eu.rekawek.coffeegb.core.events.EventBusImpl(null, null, false);
        cart.init(bus);
        java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        bus.register(e -> launches.incrementAndGet(), eu.rekawek.coffeegb.core.memory.cart.type.Datel.LaunchEvent.class);

        assertEquals(0x44, cart.getByte(0x0104)); // the AR's fake logo, pre-launch

        // 0x7FE6=07 only routes the bus once 0x7FE7 already holds 02
        cart.setByte(0x7fe6, 0x07);
        assertEquals(0, launches.get());
        cart.setByte(0x7fe7, 0x02);
        cart.setByte(0x7fe6, 0x07);
        assertEquals(1, launches.get());

        // the game now owns the whole bus
        assertEquals(0xCE, cart.getByte(0x0104));
        assertEquals('G', cart.getByte(0x0134));
        assertEquals(0x5a, cart.getByte(0x7fe1));
    }

    @Test
    public void slotBankRegisterWriteThrough() throws IOException {
        // 0x7FE5 bit 0 is a write-through: the boot pre-sets the slot game's MBC bank
        // register through it without exposing the slot for reads
        Cartridge cart = build();
        Cartridge game = new Cartridge(new Rom(mbcGameRom()), Battery.NULL_BATTERY);
        cart.getDatel().setSlotCartridge(game.getMemoryController(), false);

        cart.setByte(0x7fe5, 0x01);      // enable write-through
        cart.setByte(0x2000, 0x03);      // MBC5 low-bank register -> the game's bank 3
        cart.setByte(0x7fe5, 0x00);      // back to normal
        // reads still serve the AR (its fake logo), not the slot
        assertEquals(0x44, cart.getByte(0x0104));

        // peek the slot: the write reached the game's mapper, so its 0x4000 window maps
        // bank 3 (open the peek window, then close it)
        cart.setByte(0x7fe5, 0x10);
        assertEquals(0xB3, cart.getByte(0x4100)); // bank-3 marker
        cart.setByte(0x7fe5, 0x00);
    }

    @Test
    public void flashIdProgramAndErase() throws IOException {
        Cartridge cart = build();
        // JEDEC software-ID mode (the boot code identifies its flash chip)
        cart.setByte(0x5555, 0xaa); // via the 0x4000 window, bank 2: flash 0x5555
        cart.setByte(0x2aaa, 0x55);
        cart.setByte(0x5555, 0x90);
        assertEquals(0xbf, cart.getByte(0x0000)); // SST manufacturer
        assertEquals(0xb5, cart.getByte(0x0001)); // device
        cart.setByte(0x5555, 0xaa);
        cart.setByte(0x2aaa, 0x55);
        cart.setByte(0x5555, 0xf0); // reset
        assertEquals(0xA0, cart.getByte(0x1000)); // normal reads again

        // sector erase of flash sector 0x3000 (programming can only clear bits)
        cart.setByte(0x5555, 0xaa);
        cart.setByte(0x2aaa, 0x55);
        cart.setByte(0x5555, 0x80);
        cart.setByte(0x5555, 0xaa);
        cart.setByte(0x2aaa, 0x55);
        cart.setByte(0x3000, 0x30);
        assertEquals(0xff, cart.getByte(0x3000));
        assertEquals(0xff, cart.getByte(0x3001));

        // byte program into the erased sector
        cart.setByte(0x5555, 0xaa);
        cart.setByte(0x2aaa, 0x55);
        cart.setByte(0x5555, 0xa0);
        cart.setByte(0x3000, 0x12);
        assertEquals(0x12, cart.getByte(0x3000));
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
