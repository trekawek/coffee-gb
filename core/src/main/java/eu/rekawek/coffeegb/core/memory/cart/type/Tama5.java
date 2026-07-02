package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;

/**
 * Bandai TAMA5 mapper (Game de Hakken!! Tamagotchi - Osutchi to Mesutchi). The cartridge is
 * driven through two nibble-wide ports: 0xA001 selects a command register and 0xA000 carries
 * the data. Registers 0/1 set the ROM bank; registers 4-7 form a tiny read/write protocol
 * addressing 32 bytes of cart RAM, a command interface and the TAMA6 RTC chip (kept as four
 * pages of BCD nibble registers). Modelled after mGBA.
 */
public class Tama5 implements MemoryController {

    // command registers
    private static final int BANK_LO = 0x0;
    private static final int BANK_HI = 0x1;
    private static final int WRITE_LO = 0x4;
    private static final int WRITE_HI = 0x5;
    private static final int ADDR_HI = 0x6;
    private static final int ADDR_LO = 0x7;
    private static final int MAX_REG = 0x8;
    private static final int ACTIVE = 0xa;
    private static final int READ_LO = 0xc;
    private static final int READ_HI = 0xd;

    // TAMA6 RTC registers (per page)
    private static final int RTC_SECOND_1 = 0x0;
    private static final int RTC_SECOND_10 = 0x1;
    private static final int RTC_MINUTE_1 = 0x2;
    private static final int RTC_MINUTE_10 = 0x3;
    private static final int RTC_HOUR_1 = 0x4;
    private static final int RTC_HOUR_10 = 0x5;
    private static final int RTC_WEEK = 0x6;
    private static final int RTC_DAY_1 = 0x7;
    private static final int RTC_DAY_10 = 0x8;
    private static final int RTC_MONTH_1 = 0x9;
    private static final int RTC_MONTH_10 = 0xa;
    private static final int RTC_YEAR_1 = 0xb;
    private static final int RTC_YEAR_10 = 0xc;
    private static final int RTC_PA1_24_HOUR = 0xa;
    private static final int RTC_PA1_LEAP_YEAR = 0xb;
    private static final int RTC_PAGE = 0xd;

    // TAMA6 commands
    private static final int CMD_DISABLE_TIMER = 0x0;
    private static final int CMD_ENABLE_TIMER = 0x1;
    private static final int CMD_MINUTE_WRITE = 0x4;
    private static final int CMD_HOUR_WRITE = 0x5;
    private static final int CMD_MINUTE_READ = 0x6;
    private static final int CMD_HOUR_READ = 0x7;
    private static final int CMD_DISABLE_ALARM = 0x10;
    private static final int CMD_ENABLE_ALARM = 0x11;

    private static final int[] RTC_MASK = {
            0xF, 0x7, 0xF, 0x7, 0xF, 0x3, 0x7, 0xF, 0x3, 0xF, 0x1, 0xF, 0xF, 0x0, 0x0, 0x0,
            0x0, 0x0, 0xF, 0x7, 0xF, 0x3, 0x7, 0xF, 0x3, 0x0, 0x1, 0x3, 0x0, 0x0, 0x0, 0x0,
    };

    private static final int[] DAYS_TO_MONTH = {0, 0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334};

    private final int[] cartridge;

    private final int[] ram = new int[0x20];

    private final int romBanks;

    private final Battery battery;

    private int selectedReg;

    private final int[] registers = new int[MAX_REG];

    private final int[] rtcTimerPage = new int[16];

    private final int[] rtcAlarmPage = new int[16];

    private final int[] rtcFreePage0 = new int[16];

    private final int[] rtcFreePage1 = new int[16];

    private boolean rtcDisabled;

    private long lastRtcSecond = System.currentTimeMillis() / 1000;

    private boolean ramUpdated;

    public Tama5(Rom rom, Battery battery) {
        this.cartridge = rom.getRom();
        this.romBanks = rom.getRomBanks();
        this.battery = battery;

        rtcAlarmPage[RTC_PAGE] = 1;
        rtcFreePage0[RTC_PAGE] = 2;
        rtcFreePage1[RTC_PAGE] = 3;

        long[] clockData = new long[12];
        battery.loadRamWithClock(ram, clockData);
        if (clockData[5] != 0) {
            unpackPage(rtcTimerPage, clockData[0]);
            unpackPage(rtcAlarmPage, clockData[1]);
            unpackPage(rtcFreePage0, clockData[2]);
            unpackPage(rtcFreePage1, clockData[3]);
            rtcDisabled = clockData[4] != 0;
            lastRtcSecond = clockData[5];
        } else {
            // sensible default so the game does not start from an invalid date
            rtcTimerPage[RTC_DAY_1] = 1;
            rtcTimerPage[RTC_MONTH_1] = 1;
        }
    }

