package eu.rekawek.coffeegb.core.memory.cart;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memento.Originator;
import eu.rekawek.coffeegb.core.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.core.memory.cart.battery.FileBattery;
import eu.rekawek.coffeegb.core.memory.cart.type.*;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.Serializable;

public class Cartridge implements AddressSpace, Serializable, Originator<Cartridge> {

    private final MemoryController addressSpace;

    private final Battery battery;

    public Cartridge(Rom rom, boolean supportBatterySaves) {
        this(rom, supportBatterySaves && rom.getFile() != null ? createBattery(rom) : Battery.NULL_BATTERY);
    }

    private static final int[] NINTENDO_LOGO = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E};

    public Cartridge(Rom rom, Battery battery) {
        this.battery = battery;

        var type = rom.getType();
        if (type.isMmm01()) {
            // the dump has the menu program first; the mapper wants it last
            addressSpace = new Mmm01(rom, battery, true);
        } else if (isHiddenMmm01(rom)) {
            addressSpace = new Mmm01(rom, battery, false);
        } else if (isMani32kMulticart(rom)) {
            addressSpace = new Mani32kMulticart(rom);
        } else if (isDuzMulticart(rom)) {
            addressSpace = new DuzMulticart(rom, battery);
        } else if (sachenBase(rom) >= 0) {
            addressSpace = new SachenMmc(rom, sachenBase(rom));
        } else if (type.isHuc1()) {
            addressSpace = new Huc1(rom, battery);
        } else if (type.isHuc3()) {
            addressSpace = new Huc3(rom, battery);
        } else if (type.isTama5()) {
            addressSpace = new Tama5(rom, battery);
        } else if (type.isMbc1()) {
            addressSpace = new Mbc1(rom, battery);
        } else if (type.isMbc2()) {
            addressSpace = new Mbc2(rom, battery);
        } else if (type.isMbc3()) {
            addressSpace = new Mbc3(rom, battery);
        } else if (type.isMbc5()) {
            addressSpace = new Mbc5(rom, battery);
        } else if (type.isMbc6()) {
            addressSpace = new Mbc6(rom, battery);
        } else if (type.isMbc7()) {
            addressSpace = new Mbc7(rom, battery);
        } else if (type.isPocketCamera()) {
            addressSpace = new PocketCamera(rom, battery);
        } else if (rom.getRom().length > 0x8000 && !hasValidLogo(rom)) {
            // Datel Action Replay / GameShark carts: "ROM only" type with a deliberately
            // bad Nintendo logo (the ASIC swaps it after the boot check on hardware) and
            // banking registers at 0x7FE0/0x7FE1 (issue #66)
            addressSpace = new Datel(rom, battery);
        } else if (rom.getRom().length >= 0x20000) {
            // "ROM only" carts of 128 KB and more are Wisdom Tree style unlicensed
            // mappers with a single switchable 32 KB window (issue #61); smaller
            // oversized type-0 dumps are treated as plain 32 KB ROMs like on hardware
            addressSpace = new WisdomTree(rom);
        } else {
            addressSpace = new BasicRom(rom);
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

    /**
     * MMM01 multicarts are often dumped with a plain MBC header in the first game's bank
     * while the menu program (with the real header) sits in the last 32 KB. Detect them by
     * the duplicated logo and the menu header's type (like SameBoy does).
     */
    /**
     * The Duz multicart (Pokemon Red-Blue 2-in-1 and similar) is an MBC3-typed cart whose
     * individual games each carry their own Nintendo logo at a 16 KB boundary past bank 0.
     * A normal single-game cart has exactly one logo.
     */
    /**
     * The Mani "TETRIS SET" style multicart (issue #74): a menu in the first 32 KB block
     * followed by plain 32 KB games, each with a valid logo and a plain-ROM (0x00) type
     * byte in its own header. The Duz multicarts also duplicate logos at 32 KB blocks,
     * but their sub-games are banked (non-zero type bytes).
     */
    private static boolean isMani32kMulticart(Rom rom) {
        int[] data = rom.getRom();
        if (rom.getType() == CartridgeType.ROM || data.length < 0x10000) {
            return false;
        }
        int subGames = 0;
        for (int block = 1; block * 0x8000 + 0x150 <= data.length; block++) {
            int base = block * 0x8000;
            boolean logo = true;
            for (int i = 0; i < NINTENDO_LOGO.length; i++) {
                if (data[base + 0x104 + i] != NINTENDO_LOGO[i]) {
                    logo = false;
                    break;
                }
            }
            if (!logo) {
                // trailing blocks may be pad filler; games are contiguous from block 1
                break;
            }
            if (data[base + 0x147] != 0x00) {
                return false;
            }
            subGames++;
        }
        return subGames >= 2;
    }

    private static boolean isDuzMulticart(Rom rom) {
        int[] data = rom.getRom();
        if (!rom.getType().isMbc3() || data.length < 0x10000) {
            return false;
        }
        for (int bank = 2; bank * 0x4000 + 0x134 < data.length; bank += 2) {
            boolean logo = true;
            for (int i = 0; i < NINTENDO_LOGO.length; i++) {
                if (data[bank * 0x4000 + 0x104 + i] != NINTENDO_LOGO[i]) {
                    logo = false;
                    break;
                }
            }
            if (logo) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects the Sachen MMC1/MMC2 multicarts (issues #73/#74/#75) and returns the
     * power-on base bank, or -1 for regular carts. The 4B collections have a header
     * logo that starts with the first logo byte but is otherwise scrambled (the real
     * mapper unscrambles it for the boot ROM); the MMC2 carts hide a valid (CGB,
     * half-checked) logo at the header of a later bank, which is also their boot base.
     */
    private static int sachenBase(Rom rom) {
        int[] data = rom.getRom();
        if (hasValidLogo(rom) || data.length < 0x10000) {
            return -1;
        }
        // MMC2: a bank >0 carries the real (half) logo
        for (int bank = 1; bank * 0x4000 + 0x134 <= data.length; bank++) {
            boolean match = true;
            for (int i = 0; i < NINTENDO_LOGO.length / 2; i++) {
                if (data[bank * 0x4000 + 0x104 + i] != NINTENDO_LOGO[i]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return bank;
            }
        }
        // MMC1 (4B-xxx): scrambled logo that keeps the first byte
        if (data[0x104] == 0xce && data[0x105] != 0xed) {
            return 0;
        }
        return -1;
    }

    private static boolean hasValidLogo(Rom rom) {
        int[] data = rom.getRom();
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            if (data[0x104 + i] != NINTENDO_LOGO[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHiddenMmm01(Rom rom) {
        int[] data = rom.getRom();
        int menuBase = data.length - 0x8000;
        if (menuBase <= 0) {
            return false;
        }
        for (int i = 0; i < NINTENDO_LOGO.length; i++) {
            if (data[0x104 + i] != data[menuBase + 0x104 + i]) {
                return false;
            }
        }
        int menuType = data[menuBase + 0x147];
        return menuType == 0x0b || menuType == 0x0c || menuType == 0x0d || menuType == 0x11;
    }

    private static Battery createBattery(Rom rom) {
        if (rom.getType().isBattery()) {
            int ramSize = 0x2000 * rom.getRamBanks();
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
