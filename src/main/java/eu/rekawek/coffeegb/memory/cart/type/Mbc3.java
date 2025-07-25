package eu.rekawek.coffeegb.memory.cart.type;

import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memory.cart.MemoryController;
import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.memory.cart.rtc.RealTimeClock;
import eu.rekawek.coffeegb.memory.cart.rtc.SystemTimeSource;

import java.util.Arrays;

public class Mbc3 implements MemoryController {

    private final int[] cartridge;

    private final int[] ram;

    private final RealTimeClock clock;

    private final Battery battery;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private boolean ramWriteEnabled;

    private int latchClockReg = 0xff;

    private boolean clockLatched;

    public Mbc3(int[] cartridge, Battery battery, int ramBanks) {
        this.cartridge = cartridge;
        this.ram = new int[0x2000 * Math.max(ramBanks, 1)];
        Arrays.fill(ram, 0xff);
        this.clock = new RealTimeClock(new SystemTimeSource());
        this.battery = battery;

        long[] clockData = new long[12];
        battery.loadRamWithClock(ram, clockData);
        clock.deserialize(clockData);
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            ramWriteEnabled = (value & 0b1010) != 0;
        } else if (address >= 0x2000 && address < 0x4000) {
            int bank = value & 0b01111111;
            selectRomBank(bank);
        } else if (address >= 0x4000 && address < 0x6000) {
            selectedRamBank = value;
        } else if (address >= 0x6000 && address < 0x8000) {
            if (value == 0x01 && latchClockReg == 0x00) {
                if (clockLatched) {
                    clock.unlatch();
                    clockLatched = false;
                } else {
                    clock.latch();
                    clockLatched = true;
                }
            }
            latchClockReg = value;
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled && selectedRamBank < 4) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
            }
        } else if (address >= 0xa000 && address < 0xc000 && ramWriteEnabled) {
            setTimer(value);
        }
    }

    @Override
    public void flushRam() {
        battery.saveRamWithClock(ram, clock.serialize());
        battery.flush();
    }

    private void selectRomBank(int bank) {
        if (bank == 0) {
            bank = 1;
        }
        selectedRomBank = bank;
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(selectedRomBank, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000 && selectedRamBank < 4) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                return ram[ramAddress];
            } else {
                return 0xff;
            }
        } else if (address >= 0xa000 && address < 0xc000) {
            return getTimer();
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    private int getRomByte(int bank, int address) {
        int cartOffset = bank * 0x4000 + address;
        if (cartOffset < cartridge.length) {
            return cartridge[cartOffset];
        } else {
            return 0xff;
        }
    }

    private int getRamAddress(int address) {
        return selectedRamBank * 0x2000 + (address - 0xa000);
    }

    private int getTimer() {
        switch (selectedRamBank) {
            case 0x08:
                return clock.getSeconds();

            case 0x09:
                return clock.getMinutes();

            case 0x0a:
                return clock.getHours();

            case 0x0b:
                return clock.getDayCounter() & 0xff;

            case 0x0c:
                int result = ((clock.getDayCounter() & 0x100) >> 8);
                result |= clock.isHalt() ? (1 << 6) : 0;
                result |= clock.isCounterOverflow() ? (1 << 7) : 0;
                return result;
        }
        return 0xff;
    }

    private void setTimer(int value) {
        int dayCounter = clock.getDayCounter();
        switch (selectedRamBank) {
            case 0x08:
                clock.setSeconds(value);
                break;

            case 0x09:
                clock.setMinutes(value);
                break;

            case 0x0a:
                clock.setHours(value);
                break;

            case 0x0b:
                clock.setDayCounter((dayCounter & 0x100) | (value & 0xff));
                break;

            case 0x0c:
                clock.setDayCounter((dayCounter & 0xff) | ((value & 1) << 8));
                clock.setHalt((value & (1 << 6)) != 0);
                if ((value & (1 << 7)) == 0) {
                    clock.clearCounterOverflow();
                }
                break;
        }
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Mbc3Memento(ram.clone(), clock.saveToMemento(), battery.saveToMemento(), selectedRamBank, selectedRomBank, ramWriteEnabled, latchClockReg, clockLatched);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Mbc3Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        clock.restoreFromMemento(mem.clockMemento);
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.selectedRamBank = mem.selectedRamBank;
        this.selectedRomBank = mem.selectedRomBank;
        this.ramWriteEnabled = mem.ramWriteEnabled;
        this.latchClockReg = mem.latchClockReg;
        this.clockLatched = mem.clockLatched;
    }

    private record Mbc3Memento(int[] ram, Memento<RealTimeClock> clockMemento, Memento<Battery> batteryMemento,
                               int selectedRamBank, int selectedRomBank,
                               boolean ramWriteEnabled, int latchClockReg,
                               boolean clockLatched) implements Memento<MemoryController> {
    }
}