    @Override
    public boolean accepts(int address) {
        return (address >= 0x0000 && address < 0x8000) || (address >= 0xa000 && address < 0xc000);
    }

    @Override
    public void setByte(int address, int value) {
        if (address < 0x8000) {
            // ROM area writes have no effect on TAMA5
            return;
        }
        if ((address & 1) == 1) {
            selectedReg = value & 0x0f;
            return;
        }
        value &= 0x0f;
        if (selectedReg >= MAX_REG) {
            return;
        }
        registers[selectedReg] = value;
        int ramAddress = ((registers[ADDR_HI] << 4) & 0x10) | registers[ADDR_LO];
        int out = (registers[WRITE_HI] << 4) | registers[WRITE_LO];
        switch (selectedReg) {
            case BANK_LO:
            case BANK_HI:
                // bank latched on write; effective bank computed on read
                break;

            case ADDR_LO:
                switch (registers[ADDR_HI] >> 1) {
                    case 0x0: // RAM write
                        ram[ramAddress] = out;
                        ramUpdated = true;
                        break;

                    case 0x1: // RAM read (latched on the read side)
                        break;

                    case 0x2: // commands
                        runCommand(ramAddress, out);
                        break;

                    case 0x4: // RTC register write
                        rtcWrite();
                        break;

                    default:
                        break;
                }
                break;

            default:
                break;
        }
    }

    private void runCommand(int command, int out) {
        switch (command) {
            case CMD_DISABLE_TIMER:
                rtcDisabled = true;
                rtcTimerPage[RTC_PAGE] &= 0x7;
                rtcAlarmPage[RTC_PAGE] &= 0x7;
                rtcFreePage0[RTC_PAGE] &= 0x7;
                rtcFreePage1[RTC_PAGE] &= 0x7;
                break;

            case CMD_ENABLE_TIMER:
                rtcDisabled = false;
                rtcTimerPage[RTC_SECOND_1] = 0;
                rtcTimerPage[RTC_SECOND_10] = 0;
                rtcTimerPage[RTC_PAGE] |= 0x8;
                rtcAlarmPage[RTC_PAGE] |= 0x8;
                rtcFreePage0[RTC_PAGE] |= 0x8;
                rtcFreePage1[RTC_PAGE] |= 0x8;
                break;

            case CMD_MINUTE_WRITE:
                rtcTimerPage[RTC_MINUTE_1] = out & 0x0f;
                rtcTimerPage[RTC_MINUTE_10] = out >> 4;
                break;

            case CMD_HOUR_WRITE:
                rtcTimerPage[RTC_HOUR_1] = out & 0x0f;
                rtcTimerPage[RTC_HOUR_10] = out >> 4;
                break;

            case CMD_DISABLE_ALARM:
                rtcTimerPage[RTC_PAGE] &= 0xb;
                rtcAlarmPage[RTC_PAGE] &= 0xb;
                rtcFreePage0[RTC_PAGE] &= 0xb;
                rtcFreePage1[RTC_PAGE] &= 0xb;
                break;

            case CMD_ENABLE_ALARM:
                rtcTimerPage[RTC_PAGE] |= 0x4;
                rtcAlarmPage[RTC_PAGE] |= 0x4;
                rtcFreePage0[RTC_PAGE] |= 0x4;
                rtcFreePage1[RTC_PAGE] |= 0x4;
                break;

            default:
                break;
        }
    }

    private void rtcWrite() {
        int reg = registers[WRITE_LO];
        if (reg >= RTC_PAGE) {
            return;
        }
        int out = registers[WRITE_HI];
        switch (registers[ADDR_LO]) {
            case 0:
                rtcTimerPage[reg] = out & RTC_MASK[reg];
                break;
            case 2:
                rtcAlarmPage[reg] = out & RTC_MASK[reg | 0x10];
                break;
            case 4:
                rtcFreePage0[reg] = out;
                break;
            case 6:
                rtcFreePage1[reg] = out;
                break;
            default:
                break;
        }
    }

