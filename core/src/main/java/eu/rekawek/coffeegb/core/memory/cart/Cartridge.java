package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.memento.Memento;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.debug.DebugHooks;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.hardware.ClockSpec;
import eu.rekawek.coffeegb.core.state.MachineStateCapture;
import eu.rekawek.coffeegb.core.state.ComponentState;
import eu.rekawek.coffeegb.core.state.StatefulComponent;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryFlush;
import eu.rekawek.coffeegb.core.memory.cart.battery.BatteryStorage;
import eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.RealTimeClock;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;
import eu.rekawek.coffeegb.core.memory.cart.type.*;
import java.io.File;
import java.nio.file.Path;

public class Cartridge implements AddressSpace, StatefulComponent<Cartridge> {

    private final MemoryController addressSpace;

    /** Parser-corrected loaded-image bytes retained for side-effect-free debugger reads. */
    private final int[] debugRom;

    private final Battery battery;

    public Cartridge(Rom rom, boolean supportBatterySaves) {
        this(rom, supportBatterySaves && canPersist(rom, null)
                        ? createBattery(rom, null) : Battery.NULL_BATTERY,
                new SystemTimeSource(), ClockSpec.LEGACY);
    }

    public Cartridge(Rom rom, Battery battery) {
        this(rom, battery, new SystemTimeSource(), ClockSpec.LEGACY);
    }

    public Cartridge(Rom rom, boolean supportBatterySaves, TimeSource rtcTimeSource) {
        this(rom, supportBatterySaves && canPersist(rom, null)
                        ? createBattery(rom, null) : Battery.NULL_BATTERY,
                rtcTimeSource, ClockSpec.LEGACY);
    }

    public Cartridge(Rom rom, Battery battery, TimeSource rtcTimeSource) {
        this(rom, battery, rtcTimeSource, ClockSpec.LEGACY);
    }

    public Cartridge(Rom rom, boolean supportBatterySaves, TimeSource rtcTimeSource,
                     ClockSpec clockSpec) {
        this(rom, supportBatterySaves && canPersist(rom, null)
                        ? createBattery(rom, null) : Battery.NULL_BATTERY,
                rtcTimeSource, clockSpec);
    }

    public Cartridge(
            Rom rom,
            boolean supportBatterySaves,
            BatteryStorage batteryStorage,
            TimeSource rtcTimeSource,
            ClockSpec clockSpec) {
        this(
                rom,
                supportBatterySaves && canPersist(rom, batteryStorage)
                        ? createBattery(rom, batteryStorage)
                        : Battery.NULL_BATTERY,
                rtcTimeSource,
                clockSpec);
    }

    public Cartridge(Rom rom, Battery battery, TimeSource rtcTimeSource, ClockSpec clockSpec) {
        this.battery = battery;
        this.debugRom = rom.getRom();
        this.addressSpace = createMemoryController(rom, battery, rtcTimeSource, clockSpec);
    }

    private static MemoryController createMemoryController(Rom rom, Battery battery,
                                                           TimeSource rtcTimeSource,
                                                           ClockSpec clockSpec) {
        return switch (rom.getCartridgeProperties().getMapper()) {
            case BUNG_EMS -> new BungEms(rom, battery);
            case HIDDEN_MMM01 -> new Mmm01(rom, battery, false);
            case MANI_32K_MULTICART -> new Mani32kMulticart(rom);
            case SL_MULTICART -> new SlMulticart(rom, battery);
            case DUZ_MULTICART -> new DuzMulticart(rom, battery);
            case BHGOS_MULTICART -> new BhgosMulticart(rom, battery);
            case MAKON_NT_OLD_2 -> new MakonNtOld2(rom, battery);
            case LI_CHENG -> new LiCheng(rom, battery);
            case VF001_ZOOK -> new Vf001Zook(rom, battery);
            case VF001_GENERAL -> new Vf001General(rom, battery);
            case BBD -> new Bbd(rom, battery);
            case SINTAX -> new Sintax(rom, battery);
            case SACHEN_MMC1 -> new SachenMmc(rom, false, true);
            case SACHEN_MMC2 -> new SachenMmc(rom, true, true);
            case SACHEN_MMC2_LINEAR -> new SachenMmc(rom, true, false);
            case SACHEN_COOKED -> new SachenMmc(rom, 0);
            case DATEL -> new Datel(rom, battery);
            case XPLODER_GB -> new XploderGb(rom, battery);
            case WISDOM_TREE -> new WisdomTree(rom);
            case MBC1 -> new Mbc1(rom, battery);
            case POCKET_CAMERA -> new PocketCamera(rom, battery);
            case MBC5 -> new Mbc5(rom, battery);
            case STANDARD -> createStandardMemoryController(rom, battery, rtcTimeSource, clockSpec);
        };
    }

