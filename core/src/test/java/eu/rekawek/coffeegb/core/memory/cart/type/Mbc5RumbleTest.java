package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

/**
 * The MBC5 rumble motor (issue #93): on rumble carts (types 0x1C-0x1E) bit 3 of the
 * RAM-bank register drives the vibration motor, leaving bits 0-2 for the bank select.
 */
public class Mbc5RumbleTest {

    private static byte[] rom(int type) {
        byte[] rom = new byte[0x40000]; // 256 KB, 16 banks
        rom[0x147] = (byte) type;
        rom[0x148] = 0x04; // 256 KB
        rom[0x149] = 0x03; // 4 RAM banks
        for (int bank = 0; bank < 16; bank++) {
            rom[bank * 0x4000 + 0x0100] = (byte) (0xB0 + bank);
        }
        return rom;
    }

    private static Mbc5 build(int type, List<Boolean> motorLog) throws IOException {
        Mbc5 mbc = new Mbc5(new Rom(rom(type)), Battery.NULL_BATTERY);
        EventBusImpl bus = new EventBusImpl(null, null, false);
        bus.register(e -> motorLog.add(e.on()), RumbleEvent.class);
        mbc.init(bus);
        return mbc;
    }

    @Test
    public void motorBitPostsEvents() throws IOException {
        List<Boolean> log = new ArrayList<>();
        Mbc5 mbc = build(0x1c, log); // ROM_MBC5_RUMBLE

        mbc.setByte(0x4000, 0x08); // motor on
        mbc.setByte(0x4000, 0x08); // idempotent - no duplicate event
        mbc.setByte(0x4000, 0x00); // motor off
        assertEquals(List.of(true, false), log);
    }

    @Test
    public void motorBitIsNotPartOfTheRamBank() throws IOException {
        List<Boolean> log = new ArrayList<>();
        Mbc5 mbc = build(0x1e, log); // ROM_MBC5_RUMBLE_SRAM_BATTERY

        // select RAM bank 3 with the motor running: bit 3 must not leak into the bank
        mbc.setByte(0x0000, 0x0a); // enable RAM
        mbc.setByte(0x4000, 0x0b); // bank 3 (0b011) + motor (0b1000)
        mbc.setByte(0xa000, 0x77);
        // read it back from bank 3 with the motor off
        mbc.setByte(0x4000, 0x03);
        assertEquals(0x77, mbc.getByte(0xa000));
        assertEquals(List.of(true, false), log);
    }

    @Test
    public void nonRumbleCartTreatsBit3AsBankBits() throws IOException {
        List<Boolean> log = new ArrayList<>();
        Mbc5 mbc = build(0x1a, log); // ROM_MBC5_RAM, no rumble

        // without rumble, bit 3 is an ordinary RAM-bank bit and posts no motor event
        mbc.setByte(0x0000, 0x0a);
        mbc.setByte(0x4000, 0x08);
        assertEquals(List.of(), log);
    }
}
