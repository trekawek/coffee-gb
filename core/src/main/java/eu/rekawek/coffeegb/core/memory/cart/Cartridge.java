package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery;
import eu.rekawek.coffeegb.core.memory.cart.rtc.SystemTimeSource;
import eu.rekawek.coffeegb.core.memory.cart.rtc.TimeSource;
import eu.rekawek.coffeegb.core.memory.cart.type.*;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.Serializable;

public class Cartridge implements AddressSpace, Serializable, Originator<Cartridge> {

    private final MemoryController addressSpace;

    private final Battery battery;

    public Cartridge(Rom rom, boolean supportBatterySaves) {
        this(rom, supportBatterySaves && rom.getFile() != null ? createBattery(rom) : Battery.NULL_BATTERY,
                new SystemTimeSource());
    }

    public Cartridge(Rom rom, Battery battery) {
        this(rom, battery, new SystemTimeSource());
    }

    public Cartridge(Rom rom, boolean supportBatterySaves, TimeSource rtcTimeSource) {
        this(rom, supportBatterySaves && rom.getFile() != null ? createBattery(rom) : Battery.NULL_BATTERY,
                rtcTimeSource);
    }

    public Cartridge(Rom rom, Battery battery, TimeSource rtcTimeSource) {
        this.battery = battery;
        this.addressSpace = createMemoryController(rom, battery, rtcTimeSource);
    }

    private static MemoryController createMemoryController(Rom rom, Battery battery,
                                                           TimeSource rtcTimeSource) {
        return switch (rom.getCartridgeProperties().getMapper()) {
            case BUNG_EMS -> new BungEms(rom, battery);
            case HIDDEN_MMM01 -> new Mmm01(rom, battery, false);
            case MANI_32K_MULTICART -> new Mani32kMulticart(rom);
            case DUZ_MULTICART -> new DuzMulticart(rom, battery);
            case BHGOS_MULTICART -> new BhgosMulticart(rom, battery);
            case MAKON_NT_OLD_2 -> new MakonNtOld2(rom, battery);
            case BBD -> new Bbd(rom, battery);
            case SACHEN_MMC1 -> new SachenMmc(rom, false, true);
            case SACHEN_MMC2 -> new SachenMmc(rom, true, true);
            case SACHEN_MMC2_LINEAR -> new SachenMmc(rom, true, false);
            case SACHEN_COOKED -> new SachenMmc(rom, 0);
            case DATEL -> new Datel(rom, battery);
            case WISDOM_TREE -> new WisdomTree(rom);
            case MBC1 -> new Mbc1(rom, battery);
            case STANDARD -> createStandardMemoryController(rom, battery, rtcTimeSource);
        };
    }

    private static MemoryController createStandardMemoryController(Rom rom, Battery battery,
                                                                   TimeSource rtcTimeSource) {
        var type = rom.getType();
        if (type.isMmm01()) {
            // The dump has the menu program first; the mapper wants it last.
            return new Mmm01(rom, battery, true);
        } else if (type.isHuc1()) {
            return new Huc1(rom, battery);
        } else if (type.isHuc3()) {
            return new Huc3(rom, battery);
        } else if (type.isTama5()) {
            return new Tama5(rom, battery);
        } else if (type.isMbc1()) {
            return new Mbc1(rom, battery);
        } else if (type.isMbc2()) {
            return new Mbc2(rom, battery);
        } else if (type.isMbc3()) {
            return new Mbc3(rom, battery, rtcTimeSource);
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
        addressSpace.init(eventBus);
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

    public void flushBattery() {
        addressSpace.flushRam();
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

    private static Battery createBattery(Rom rom) {
        if (rom.getType().isBattery()) {
            // Existing MBC implementations expose RAM in 8 KiB banks. Plain ROM+RAM
            // is the exception: its 2 KiB header size is mirrored across A000-BFFF.
            int ramSize = rom.getType() == CartridgeType.ROM_RAM_BATTERY
                    ? rom.getRamSize() : 0x2000 * rom.getRamBanks();
            if (rom.getCartridgeProperties().getMapper() == CartridgeProperties.Mapper.BUNG_EMS) {
                ramSize = 0x8000;
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
            return new FileBattery(getSaveName(rom.getFile()), ramSize);
        } else {
            return Battery.NULL_BATTERY;
        }
    }

    public static File getSaveName(File romFile) {
        File parent = romFile.getParentFile();
        String baseName = FilenameUtils.removeExtension(romFile.getName());
        return new File(parent, baseName + ".sav");
    }

    @Override
    public Memento<Cartridge> saveToMemento() {
        return new CartridgeMemento(addressSpace.saveToMemento(), battery.saveToMemento());
    }

    @Override
    public void restoreFromMemento(Memento<Cartridge> memento) {
        if (!(memento instanceof CartridgeMemento mem)) {
            throw new IllegalArgumentException("Invalid memento type");
        }
        this.addressSpace.restoreFromMemento(mem.memoryControllerMemento);
        this.battery.restoreFromMemento(mem.batteryMemento);
    }

    private record CartridgeMemento(Memento<MemoryController> memoryControllerMemento,
                                    Memento<Battery> batteryMemento) implements Memento<Cartridge> {
    }
}