    @Override
    public int getByte(int address) {
        if (address < 0x4000) {
            return getRomByte(0, address);
        } else if (address < 0x8000) {
            int bank = (registers[BANK_LO] | (registers[BANK_HI] << 4)) % romBanks;
            return getRomByte(bank, address - 0x4000);
        }
        if ((address & 1) == 1) {
            return 0xff;
        }
        int ramAddress = ((registers[ADDR_HI] << 4) & 0x10) | registers[ADDR_LO];
        switch (selectedReg) {
            case ACTIVE:
                return 0xf1;

            case READ_LO:
            case READ_HI: {
                int value = 0xf0;
                switch (registers[ADDR_HI] >> 1) {
                    case 0x1: // RAM read
                        value = ram[ramAddress];
                        break;

                    case 0x2: // command results
                        latchRtc();
                        switch (ramAddress) {
                            case CMD_MINUTE_READ:
                                value = (rtcTimerPage[RTC_MINUTE_10] << 4) | rtcTimerPage[RTC_MINUTE_1];
                                break;
                            case CMD_HOUR_READ:
                                value = (rtcTimerPage[RTC_HOUR_10] << 4) | rtcTimerPage[RTC_HOUR_1];
                                break;
                            default:
                                value = ramAddress;
                                break;
                        }
                        break;

                    case 0x4: { // RTC register read
                        latchRtc();
                        int reg = registers[WRITE_LO];
                        if (reg > RTC_PAGE) {
                            value = 0;
                        } else {
                            value = rtcTimerPage[reg];
                        }
                        break;
                    }

                    default:
                        break;
                }
                if (selectedReg == READ_HI) {
                    value >>= 4;
                }
                return value | 0xf0;
            }

            default:
                return 0xf1;
        }
    }

    /**
     * Advances the BCD calendar of the TAMA6 chip by the wall-clock time elapsed since the
     * last latch.
     */
    private void latchRtc() {
        long now = System.currentTimeMillis() / 1000;
        long t = now - lastRtcSecond;
        lastRtcSecond = now;
        if (t <= 0 || rtcDisabled) {
            return;
        }

        boolean is24hour = rtcAlarmPage[RTC_PA1_24_HOUR] != 0;

        long diff = rtcTimerPage[RTC_SECOND_1] + rtcTimerPage[RTC_SECOND_10] * 10 + t % 60;
        rtcTimerPage[RTC_SECOND_1] = (int) (diff % 10);
        rtcTimerPage[RTC_SECOND_10] = (int) ((diff % 60) / 10);
        t = t / 60 + diff / 60;

        diff = rtcTimerPage[RTC_MINUTE_1] + rtcTimerPage[RTC_MINUTE_10] * 10 + t % 60;
        rtcTimerPage[RTC_MINUTE_1] = (int) (diff % 10);
        rtcTimerPage[RTC_MINUTE_10] = (int) ((diff % 60) / 10);
        t = t / 60 + diff / 60;

        diff = rtcTimerPage[RTC_HOUR_1];
        if (is24hour) {
            diff += rtcTimerPage[RTC_HOUR_10] * 10;
        } else {
            int hour10 = rtcTimerPage[RTC_HOUR_10];
            diff += (hour10 & 1) * 10 + (hour10 & 2) * 12;
        }
        diff += t % 24;
        if (is24hour) {
            rtcTimerPage[RTC_HOUR_1] = (int) ((diff % 24) % 10);
            rtcTimerPage[RTC_HOUR_10] = (int) ((diff % 24) / 10);
        } else {
            rtcTimerPage[RTC_HOUR_1] = (int) ((diff % 12) % 10);
            rtcTimerPage[RTC_HOUR_10] = (int) ((diff % 12) / 10 + (diff / 12) * 2);
        }
        t = t / 24 + diff / 24;
        if (t == 0) {
            return;
        }

        int day = rtcTimerPage[RTC_DAY_1] + rtcTimerPage[RTC_DAY_10] * 10;
        int month = rtcTimerPage[RTC_MONTH_1] + rtcTimerPage[RTC_MONTH_10] * 10;
        int year = rtcTimerPage[RTC_YEAR_1] + rtcTimerPage[RTC_YEAR_10] * 10;
        int leapYear = rtcAlarmPage[RTC_PA1_LEAP_YEAR];
        int dayOfWeek = rtcTimerPage[RTC_WEEK];

        long dayInYear = dayOfYear(day, month, leapYear) + t;
        while (dayInYear > ((leapYear & 3) != 0 ? 365 : 366)) {
            dayInYear -= (year % 4 != 0) ? 365 : 366;
            year++;
            leapYear++;
        }
        dayOfWeek = (int) ((dayOfWeek + t) % 7);
        year %= 100;
        leapYear &= 3;

        day = dayOfMonth((int) dayInYear, leapYear);
        month = monthOfYear((int) dayInYear, leapYear);

        rtcTimerPage[RTC_WEEK] = dayOfWeek;
        rtcAlarmPage[RTC_PA1_LEAP_YEAR] = leapYear;
        rtcTimerPage[RTC_DAY_1] = day % 10;
        rtcTimerPage[RTC_DAY_10] = day / 10;
        rtcTimerPage[RTC_MONTH_1] = month % 10;
        rtcTimerPage[RTC_MONTH_10] = month / 10;
        rtcTimerPage[RTC_YEAR_1] = year % 10;
        rtcTimerPage[RTC_YEAR_10] = year / 10;
    }

