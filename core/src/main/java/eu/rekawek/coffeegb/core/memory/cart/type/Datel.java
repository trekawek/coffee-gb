package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.Event;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * The Datel "Orbit V2" mapper of the Action Replay / GameShark carts for the Game Boy
 * Color (Action Replay Online, Action Replay Xtreme; issue #66).
 *
 * <p>The ASIC banks the ROM in 8 KB units (the granularity FlashGBX uses to dump these
 * carts) through a register file at the top of the ROM space:
 *
 * <ul>
 *   <li>{@code 0x0000-0x3FFF} - fixed, the first 16 KB of ROM;</li>
 *   <li>{@code 0x4000-0x5FFF} - 8 KB window selected by {@code 0x7FE1};</li>
 *   <li>{@code 0x6000-0x7FFF} - 8 KB window selected by {@code 0x7FE0} (the boot code
 *       maps bank 1 here and the software's overlays/cheat lists execute from it);</li>
 *   <li>{@code 0x7800-0x7FFF} - 2 KB of battery-backed ASIC RAM overlays the top of the
 *       0x6000 window: the software builds its cheat/game lists here and runs code from
 *       it. The register file lives in this RAM at {@code 0x7FE0-0x7FE7};</li>
 *   <li>{@code 0x7FE2-0x7FE7} - control registers of the link/dongle hardware (values
 *       are stored and read back; the device itself is not emulated).</li>
 * </ul>
 */
public class Datel implements MemoryController {

    private final int[] rom;

    private final int romBanks8k;

    // 2 KB of battery-backed ASIC RAM at 0x7800-0x7FFF
    private final int[] ram = new int[0x800];

    private final boolean[] ramWritten = new boolean[0x800];

    private final Battery battery;

    private final int[] regs = new int[8];

    // the second register channel at 0x7FF0-0x7FF7 (written by the game-launch stub)
    private final int[] regsB = new int[8];

    // the game cartridge in the pass-through slot; the ASIC routes the bus to it while
    // register 0x7FE5 holds 0x10 (the AR software reads the game's header through it,
    // and launches the game over it)
    private transient MemoryController slot;

    private transient boolean slotNonCgb;

    private transient EventBus eventBus;

    /** Fired when the launch sequence pulses the console reset with the slot latched. */
    public static class LaunchEvent implements Event {
        public final boolean nonCgbGame;

        public LaunchEvent(boolean nonCgbGame) {
            this.nonCgbGame = nonCgbGame;
        }
    }

    @Override
    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    public void setSlotCartridge(MemoryController slot, boolean nonCgb) {
        this.slot = slot;
        this.slotNonCgb = nonCgb;
    }

    private boolean slotSelected() {
        // 0x7FE5=0x10 exposes the slot temporarily (the header peek); 0x7FE4 bit 0
        // latches it for the game launch (the HRAM stub writes 0x11 and jumps to 0x0100)
        return (regs[5] & 0x10) != 0 || (regs[4] & 0x01) != 0;
    }

    public Datel(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.romBanks8k = Math.max(1, this.rom.length / 0x2000);
        this.battery = battery;
        Arrays.fill(ram, 0xff);
        battery.loadRam(ram);
        regs[0] = 1;
        regs[1] = 2;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    private int bank(int reg) {
        return regs[reg] % romBanks8k;
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x7fe0 && address <= 0x7fe7) {
            // the register file stays writable in slot mode - the switch-back write
            // (0x7FE5=0) happens while the game cart occupies the bus
            regs[address - 0x7fe0] = value & 0xff;
            return;
        }
        if (address >= 0x7ff0 && address <= 0x7ff7) {
            regsB[address - 0x7ff0] = value & 0xff;
            if (address == 0x7ff4 && (regs[4] & 0x01) != 0 && eventBus != null) {
                // the game-launch stub's final register write: the ASIC latches the slot
                // onto the bus and pulses the console's reset line - the boot ROM runs
                // again and verifies the GAME's (real) logo, the trick these carts play
                eventBus.post(new LaunchEvent(slotNonCgb));
            }
            return;
        }
        if (slotSelected()) {
            if (slot != null) {
                slot.setByte(address, value);
            }
            return;
        }
        if (address >= 0x7800 && address < 0x8000) {
            ram[address - 0x7800] = value & 0xff;
            ramWritten[address - 0x7800] = true;
        }
        // other ROM-space writes hit mask ROM and are ignored
    }

    @Override
    public int getByte(int address) {
        if (slotSelected()) {
            // an empty slot reads as open bus
            return slot != null ? slot.getByte(address) : 0xff;
        }
        if (address >= 0xa000) {
            return 0xff;
        }
        if (address < 0x4000) {
            return rom[address];
        }
        if (address < 0x6000) {
            return rom[bank(1) * 0x2000 + (address - 0x4000)];
        }
        if (address < 0x8000) {
            if (address >= 0x7fe0 && address <= 0x7fe7) {
                return regs[address - 0x7fe0];
            }
            if (address >= 0x7800 && ramWritten[address - 0x7800]) {
                return ram[address - 0x7800];
            }
            if (address >= 0x7fe8) {
                // the rest of the ASIC register page reads as pulled-up open bus; the
                // software boots straight to the game launch when 0x7FEE reads with bit 0
                // set (and skips its link-dongle serial path when bit 4 is set)
                return 0xff;
            }
            return rom[bank(0) * 0x2000 + (address - 0x6000)];
        }
        return 0xff;
    }

    @Override
    public void flushRam() {
        battery.saveRam(ram);
        battery.flush();
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new DatelMemento(ram.clone(), regs.clone(), ramWritten.clone(), regsB.clone(),
                slot == null ? null : slot.saveToMemento());
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof DatelMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        System.arraycopy(mem.regs, 0, regs, 0, regs.length);
        System.arraycopy(mem.ramWritten, 0, ramWritten, 0, ramWritten.length);
        System.arraycopy(mem.regsB, 0, regsB, 0, regsB.length);
        if (slot != null && mem.slotMemento != null) {
            slot.restoreFromMemento(mem.slotMemento);
        }
    }

    private record DatelMemento(int[] ram, int[] regs, boolean[] ramWritten, int[] regsB,
                                Memento<MemoryController> slotMemento)
            implements Memento<MemoryController> {
    }
}
