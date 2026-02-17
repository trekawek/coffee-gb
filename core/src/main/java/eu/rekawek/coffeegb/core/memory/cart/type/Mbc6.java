package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

public class Mbc6 implements MemoryController {

    private final int[] cartridge;

    private final int[] ram;

    private final int[] flash;

    private final Battery battery;

    private boolean ramEnabled;

    private int ramBankA;

    private int ramBankB;

    private int romBankA = 2;

    private int romBankB = 3;

    private boolean romBankAFlash;

    private boolean romBankBFlash;

    private boolean flashEnabled;

    private boolean flashWriteEnable;

    private int flashCommandState = 0;

    private boolean flashIdMode = false;

    private boolean flashProgramMode = false;

    public Mbc6(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.ram = new int[0x8000]; // 32KB
        Arrays.fill(ram, 0xff);
        this.flash = new int[0x100000]; // 1MB
        Arrays.fill(flash, 0xff);
        this.battery = battery;

        int[] combined = new int[ram.length + flash.length];
        battery.loadRam(combined);
        System.arraycopy(combined, 0, ram, 0, ram.length);
        System.arraycopy(combined, ram.length, flash, 0, flash.length);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x0400) {
            ramEnabled = value == 0x0a;
        } else if (address >= 0x0400 && address < 0x0800) {
            ramBankA = value;
        } else if (address >= 0x0800 && address < 0x0c00) {
            ramBankB = value;
        } else if (address >= 0x0c00 && address < 0x1000) {
            flashEnabled = value != 0;
        } else if (address >= 0x1000 && address < 0x2000) {
            flashWriteEnable = value != 0;
        } else if (address >= 0x2000 && address < 0x2800) {
            romBankA = value;
        } else if (address >= 0x2800 && address < 0x3000) {
            romBankAFlash = value == 0x08;
        } else if (address >= 0x3000 && address < 0x3800) {
            romBankB = value;
        } else if (address >= 0x3800 && address < 0x4000) {
            romBankBFlash = value == 0x08;
        } else if (address >= 0x4000 && address < 0x6000) {
            if (romBankAFlash && flashEnabled && flashWriteEnable) {
                handleFlashWrite(romBankA, address - 0x4000, value);
            }
        } else if (address >= 0x6000 && address < 0x8000) {
            if (romBankBFlash && flashEnabled && flashWriteEnable) {
                handleFlashWrite(romBankB, address - 0x6000, value);
            }
        } else if (address >= 0xa000 && address < 0xb000) {
            if (ramEnabled) {
                ram[(ramBankA % 8) * 0x1000 + (address - 0xa000)] = value;
            }
        } else if (address >= 0xb000 && address < 0xc000) {
            if (ramEnabled) {
                ram[(ramBankB % 8) * 0x1000 + (address - 0xb000)] = value;
            }
        }
    }

    private void handleFlashWrite(int bank, int offset, int value) {
        int addr = (bank % 128) * 0x2000 + offset;
        if (flashProgramMode) {
            flash[addr] &= value;
            flashProgramMode = false;
            flashCommandState = 0;
            return;
        }

        if (offset == 0x1555 && value == 0xaa && flashCommandState == 0) {
            flashCommandState = 1;
        } else if (offset == 0x0aaa && value == 0x55 && flashCommandState == 1) {
            flashCommandState = 2;
        } else if (offset == 0x1555 && flashCommandState == 2) {
            switch (value) {
                case 0x80 -> flashCommandState = 3;
                case 0xa0 -> flashProgramMode = true;
                case 0x90 -> flashIdMode = true;
                case 0xf0 -> {
                    flashIdMode = false;
                    flashCommandState = 0;
                }
            }
            if (!flashProgramMode && value != 0x80) {
                flashCommandState = 0;
            }
        } else if (flashCommandState == 3) {
            if (offset == 0x1555 && value == 0xaa) {
                flashCommandState = 4;
            } else {
                flashCommandState = 0;
            }
        } else if (flashCommandState == 4) {
            if (offset == 0x0aaa && value == 0x55) {
                flashCommandState = 5;
            } else {
                flashCommandState = 0;
            }
        } else if (flashCommandState == 5) {
            if (value == 0x10) {
                Arrays.fill(flash, 0xff);
            } else if (value == 0x30) {
                int sectorAddr = addr & ~0x1ffff; // 128KB sectors
                for (int i = 0; i < 0x20000; i++) {
                    flash[sectorAddr + i] = 0xff;
                }
            }
            flashCommandState = 0;
        } else {
            flashCommandState = 0;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return cartridge[address];
        } else if (address >= 0x4000 && address < 0x6000) {
            if (romBankAFlash && flashEnabled) {
                return getFlashByte(romBankA, address - 0x4000);
            } else {
                int offset = (romBankA % (cartridge.length / 0x2000)) * 0x2000 + (address - 0x4000);
                return cartridge[offset];
            }
        } else if (address >= 0x6000 && address < 0x8000) {
            if (romBankBFlash && flashEnabled) {
                return getFlashByte(romBankB, address - 0x6000);
            } else {
                int offset = (romBankB % (cartridge.length / 0x2000)) * 0x2000 + (address - 0x6000);
                return cartridge[offset];
            }
        } else if (address >= 0xa000 && address < 0xb000) {
            if (ramEnabled) {
                return ram[(ramBankA % 8) * 0x1000 + (address - 0xa000)];
            } else {
                return 0xff;
            }
        } else if (address >= 0xb000 && address < 0xc000) {
            if (ramEnabled) {
                return ram[(ramBankB % 8) * 0x1000 + (address - 0xb000)];
            } else {
                return 0xff;
            }
        }
        return 0xff;
    }

    private int getFlashByte(int bank, int offset) {
        if (flashIdMode) {
            return switch (offset) {
                case 0 -> 0xc2; // Macronix
                case 1 -> 0x5b; // MX29F008
                default -> 0x00;
            };
        }
        return flash[(bank % 128) * 0x2000 + offset];
    }

    @Override
    public void flushRam() {
        int[] combined = new int[ram.length + flash.length];
        System.arraycopy(ram, 0, combined, 0, ram.length);
        System.arraycopy(flash, 0, combined, ram.length, flash.length);
        battery.saveRam(combined);
        battery.flush();
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Mbc6Memento(
                ram.clone(),
                flash.clone(),
                ramEnabled,
                ramBankA,
                ramBankB,
                romBankA,
                romBankB,
                romBankAFlash,
                romBankBFlash,
                flashEnabled,
                flashWriteEnable,
                flashCommandState,
                flashIdMode,
                flashProgramMode
        );
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (memento instanceof Mbc6Memento mem) {
            System.arraycopy(mem.ram, 0, this.ram, 0, ram.length);
            System.arraycopy(mem.flash, 0, this.flash, 0, flash.length);
            this.ramEnabled = mem.ramEnabled;
            this.ramBankA = mem.ramBankA;
            this.ramBankB = mem.ramBankB;
            this.romBankA = mem.romBankA;
            this.romBankB = mem.romBankB;
            this.romBankAFlash = mem.romBankAFlash;
            this.romBankBFlash = mem.romBankBFlash;
            this.flashEnabled = mem.flashEnabled;
            this.flashWriteEnable = mem.flashWriteEnable;
            this.flashCommandState = mem.flashCommandState;
            this.flashIdMode = mem.flashIdMode;
            this.flashProgramMode = mem.flashProgramMode;
        }
    }

    private record Mbc6Memento(
            int[] ram,
            int[] flash,
            boolean ramEnabled,
            int ramBankA,
            int ramBankB,
            int romBankA,
            int romBankB,
            boolean romBankAFlash,
            boolean romBankBFlash,
            boolean flashEnabled,
            boolean flashWriteEnable,
            int flashCommandState,
            boolean flashIdMode,
            boolean flashProgramMode
    ) implements Memento<MemoryController> {
    }
}
