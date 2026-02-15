package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

/**
 * MBC7 Mapper implementation.
 * Used by Kirby's Tilt 'n' Tumble.
 * Features ROM banking, accelerometer (sensor), and EEPROM.
 */
public class Mbc7 implements MemoryController {

    private final int romBanks;

    private final int[] cartridge;

    private int selectedRomBank = 1;

    private boolean ramWriteEnabled1;

    private boolean ramWriteEnabled2;

    private double x, y = 0;

    private int latchX = 0x8000;

    private int latchY = 0x8000;

    private int latchState = 0;

    private final Mbc7Eeprom eeprom;

    public Mbc7(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.romBanks = rom.getRomBanks();
        eeprom = new Mbc7Eeprom(battery);
    }

    public void init(EventBus eventBus) {
        eventBus.register(event -> {
            synchronized (this) {
                x = event.x();
                y = event.y();
            }
        }, AccelerometerEvent.class);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            if (value == 0x0a) {
                ramWriteEnabled1 = true;
            } else if (value == 0x00) {
                ramWriteEnabled1 = false;
                ramWriteEnabled2 = false;
            }
        } else if (address >= 0x2000 && address < 0x3000) {
            selectedRomBank = (selectedRomBank & 0x100) | value;
        } else if (address >= 0x3000 && address < 0x4000) {
            selectedRomBank = (selectedRomBank & 0x0ff) | ((value & 1) << 8);
        } else if (address >= 0x4000 && address < 0x6000) {
            if (value == 0x40 && ramWriteEnabled1) {
                ramWriteEnabled2 = true;
            }
        } else if (address >= 0xa000 && address < 0xb000 && ramWriteEnabled2) {
            int a = (address >> 4) & 0xf;
            if (a == 0) {
                if (value == 0x55) {
                    latchState = 1;
                    latchX = 0x8000;
                    latchY = 0x8000;
                }
            } else if (a == 1) {
                if (value == 0xaa) {
                    synchronized (this) {
                        latchX = 0x81d0 + (int) (x * 0x70);
                        latchY = 0x81d0 + (int) (y * 0x70);
                    }
                    latchState = 0;
                }
            } else if (a == 8) {
                writeEeprom(value);
            }
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(selectedRomBank % romBanks, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            if (!ramWriteEnabled2) {
                return 0xff;
            }
            return switch ((address >> 4) & 0xf) {
                case 2 -> latchX & 0xff;
                case 3 -> (latchX >> 8) & 0xff;
                case 4 -> latchY & 0xff;
                case 5 -> (latchY >> 8) & 0xff;
                case 6 -> 0;
                case 8 -> readEeprom();
                default -> 0xff;
            };
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    private void writeEeprom(int value) {
        eeprom.write(value);
    }

    private int readEeprom() {
        return eeprom.read();
    }

    private int getRomByte(int bank, int address) {
        int cartOffset = bank * 0x4000 + address;
        if (cartOffset < cartridge.length) {
            return cartridge[cartOffset];
        } else {
            return 0xff;
        }
    }

    @Override
    public void flushRam() {
        eeprom.flush();
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Mbc7Memento(
                selectedRomBank,
                ramWriteEnabled1,
                ramWriteEnabled2,
                x,
                y,
                latchX,
                latchY,
                latchState,
                eeprom.saveToMemento()
        );
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Mbc7Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.selectedRomBank = mem.selectedRomBank;
        this.ramWriteEnabled1 = mem.ramWriteEnabled1;
        this.ramWriteEnabled2 = mem.ramWriteEnabled2;
        this.x = mem.x;
        this.y = mem.y;
        this.latchX = mem.latchX;
        this.latchY = mem.latchY;
        this.latchState = mem.latchState;
        this.eeprom.restoreFromMemento(mem.eepromMemento);
    }

    private record Mbc7Memento(
            int selectedRomBank,
            boolean ramWriteEnabled1,
            boolean ramWriteEnabled2,
            double x,
            double y,
            int latchX,
            int latchY,
            int latchState,
            Memento<Mbc7Eeprom> eepromMemento
    ) implements Memento<MemoryController> {
    }
}
