package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.rumble.RumbleEvent;

import java.util.Arrays;

/**
 * Makon/N&amp;T's old type 2 mapper, used by the Pocket Bomberman-based 23-in-1
 * multicart. Registers 0x5001 and 0x5002 select an embedded game's base and size;
 * the game's subsequent bank writes are relative to that base. Register 0x5003
 * enables the alternate ROM-bank bit wiring used by some of the embedded games.
 */
public class MakonNtOld2 implements MemoryController {

    private final int[] rom;

    private final int romBanks;

    private final int[] ram = new int[0x2000];

    private final Battery battery;

    private int selectedRomBank = 1;

    private int mappedRomBank = 1;

    private int baseRomBank;

    private int gameRomBankMask;

    private boolean weirdMode;

    private boolean rumbleEnabled;

    private boolean motorOn;

    private boolean ramUpdated;

    private transient EventBus eventBus;

    public MakonNtOld2(Rom rom, Battery battery) {
        this.rom = rom.getRom();
        this.romBanks = Math.max(2, (this.rom.length + 0x3fff) / 0x4000);
        this.gameRomBankMask = Integer.highestOneBit(this.romBanks) - 1;
        this.battery = battery;
        Arrays.fill(ram, 0xff);
        battery.loadRam(ram);
    }

    @Override
    public void init(EventBus eventBus) {
        this.eventBus = eventBus;
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000)
                || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        value &= 0xff;
        if (address >= 0x2000 && address < 0x4000) {
            selectRomBank(value);
        } else if (address == 0x5001) {
            setRumbleEnabled((value & 0x80) != 0);
            updateMotor(value);

            int newBaseRomBank = (value & 0x3f) * 2;
            if (newBaseRomBank > 0) {
                baseRomBank = newBaseRomBank;
                mappedRomBank = 1;
            }
        } else if (address == 0x5002) {
            gameRomBankMask = switch (value & 0x0f) {
                case 0x08 -> 0x0f; // 256 KiB
                case 0x0c -> 0x07; // 128 KiB
                case 0x0e -> 0x03; // 64 KiB
                case 0x0f -> 0x01; // 32 KiB
                default -> 0x1f;   // 512 KiB
            };
            updateMotor(value);
        } else if (address == 0x5003) {
            updateMotor(value);
            weirdMode = (value & 0x10) != 0;
            selectRomBank(selectedRomBank);
        } else if (address >= 0x4000 && address < 0x6000) {
            updateMotor(value);
        } else if (address >= 0xa000 && address < 0xc000) {
            ram[address - 0xa000] = value;
            ramUpdated = true;
        }
    }

    private void selectRomBank(int bank) {
        if (bank == 0) {
            bank = 1;
        }
        if (weirdMode) {
            bank = reorderBankBits(bank);
        }
        selectedRomBank = bank;
        mappedRomBank = bank & gameRomBankMask;
    }

    private static int reorderBankBits(int bank) {
        return (bank & 0xf8)
                | ((bank & 0x01) << 2)
                | ((bank & 0x04) >> 1)
                | ((bank & 0x02) >> 1);
    }

    private void setRumbleEnabled(boolean enabled) {
        rumbleEnabled = enabled;
        if (!enabled) {
            setMotorOn(false);
        }
    }

    private void updateMotor(int value) {
        if (rumbleEnabled) {
            setMotorOn((value & (weirdMode ? 0x08 : 0x02)) != 0);
        }
    }

    private void setMotorOn(boolean on) {
        if (on == motorOn) {
            return;
        }
        motorOn = on;
        if (eventBus != null) {
            eventBus.post(new RumbleEvent(on));
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(baseRomBank, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(baseRomBank + mappedRomBank, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            return ram[address - 0xa000];
        }
        throw new IllegalArgumentException(Integer.toHexString(address));
    }

    private int getRomByte(int bank, int address) {
        int offset = Math.floorMod(bank, romBanks) * 0x4000 + address;
        return offset < rom.length ? rom[offset] : 0xff;
    }

    @Override
    public void flushRam() {
        if (ramUpdated) {
            battery.saveRam(ram);
            battery.flush();
        }
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new MakonNtOld2Memento(battery.saveToMemento(), ram.clone(), selectedRomBank,
                mappedRomBank, baseRomBank, gameRomBankMask, weirdMode, rumbleEnabled,
                motorOn, ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof MakonNtOld2Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, ram, 0, ram.length);
        selectedRomBank = mem.selectedRomBank;
        mappedRomBank = mem.mappedRomBank;
        baseRomBank = mem.baseRomBank;
        gameRomBankMask = mem.gameRomBankMask;
        weirdMode = mem.weirdMode;
        rumbleEnabled = mem.rumbleEnabled;
        motorOn = mem.motorOn;
        ramUpdated = mem.ramUpdated;
    }

    private record MakonNtOld2Memento(Memento<Battery> batteryMemento, int[] ram,
                                       int selectedRomBank, int mappedRomBank,
                                       int baseRomBank, int gameRomBankMask,
                                       boolean weirdMode, boolean rumbleEnabled,
                                       boolean motorOn, boolean ramUpdated)
            implements Memento<MemoryController> {
    }
}
