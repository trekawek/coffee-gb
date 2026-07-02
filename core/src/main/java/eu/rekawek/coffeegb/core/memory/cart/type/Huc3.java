package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

import java.util.Arrays;

/**
 * Hudson HuC-3 mapper (used by e.g. Robopon). Simple ROM/RAM banking plus a mode register
 * at 0x0000-0x1FFF that switches what the 0xA000-0xBFFF window exposes: cart RAM (0x0/0xA),
 * an RTC command interface (0xB write / 0xC read / 0xD status semaphore) or the infrared
 * transceiver (0xE). The RTC keeps the minute of the day (12 bits) and a day counter
 * (12 bits), accessed nibble-wise through an index register.
 */
public class Huc3 implements MemoryController {

    private final int[] cartridge;

    private final int[] ram;

    private final int romBanks;

    private final int ramBanks;

    private final Battery battery;

    private int romBank = 1;

    private int ramBank;

    private int mode;

    // RTC state
    private int minutes; // minute of the day, 0..1439

    private int days;

    private int alarmMinutes;

    private int alarmDays;

    private boolean alarmEnabled;

    private int accessIndex;

    private int accessFlags;

    private int readValue;

    private long lastRtcSecond = System.currentTimeMillis() / 1000;

    private boolean ramUpdated;

    public Huc3(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.romBanks = rom.getRomBanks();
        this.ramBanks = Math.max(rom.getRamBanks(), 1);
        this.ram = new int[0x2000 * this.ramBanks];
        Arrays.fill(ram, 0xff);
        this.battery = battery;

        long[] clockData = new long[12];
        battery.loadRamWithClock(ram, clockData);
        if (clockData[5] != 0) {
            minutes = (int) clockData[0];
            days = (int) clockData[1];
            alarmMinutes = (int) clockData[2];
            alarmDays = (int) clockData[3];
            alarmEnabled = clockData[4] != 0;
            lastRtcSecond = clockData[5];
        }
        updateRtc();
    }