    private static MemoryController createStandardMemoryController(Rom rom, Battery battery,
                                                                   TimeSource rtcTimeSource,
                                                                   ClockSpec clockSpec) {
        var type = rom.getType();
        if (type.isMmm01()) {
            // The dump has the menu program first; the mapper wants it last.
            return new Mmm01(rom, battery, true);
        } else if (type.isHuc1()) {
            return new Huc1(rom, battery);
        } else if (type.isHuc3()) {
            return new Huc3(rom, battery, rtcTimeSource);
        } else if (type.isTama5()) {
            return new Tama5(rom, battery, rtcTimeSource);
        } else if (type.isMbc1()) {
            return new Mbc1(rom, battery);
        } else if (type.isMbc2()) {
            return new Mbc2(rom, battery);
        } else if (type.isMbc3()) {
            return new Mbc3(rom, battery, rtcTimeSource, clockSpec);
        } else if (type.isMbc5()) {
            return new Mbc5(rom, battery);
        } else if (type.isMbc6()) {
            return new Mbc6(rom, battery);
        } else if (type.isMbc7()) {
            return new Mbc7(rom, battery);
        } else if (type.isPocketCamera()) {
            return new PocketCamera(rom, battery);
        } else {
            return new BasicRom(rom, battery);
        }
    }

    public void init(EventBus eventBus) {
        battery.init(eventBus);
        addressSpace.init(eventBus);
    }

    /** Supplies only the persistence error route for a pass-through slot cartridge. */
    public void initBattery(EventBus eventBus) {
        battery.init(eventBus);
    }

    @Override
    public boolean accepts(int address) {
        return addressSpace.accepts(address);
    }

    @Override
    public void setByte(int address, int value) {
        addressSpace.setByte(address, value);
    }

    @Override
    public int getByte(int address) {
        return addressSpace.getByte(address);
    }

