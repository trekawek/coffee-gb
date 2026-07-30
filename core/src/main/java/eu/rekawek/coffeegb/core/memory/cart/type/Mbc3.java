package eu.rekawek.coffeegb.core.memory.cart.type;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.debug.trace.MapperRtcTrace;

import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.memory.cart.MemoryController;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;

import java.util.Arrays;

public class Mbc3 implements MemoryController {

    private final int[] cartridge;

    private final int[] ram;

    private final RealTimeClock clock;

    private final Battery battery;

    private final boolean mbc30;

    private int selectedRamBank;

    private int selectedRomBank = 1;

    private boolean ramEnabled;

    private transient DebugHooks debugHooks;

    public Mbc3(Rom rom, Battery battery) {
        this(rom, battery, new SystemTimeSource(), ClockSpec.LEGACY);
    }

    public Mbc3(Rom rom, Battery battery, TimeSource timeSource) {
        this(rom, battery, timeSource, ClockSpec.LEGACY);
    }

    public Mbc3(Rom rom, Battery battery, TimeSource timeSource, ClockSpec clockSpec) {
        this.cartridge = rom.getRom();
        this.ram = new int[0x2000 * Math.max(rom.getRamBanks(), 1)];
        Arrays.fill(ram, 0xff);
        this.clock = new RealTimeClock(timeSource, clockSpec);
        this.battery = battery;
        this.mbc30 = rom.getRomBanks() > 128 || rom.getRamBanks() > 4;

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
        DebugHooks hooks = debugHooks;
        int previousRomBank = selectedRomBank;
        int previousRamBank = selectedRamBank;
        boolean previousRamEnabled = ramEnabled;
        if (address >= 0x0000 && address < 0x2000) {
            ramEnabled = (value & 0x0f) == 0x0a;
        } else if (address >= 0x2000 && address < 0x4000) {
            int bank = value & (mbc30 ? 0xff : 0x7f);
            selectRomBank(bank);
        } else if (address >= 0x4000 && address < 0x6000) {
            selectedRamBank = value & 0x0f;
        } else if (address >= 0x6000 && address < 0x8000) {
            clock.latch();
            if (hooks != null) {
                hooks.onMapperRtcEvent(
                        MapperRtcTrace.Kind.RTC_LATCHED, -1, value & 0xffL);
            }
        } else if (address >= 0xa000 && address < 0xc000 && ramEnabled && isRamBankSelected()) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                ram[ramAddress] = value;
            }
        } else if (address >= 0xa000 && address < 0xc000 && ramEnabled) {
            setTimer(value);
        }
        if (hooks != null) {
            if (selectedRomBank != previousRomBank) {
                hooks.onMapperRtcEvent(
                        MapperRtcTrace.Kind.ROM_BANK_CHANGED, -1, selectedRomBank);
            }
            if (selectedRamBank != previousRamBank) {
                boolean previouslyRtc = isRtcRegister(previousRamBank);
                boolean currentlyRtc = isRtcRegister(selectedRamBank);
                if (currentlyRtc) {
                    hooks.onMapperRtcEvent(
                            MapperRtcTrace.Kind.RTC_REGISTER_SELECTED,
                            selectedRamBank,
                            1);
                } else {
                    if (previouslyRtc) {
                        hooks.onMapperRtcEvent(
                                MapperRtcTrace.Kind.RTC_REGISTER_SELECTED, -1, 0);
                    }
                    if (isRamBank(selectedRamBank)) {
                        hooks.onMapperRtcEvent(
                                MapperRtcTrace.Kind.RAM_BANK_CHANGED,
                                -1,
                                selectedRamBank);
                    }
                }
            }
            if (ramEnabled != previousRamEnabled) {
                hooks.onMapperRtcEvent(
                        MapperRtcTrace.Kind.RAM_ENABLE_CHANGED,
                        -1,
                        ramEnabled ? 1 : 0);
            }
        }
    }

    @Override
    public void setDebugHooks(DebugHooks hooks) {
        debugHooks = hooks;
    }

    @Override
    public void flushRam() {
        battery.saveRamWithClock(ram, clock.serialize());
        battery.flush();
    }

    @Override
    public void tick() {
        clock.tick();
    }

    @Override
    public void setClockPaused(boolean paused) {
        clock.setEmulationPaused(paused);
    }

    public RealTimeClock.RuntimeState captureRtcRuntimeState() {
        return clock.captureRuntimeState();
    }

    public void restoreRtcRuntimeState(RealTimeClock.RuntimeState state) {
        clock.restoreRuntimeState(state);
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
        } else if (address >= 0xa000 && address < 0xc000 && !ramEnabled) {
            return 0xff;
        } else if (address >= 0xa000 && address < 0xc000 && isRamBankSelected()) {
            int ramAddress = getRamAddress(address);
            if (ramAddress < ram.length) {
                return ram[ramAddress];
            } else {
                return 0xff;
            }
        } else if (address >= 0xa000 && address < 0xc000) {
            int value = getTimer();
            DebugHooks hooks = debugHooks;
            if (hooks != null && isRtcRegisterSelected()) {
                hooks.onMapperRtcEvent(
                        MapperRtcTrace.Kind.RTC_REGISTER_READ,
                        selectedRamBank,
                        value & 0xffL);
            }
            return value;
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

    private boolean isRamBankSelected() {
        return isRamBank(selectedRamBank);
    }

    private boolean isRamBank(int bank) {
        return bank >= 0 && bank < (mbc30 ? 8 : 4);
    }

    private boolean isRtcRegisterSelected() {
        return isRtcRegister(selectedRamBank);
    }

    private static boolean isRtcRegister(int bank) {
        return bank >= 0x08 && bank <= 0x0c;
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
        if (!isRtcRegisterSelected()) {
            return;
        }
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
                clock.setDayCounterLow(value);
                break;

            case 0x0c:
                clock.setDayCounterHigh(value);
                clock.setHalt((value & (1 << 6)) != 0);
                clock.setCounterOverflow((value & (1 << 7)) != 0);
                break;
        }
        DebugHooks hooks = debugHooks;
        if (hooks != null) {
            hooks.onMapperRtcEvent(
                    MapperRtcTrace.Kind.RTC_REGISTER_WRITTEN,
                    selectedRamBank,
                    value & 0xffL);
        }
    }

    @Override
    public ComponentState<MemoryController> captureState() {
        return new Mbc3State(ram.clone(), clock.captureState(), battery.captureState(), selectedRamBank,
                selectedRomBank, ramEnabled);
    }

    @Override
    public ComponentState<MemoryController> captureState(MachineStateCapture capture) {
        return new Mbc3State(
                capture.ints(ram),
                clock.captureState(capture),
                battery.captureState(capture),
                selectedRamBank,
                selectedRomBank,
                ramEnabled);
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        battery.declareMachineStatePayloads(capture);
        capture.declareInts(ram);
    }

    @Override
    public void restoreState(ComponentState<MemoryController> state) {
        if (!(state instanceof Mbc3State mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        if (this.ram.length != mem.ram.length) {
            throw new IllegalArgumentException("ComponentState ram length doesn't match");
        }
        clock.restoreState(mem.clockMemento);
        battery.restoreState(mem.batteryMemento);
        System.arraycopy(mem.ram, 0, this.ram, 0, this.ram.length);
        this.selectedRamBank = mem.selectedRamBank;
        this.selectedRomBank = mem.selectedRomBank;
        this.ramEnabled = mem.ramEnabled;
    }

    private record Mbc3State(int[] ram, ComponentState<RealTimeClock> clockMemento, ComponentState<Battery> batteryMemento,
                               int selectedRamBank, int selectedRomBank,
                               boolean ramEnabled) implements ComponentState<MemoryController> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record Mbc3Memento(int[] ram, Memento<RealTimeClock> clockMemento, Memento<Battery> batteryMemento,
                               int selectedRamBank, int selectedRomBank,
                               boolean ramEnabled) implements Memento<MemoryController> {
    }
}