    private static int dayOfYear(int day, int month, int year) {
        if (month < 1 || month > 12) {
            return 1;
        }
        int result = day + DAYS_TO_MONTH[month];
        if (month > 2 && (year & 3) == 0) {
            result++;
        }
        return result;
    }

    private static int monthOfYear(int day, int year) {
        for (int month = 1; month < 12; month++) {
            if (day <= DAYS_TO_MONTH[month + 1]) {
                return month;
            }
            if (month == 2 && (year & 3) == 0) {
                if (day == 60) {
                    return 2;
                }
                day--;
            }
        }
        return 12;
    }

    private static int dayOfMonth(int day, int year) {
        for (int month = 1; month < 12; month++) {
            if (day <= DAYS_TO_MONTH[month + 1]) {
                return day - DAYS_TO_MONTH[month];
            }
            if (month == 2 && (year & 3) == 0) {
                if (day == 60) {
                    return 29;
                }
                day--;
            }
        }
        return day - DAYS_TO_MONTH[12];
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
        long[] clockData = new long[12];
        clockData[0] = packPage(rtcTimerPage);
        clockData[1] = packPage(rtcAlarmPage);
        clockData[2] = packPage(rtcFreePage0);
        clockData[3] = packPage(rtcFreePage1);
        clockData[4] = rtcDisabled ? 1 : 0;
        clockData[5] = lastRtcSecond;
        battery.saveRamWithClock(ram, clockData);
        battery.flush();
    }

    private static long packPage(int[] page) {
        long result = 0;
        for (int i = 0; i < 16; i++) {
            result |= (long) (page[i] & 0x0f) << (i * 4);
        }
        return result;
    }

    private static void unpackPage(int[] page, long value) {
        for (int i = 0; i < 16; i++) {
            page[i] = (int) ((value >> (i * 4)) & 0x0f);
        }
    }

    @Override
    public Memento<MemoryController> saveToMemento() {
        return new Tama5Memento(battery.saveToMemento(), ram.clone(), selectedReg, registers.clone(),
                rtcTimerPage.clone(), rtcAlarmPage.clone(), rtcFreePage0.clone(), rtcFreePage1.clone(),
                rtcDisabled, lastRtcSecond, ramUpdated);
    }

    @Override
    public void restoreFromMemento(Memento<MemoryController> memento) {
        if (!(memento instanceof Tama5Memento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        battery.restoreFromMemento(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.selectedReg = mem.selectedReg;
        System.arraycopy(mem.registers, 0, this.registers, 0, this.registers.length);
        System.arraycopy(mem.rtcTimerPage, 0, this.rtcTimerPage, 0, 16);
        System.arraycopy(mem.rtcAlarmPage, 0, this.rtcAlarmPage, 0, 16);
        System.arraycopy(mem.rtcFreePage0, 0, this.rtcFreePage0, 0, 16);
        System.arraycopy(mem.rtcFreePage1, 0, this.rtcFreePage1, 0, 16);
        this.rtcDisabled = mem.rtcDisabled;
        this.lastRtcSecond = mem.lastRtcSecond;
        this.ramUpdated = mem.ramUpdated;
    }

    private record Tama5Memento(Memento<Battery> batteryMemento, int[] ram, int selectedReg, int[] registers,
                                int[] rtcTimerPage, int[] rtcAlarmPage, int[] rtcFreePage0, int[] rtcFreePage1,
                                boolean rtcDisabled, long lastRtcSecond,
                                boolean ramUpdated) implements Memento<MemoryController> {
    }
}
