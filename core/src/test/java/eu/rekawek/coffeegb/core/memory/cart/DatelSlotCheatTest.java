package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.genie.AddPatches;
import eu.rekawek.coffeegb.core.genie.PatchFactory;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertEquals;

/**
 * The Action Replay's reason to exist: applying cheats to the game in its slot. A game runs
 * on the bus through the launched Datel cart, and a GameShark code entered through coffee-gb's
 * cheat system pokes the game's RAM (the read-intercept sits above the whole cartridge, so it
 * reaches whatever the AR has routed onto the bus). Issue #66.
 */
public class DatelSlotCheatTest {

    private static byte[] datelRom() {
        byte[] rom = new byte[0x20000];
        rom[0x100] = 0x00;
        rom[0x101] = (byte) 0xc3;
        rom[0x102] = 0x50;
        rom[0x103] = 0x01;
        rom[0x104] = 0x44; // bad logo -> detected as Datel
        rom[0x147] = 0x00;
        rom[0x148] = 0x02;
        // spin the CPU in place so the test drives the bus itself
        rom[0x150] = 0x18;
        rom[0x151] = (byte) 0xfe;
        return rom;
    }

    private static byte[] gameRom() {
        byte[] rom = new byte[0x8000];
        int[] logo = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D};
        for (int i = 0; i < logo.length; i++) {
            rom[0x104 + i] = (byte) logo[i];
        }
        rom[0x100] = 0x00;
        rom[0x101] = (byte) 0xc3;
        rom[0x102] = 0x50;
        rom[0x103] = 0x01;
        rom[0x150] = 0x18; // spin
        rom[0x151] = (byte) 0xfe;
        rom[0x147] = 0x00;
        return rom;
    }

    @Test
    public void gameSharkCodePokesTheSlotGamesRam() throws IOException {
        GameboyConfiguration cfg = new GameboyConfiguration(new Rom(datelRom()))
                .setSupportBatterySave(false)
                .setGameboyType(GameboyType.CGB)
                .setSlotRom(new Rom(gameRom()));
        Gameboy gb = cfg.build();
        EventBusImpl bus = new EventBusImpl(null, null, false);
        gb.init(bus, SerialEndpoint.NULL_ENDPOINT, null);
        AddressSpace mmu = gb.getAddressSpace();

        // hand the bus to the game (the launch stub's register sequence)
        mmu.setByte(0x7fe4, 0x11);
        mmu.setByte(0x7ff4, 0x10);
        for (int i = 0; i < 4; i++) {
            gb.tick(); // let the warm reset settle
        }

        // the game's WRAM starts empty
        mmu.setByte(0xc100, 0x00);
        assertEquals(0x00, mmu.getByte(0xc100));

        // GameShark 01 63 00 C1 -> always poke 0x63 into 0xC100
        bus.post(new AddPatches(PatchFactory.createPatches("016300C1")));
        assertEquals(0x63, mmu.getByte(0xc100));

        gb.stop();
    }
}