    /**
     * Copies the first 64 KiB view of the parser-corrected loaded ROM image without invoking mapper
     * read logic. Missing image bytes read as the cartridge bus's open value. The address is an
     * image offset, not a CPU address, and this view is intentionally not the mapper's current CPU
     * window.
     */
    public byte[] readDebugRom(int address, int length) {
        if (address < 0 || address > 0xffff || length < 0
                || (long) address + length > 0x10000L) {
            throw new IllegalArgumentException("Invalid debug ROM range");
        }
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            int physicalAddress = address + i;
            bytes[i] = (byte) (physicalAddress < debugRom.length
                    ? debugRom[physicalAddress] : 0xff);
        }
        return bytes;
    }

    public void flushBattery() {
        addressSpace.flushRam();
    }

    /** Captures RAM and RTC data without running persistence I/O on the emulation thread. */
    public BatteryFlush prepareBatteryFlush() {
        return battery.prepareFlush(addressSpace::flushRam);
    }

    /** Advances cartridge hardware clocked from the Game Boy master clock. */
    public void tick() {
        addressSpace.tick();
    }

    public void setDebugHooks(DebugHooks hooks) {
        addressSpace.setDebugHooks(hooks);
    }

    /** Notifies independent cartridge clocks that emulation has paused or resumed. */
    public void setClockPaused(boolean paused) {
        addressSpace.setClockPaused(paused);
    }

    /** Bypasses mapper-side boot-ROM handshakes when the console boot itself is skipped. */
    public void skipBoot() {
        addressSpace.skipBoot();
    }

    /** Returns detached MBC3 pause bookkeeping, or null for mappers without that clock. */
    public RealTimeClock.RuntimeState captureRtcRuntimeState() {
        return addressSpace instanceof Mbc3 mbc3 ? mbc3.captureRtcRuntimeState() : null;
    }

    public void validateRtcRuntimeState(RealTimeClock.RuntimeState state) {
        if (!(addressSpace instanceof Mbc3 mbc3)) {
            if (state != null) {
                throw new IllegalArgumentException("RTC runtime state supplied for a non-MBC3 cartridge");
            }
            return;
        }
        if (state == null) {
            throw new IllegalArgumentException("MBC3 RTC runtime state is missing");
        }
        if (!state.emulationPaused() && state.pauseStartedMillis() != 0) {
            throw new IllegalArgumentException("Running MBC3 RTC has a stale pause reference");
        }
    }

    public void restoreRtcRuntimeState(RealTimeClock.RuntimeState state) {
        validateRtcRuntimeState(state);
        if (addressSpace instanceof Mbc3 mbc3) {
            mbc3.restoreRtcRuntimeState(state);
        }
    }

    /** The Sachen MMC2 needs to observe reads on the upper half of the bus (see Mmu). */
    public SachenMmc getSachenMmc() {
        return addressSpace instanceof SachenMmc s ? s : null;
    }

    /** The Datel Action Replay mapper, for wiring its pass-through game slot. */
    public Datel getDatel() {
        return addressSpace instanceof Datel d ? d : null;
    }

    /** The mapper itself, for mounting this cartridge in another cartridge's slot. */
    public MemoryController getMemoryController() {
        return addressSpace;
    }

    private static Battery createBattery(Rom rom, BatteryStorage configuredStorage) {
        boolean xploderGb = rom.getCartridgeProperties().getMapper()
                == CartridgeProperties.Mapper.XPLODER_GB;
        if (rom.getType().isBattery() || xploderGb) {
            // Existing MBC implementations expose RAM in 8 KiB banks. Plain ROM+RAM
            // is the exception: its 2 KiB header size is mirrored across A000-BFFF.
            int ramSize = rom.getType() == CartridgeType.ROM_RAM_BATTERY
                    ? rom.getRamSize() : 0x2000 * rom.getRamBanks();
            if (rom.getCartridgeProperties().getMapper() == CartridgeProperties.Mapper.BUNG_EMS) {
                ramSize = 0x8000;
            }
            if (rom.getCartridgeProperties().getMapper() == CartridgeProperties.Mapper.SL_MULTICART) {
                ramSize = 0x20000;
            }
            if (xploderGb) {
                ramSize = 0x20000;
            }
            if (ramSize == 0 && rom.getType().isRam()) {
                ramSize = 0x2000;
            }
            if (rom.getType().isMbc6()) {
                ramSize = 0x8000 + 0x100000; // 32KB RAM + 1MB Flash
            }
            if (rom.getType().isTama5()) {
                ramSize = 0x20;
            }
            if (rom.getType().isMbc7()) {
                ramSize = 0x100; // 93LC56 EEPROM
            }
            BatteryStorage storage = configuredStorage;
            if (storage == null) {
                Path savePath = rom.getOrigin().persistencePath(".sav").orElseThrow();
                Path legacyPath =
                        rom.getOrigin()
                                .legacyArchivePersistencePath(".sav")
                                .orElse(null);
                storage =
                        legacyPath == null
                                ? BatteryStorage.direct(savePath)
                                : BatteryStorage.direct(savePath, java.util.List.of(legacyPath));
            }
            return new FileBattery(storage, ramSize);
        } else {
            return Battery.NULL_BATTERY;
        }
    }

    private static boolean canPersist(Rom rom, BatteryStorage configuredStorage) {
        return configuredStorage != null || rom.getOrigin().persistencePath(".sav").isPresent();
    }

    /** Reconfigures a running file-backed battery without performing filesystem I/O. */
    public void setBatteryStorage(BatteryStorage storage) {
        if (battery instanceof FileBattery fileBattery) {
            fileBattery.setStorage(storage);
        }
    }

    public static File getSaveName(File romFile) {
        return RomOrigin.directFile(romFile.toPath())
                .persistencePath(".sav")
                .orElseThrow()
                .toFile();
    }

    public static File getSaveName(Rom rom) {
        return rom.getOrigin()
                        .persistencePath(".sav")
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "In-memory ROM has no implicit battery path"))
                        .toFile();
    }

    @Override
    public ComponentState<Cartridge> captureState() {
        return new CartridgeState(addressSpace.captureState(), battery.captureState());
    }

    @Override
    public ComponentState<Cartridge> captureState(MachineStateCapture capture) {
        return new CartridgeState(
                addressSpace.captureState(capture),
                battery.captureState(capture));
    }

    @Override
    public void declareMachineStatePayloads(MachineStateCapture capture) {
        addressSpace.declareMachineStatePayloads(capture);
        battery.declareMachineStatePayloads(capture);
    }

    @Override
    public void restoreState(ComponentState<Cartridge> state) {
        if (!(state instanceof CartridgeState mem)) {
            throw new IllegalArgumentException("Invalid state type");
        }
        this.addressSpace.restoreState(mem.memoryControllerMemento);
        this.battery.restoreState(mem.batteryMemento);
    }

    private record CartridgeState(ComponentState<MemoryController> memoryControllerMemento,
                                    ComponentState<Battery> batteryMemento) implements ComponentState<Cartridge> {
    }

    /** Importer-only compatibility record for released local snapshots. */
    private record CartridgeMemento(Memento<MemoryController> memoryControllerMemento,
                                    Memento<Battery> batteryMemento) implements Memento<Cartridge> {
    }
}