    private void updateRtc() {
        long now = System.currentTimeMillis() / 1000;
        while (lastRtcSecond / 60 < now / 60) {
            lastRtcSecond += 60;
            minutes++;
            if (minutes == 60 * 24) {
                days = (days + 1) & 0xfff;
                minutes = 0;
            }
        }
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address >= 0x0000 && address < 0x2000) {
            mode = value & 0x0f;
        } else if (address >= 0x2000 && address < 0x4000) {
            romBank = value & 0x7f;
        } else if (address >= 0x4000 && address < 0x6000) {
            ramBank = value & 0x0f;
        } else if (address >= 0xa000 && address < 0xc000) {
            switch (mode) {
                case 0x0b:
                    rtcCommand(value);
                    break;

                case 0x0c:
                case 0x0d:
                case 0x0e:
                    // RTC read latch / status / IR: writes have no effect we model
                    break;

                case 0x0a:
                    ram[getRamAddress(address)] = value;
                    ramUpdated = true;
                    break;

                default:
                    break;
            }
        }
    }

    private void rtcCommand(int value) {
        updateRtc();
        switch (value >> 4) {
            case 0x1: // read register, post-increment
                if (accessIndex < 3) {
                    readValue = (minutes >> (accessIndex * 4)) & 0x0f;
                } else if (accessIndex < 7) {
                    readValue = (days >> ((accessIndex - 3) * 4)) & 0x0f;
                } else {
                    readValue = 0;
                }
                accessIndex = (accessIndex + 1) & 0xff;
                break;

            case 0x2: // write register
            case 0x3: // write register, post-increment
                if (accessIndex < 3) {
                    minutes &= ~(0x0f << (accessIndex * 4));
                    minutes |= (value & 0x0f) << (accessIndex * 4);
                } else if (accessIndex < 7) {
                    days &= ~(0x0f << ((accessIndex - 3) * 4));
                    days |= (value & 0x0f) << ((accessIndex - 3) * 4);
                } else if (accessIndex >= 0x58 && accessIndex <= 0x5a) {
                    alarmMinutes &= ~(0x0f << ((accessIndex - 0x58) * 4));
                    alarmMinutes |= (value & 0x0f) << ((accessIndex - 0x58) * 4);
                } else if (accessIndex >= 0x5b && accessIndex <= 0x5e) {
                    alarmDays &= ~(0x0f << ((accessIndex - 0x5b) * 4));
                    alarmDays |= (value & 0x0f) << ((accessIndex - 0x5b) * 4);
                } else if (accessIndex == 0x5f) {
                    alarmEnabled = (value & 1) != 0;
                }
                if ((value >> 4) == 0x3) {
                    accessIndex = (accessIndex + 1) & 0xff;
                }
                break;

            case 0x4: // set access index low nibble
                accessIndex = (accessIndex & 0xf0) | (value & 0x0f);
                break;

            case 0x5: // set access index high nibble
                accessIndex = (accessIndex & 0x0f) | ((value & 0x0f) << 4);
                break;

            case 0x6:
                accessFlags = value & 0x0f;
                break;

            default:
                break;
        }
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x0000 && address < 0x4000) {
            return getRomByte(0, address);
        } else if (address >= 0x4000 && address < 0x8000) {
            return getRomByte(romBank % romBanks, address - 0x4000);
        } else if (address >= 0xa000 && address < 0xc000) {
            switch (mode) {
                case 0x0c: // RTC read
                    if (accessFlags == 0x2) {
                        return 1;
                    }
                    return readValue;

                case 0x0d: // RTC status semaphore
                    return 1;

                case 0x0e: // IR receiver: no light seen
                    return 0xc0;

                case 0x00:
                case 0x0a:
                    return ram[getRamAddress(address)];

                default:
                    return 1;
            }
        } else {
            throw new IllegalArgumentException(Integer.toHexString(address));
        }
    }

    @Override
    public void flushRam() {
        battery.saveRamWithClock(ram, serializeClock());
        battery.flush();
    }

    private long[] serializeClock() {
        long[] clockData = new long[12];
        clockData[0] = minutes;
        clockData[1] = days;
        clockData[2] = alarmMinutes;
        clockData[3] = alarmDays;
        clockData[4] = alarmEnabled ? 1 : 0;
        clockData[5] = lastRtcSecond;
        return clockData;
    }

    private int getRamAddress(int address) {
        return (ramBank % ramBanks) * 0x2000 + (address - 0xa000);
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
    public Memento<MemoryController> saveToMemento() {
        return new Huc3Memento(battery.saveToMemento(), ram.clone(), romBank, ramBank, mode, minutes, days,
                alarmMinutes, alarmDays, alarmEnabled, accessIndex, accessFlags, readValue, lastRtcSecond,
                ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Huc3Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("Memento ram length doesn't match");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.romBank = mem.romBank;
        this.ramBank = mem.ramBank;
        this.mode = mem.mode;
        this.minutes = mem.minutes;
        this.days = mem.days;
        this.alarmMinutes = mem.alarmMinutes;
        this.alarmDays = mem.alarmDays;
        this.alarmEnabled = mem.alarmEnabled;
        this.accessIndex = mem.accessIndex;
        this.accessFlags = mem.accessFlags;
        this.readValue = mem.readValue;
        this.lastRtcSecond = mem.lastRtcSecond;
        this.ramUpdated = mem.ramUpdated;
    }

    private record Huc3Memento(Memento<Battery> batteryMemento, int[] ram, int romBank, int ramBank, int mode,
                               int minutes, int days, int alarmMinutes, int alarmDays, boolean alarmEnabled,
                               int accessIndex, int accessFlags, int readValue, long lastRtcSecond,
                               boolean ramUpdated) implements Memento<MemoryController> {
    }
}
