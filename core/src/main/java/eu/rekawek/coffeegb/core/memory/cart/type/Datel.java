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
 * <p>The cart pairs an SST-style 128 KB flash chip (the "ROM": the software identifies it
 * with the JEDEC 0x5555/0x2AAA command sequences, erases sectors and reprograms it - the
 * cheat database and settings live in flash) with an ASIC that banks it in 8 KB units and
 * carries 4 KB of RAM:
 *
 * <ul>
 *   <li>{@code 0x0000-0x3FFF} - fixed, the first 16 KB of flash;</li>
 *   <li>{@code 0x4000-0x5FFF} - 8 KB window selected by {@code 0x7FE1};</li>
 *   <li>{@code 0x6000-0x7FFF} - 8 KB window selected by {@code 0x7FE0};</li>
 *   <li>{@code 0x7000-0x7FFF} - the ASIC RAM overlays the top window: it catches all
 *       writes, and serves reads once a byte has been written (the software copies its
 *       flash routines and cheat lists here and executes them);</li>
 *   <li>{@code 0x7FE0-0x7FE7} / {@code 0x7FF0-0x7FF7} - the ASIC register file, inside
 *       the RAM page. {@code 0x7FE2} is the bus latch: value 0x03 routes the bus to the
 *       game cartridge in the pass-through slot (the launch stub's write; bit 4 arms the
 *       ASIC's vblank hook, unemulated). {@code 0x7FE5}=0x10 exposes the slot temporarily
 *       for the software's header peeks. The 0x7FFx channel guards the flash
 *       (write-protect toggles around programming; stored, no effect needed);</li>
 *   <li>the unwritten rest of the register page reads pulled up - 0x7FEE bit 0 makes the
 *       software boot straight to the game launch, bit 4 skips its dongle path.</li>
 * </ul>
 */
public class Datel implements MemoryController {

    // SST39SF010A: manufacturer 0xBF, device 0xB5 (1 MBit)
    private static final int FLASH_MANUFACTURER = 0xbf;

    private static final int FLASH_DEVICE = 0xb5;

    private static final int SECTOR_SIZE = 0x1000;

    private final int[] flash;

    private final int romBanks8k;

    // 4 KB of ASIC RAM at 0x7000-0x7FFF
    private final int[] ram = new int[0x1000];

    private final boolean[] ramWritten = new boolean[0x1000];

    private final int[] regs = new int[8];

    private final int[] regsB = new int[8];

    // JEDEC command state: 0 = idle, 1 = got 5555=AA, 2 = got 2AAA=55, 3 = program next
    private int flashCycle;

    private boolean flashErasePending;

    private boolean flashIdMode;

    private transient MemoryController slot;

    private transient boolean slotNonCgb;

    private transient EventBus eventBus;

    /** Fired when the software's launch/restart stub pulses the console reset. */
    public static class LaunchEvent implements Event {
        public final boolean nonCgbGame;

        public LaunchEvent(boolean nonCgbGame) {
            this.nonCgbGame = nonCgbGame;
        }
    }

    public Datel(Rom rom, Battery battery) {
        this.flash = rom.getRom().clone();
        this.romBanks8k = Math.max(1, this.flash.length / 0x2000);
        // the ASIC RAM is plain SRAM - fresh at every power-on. The flash is kept
        // in-memory only: the dumps capture the never-initialized factory state, and the
        // software redoes its first-boot initialization each power-on (ending in the
        // restart we treat as the game launch), so persisting our own writes would
        // strand the cart in a state real hardware reaches only after the (unemulated)
        // online registration.
        Arrays.fill(ram, 0xff);
        regs[0] = 1;
        regs[1] = 2;
    }

    /** The game cartridge in the pass-through slot. */
    public void setSlotCartridge(MemoryController slot, boolean nonCgb) {
        this.slot = slot;
        this.slotNonCgb = nonCgb;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    private boolean slotLatched() {
        // Two launch paths exist in the software. The post-registration launch stub
        // routes the bus with 0x7FE6=0x07 (0x7FE2 carries the vblank-hook config,
        // 0x03/0x13 - it is not the latch: the header peek also writes it). That flow
        // is gated behind the unemulated online-registration dongle. The reachable
        // flow is the first-boot flash-initialization restart, whose HRAM stub sets
        // 0x7FE4 bit 0 before its 0x7FF4-terminated flash-guard sequence; we latch on
        // either and treat the restart as the game launch.
        return regs[6] == 0x07 || (regs[4] & 0x01) != 0;
    }

    private boolean slotPeek() {
        return (regs[5] & 0x10) != 0;
    }

    private int bank(int reg) {
        return regs[reg] % romBanks8k;
    }

    /** The flash-side address a CPU address reaches through the current banking. */
    private int flashAddress(int address) {
        if (address < 0x4000) {
            return address;
        }
        if (address < 0x6000) {
            return bank(1) * 0x2000 + (address - 0x4000);
        }
        return bank(0) * 0x2000 + (address - 0x6000);
    }

    @Override
    public void setByte(int address, int value) {
        value &= 0xff;
        if (address >= 0x7fe0 && address <= 0x7fe7) {
            regs[address - 0x7fe0] = value;
            return;
        }
        if (address >= 0x7ff0 && address <= 0x7ff7) {
            regsB[address - 0x7ff0] = value;
            if (address == 0x7ff4 && (regs[4] & 0x01) != 0 && eventBus != null) {
                // the restart stub's final write: emulate it as the console-reset the
                // real software's launch performs, with the slot latched
                eventBus.post(new LaunchEvent(slotNonCgb));
            }
            return;
        }
        if (slotLatched() || slotPeek()) {
            if (slot != null) {
                slot.setByte(address, value);
            }
            return;
        }
        if (address >= 0xa000) {
            return;
        }
        if (address >= 0x7000 && address < 0x8000) {
            // the ASIC RAM catches all writes to the top window (flash routines, cheat
            // lists, the vblank hook body seeded at 0x7000)
            ram[address - 0x7000] = value;
            ramWritten[address - 0x7000] = true;
            return;
        }
        flashWrite(flashAddress(address), value);
    }

    private void flashWrite(int flashAddr, int value) {
        switch (flashCycle) {
            case 1:
                if (flashAddr == 0x2aaa && value == 0x55) {
                    flashCycle = 2;
                    return;
                }
                break;
            case 2:
                if (flashAddr == 0x5555 && !flashErasePending) {
                    switch (value) {
                        case 0xa0: // byte program
                            flashCycle = 3;
                            return;
                        case 0x80: // erase prefix: expects a second AA/55 unlock
                            flashErasePending = true;
                            flashCycle = 0;
                            return;
                        case 0x90: // software ID
                            flashIdMode = true;
                            flashCycle = 0;
                            return;
                        case 0xf0: // reset
                            flashIdMode = false;
                            flashCycle = 0;
                            return;
                        default:
                            break;
                    }
                }
                if (flashErasePending) {
                    if (value == 0x30) { // sector erase
                        int base = (flashAddr & ~(SECTOR_SIZE - 1)) % flash.length;
                        for (int i = 0; i < SECTOR_SIZE; i++) {
                            flash[base + i] = 0xff;
                        }
                    } else if (value == 0x10 && flashAddr == 0x5555) { // chip erase
                        Arrays.fill(flash, 0xff);
                    }
                    flashErasePending = false;
                }
                flashCycle = 0;
                return;
            case 3: // program: flash writes only clear bits
                flash[flashAddr % flash.length] &= value;
                flashCycle = 0;
                return;
            default:
                break;
        }
        if (flashAddr == 0x5555 && value == 0xaa) {
            flashCycle = 1;
        } else if (value == 0xf0) {
            // reset works from any state
            flashIdMode = false;
            flashCycle = 0;
        } else {
            flashCycle = 0;
        }
    }

    @Override
    public int getByte(int address) {
        if (slotLatched() || slotPeek()) {
            if (address >= 0x7fe0 && address <= 0x7fe7) {
                // the register file stays visible: the peek stub switches back with a
                // 0x7FE5 write while the slot occupies the bus
                return regs[address - 0x7fe0];
            }
            return slot != null ? slot.getByte(address) : 0xff;
        }
        if (address >= 0xa000) {
            return 0xff;
        }
        if (flashIdMode && address < 0x7000) {
            int flashAddr = flashAddress(address) & 0xff;
            if (flashAddr == 0) {
                return FLASH_MANUFACTURER;
            }
            if (flashAddr == 1) {
                return FLASH_DEVICE;
            }
        }
        if (address >= 0x7000) {
            if (address >= 0x7fe0 && address <= 0x7fe7) {
                return regs[address - 0x7fe0];
            }
            if (address >= 0x7800 && ramWritten[address - 0x7000]) {
                // only the top 2 KB reads back what was written (the cheat lists the
                // software builds and executes); 0x7000-0x77FF writes are captured for
                // the ASIC's hook memory but reads keep serving the flash window
                return ram[address - 0x7000];
            }
            if (address >= 0x7fe8) {
                // the unwritten rest of the ASIC register page reads pulled up: 0x7FEE
                // bit 0 boots the software straight to the game launch, bit 4 skips its
                // link-dongle serial path
                return 0xff;
            }
        }
        return flash[flashAddress(address) % flash.length];
    }

    @Override
    public void flushRam() {
        if (slot != null) {
            slot.flushRam();
        }
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new DatelMemento(ram.clone(), regs.clone(), ramWritten.clone(), regsB.clone(),
                flash.clone(), flashCycle, flashErasePending, flashIdMode,
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
        System.arraycopy(mem.flash, 0, flash, 0, flash.length);
        this.flashCycle = mem.flashCycle;
        this.flashErasePending = mem.flashErasePending;
        this.flashIdMode = mem.flashIdMode;
        if (slot != null && mem.slotMemento != null) {
            slot.restoreFromMemento(mem.slotMemento);
        }
    }

    private record DatelMemento(int[] ram, int[] regs, boolean[] ramWritten, int[] regsB,
                                int[] flash, int flashCycle, boolean flashErasePending,
                                boolean flashIdMode, Memento<MemoryController> slotMemento)
            implements Memento<MemoryController> {
    }
}
