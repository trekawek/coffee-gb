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
import java.util.zip.CRC32;

public class Cartridge implements AddressSpace, Serializable, Originator<Cartridge> {

    private final MemoryController addressSpace;

    private final Battery battery;

    public Cartridge(Rom rom, boolean supportBatterySaves) {
        this(rom, supportBatterySaves && rom.getFile() != null ? createBattery(rom) : Battery.NULL_BATTERY,
                new SystemTimeSource());
    }

    private static final int[] NINTENDO_LOGO = {0xCE, 0xED, 0x66, 0x66, 0xCC, 0x0D, 0x00, 0x0B, 0x03, 0x73, 0x00, 0x83, 0x00, 0x0C, 0x00, 0x0D, 0x00, 0x08, 0x11, 0x1F, 0x88, 0x89, 0x00, 0x0E, 0xDC, 0xCC, 0x6E, 0xE6, 0xDD, 0xDD, 0xD9, 0x99, 0xBB, 0xBB, 0x67, 0x63, 0x6E, 0x0E, 0xEC, 0xCC, 0xDD, 0xDC, 0x99, 0x9F, 0xBB, 0xB9, 0x33, 0x3E};

    public Cartridge(Rom rom, Battery battery) {
        this(rom, battery, new SystemTimeSource());
    }

    public Cartridge(Rom rom, boolean supportBatterySaves, TimeSource rtcTimeSource) {
        this(rom, supportBatterySaves && rom.getFile() != null ? createBattery(rom) : Battery.NULL_BATTERY,
                rtcTimeSource);
    }

    public Cartridge(Rom rom, Battery battery, TimeSource rtcTimeSource) {
        this.battery = battery;

        var type = rom.getType();
        if (isBungEms(rom)) {
            addressSpace = new BungEms(rom, battery);
        } else if (type.isMmm01()) {
            // the dump has the menu program first; the mapper wants it last
            addressSpace = new Mmm01(rom, battery, true);
        } else if (isHiddenMmm01(rom)) {
            addressSpace = new Mmm01(rom, battery, false);
        } else if (isMani32kMulticart(rom)) {
            addressSpace = new Mani32kMulticart(rom);
        } else if (isDuzMulticart(rom)) {
            addressSpace = new DuzMulticart(rom, battery);
        } else if (isBhgosMulticart(rom)) {
            addressSpace = new BhgosMulticart(rom, battery);
        } else if (sachenType(rom) >= 0) {
            addressSpace = new SachenMmc(rom, sachenType(rom) == 2);
        } else if (isCookedSachen(rom)) {
            // post-unlock ("cooked") Sachen dumps: no lockout, boot at base 0 like the
            // power-on cart (issues #73/#75)
            addressSpace = new SachenMmc(rom, 0);
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
            addressSpace = new Mbc3(rom, battery, rtcTimeSource);
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
            // mappers with a single switchable 32 KB window (issue #61)
            addressSpace = new WisdomTree(rom);
        } else if (rom.getRom().length > 0x8000) {
            // a "ROM only" header on a cart bigger than the 32 KB a no-MBC cart can address
            // is wrong (common in homebrew, e.g. Dimensionless Sample, #76): bank it as MBC1
            // like BGB does. MBC1's power-on mapping is identical to a plain 32 KB ROM, so
            // this never breaks a genuinely 32 KB-addressable dump.
            addressSpace = new Mbc1(rom, battery);
        } else {
            addressSpace = new BasicRom(rom, battery);
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

    /**
     * Whether this ROM is a Datel Action Replay / GameShark cart (deliberately bad logo on
     * an oversized "ROM only" image). These carts run on the Game Boy Color - the ASIC
     * presents a valid CGB header to the console - so the type mapping treats them as
     * colour carts despite the garbage CGB flag byte in the dump.
     */
    public static boolean isDatel(Rom rom) {
        return rom.getRom().length > 0x8000 && !hasValidLogo(rom)
                && rom.getType() == CartridgeType.ROM;
    }

    private static boolean isMani32kMulticart(Rom rom) {
        int[] data = rom.getRom();
        // MBC1-typed carts with per-game logos are MBC1M multicarts (handled by Mbc1);
        // the Mani TETRIS SET declares a bogus MBC3-family type
        if (rom.getType() == CartridgeType.ROM || rom.getType().isMbc1() || data.length < 0x10000) {
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
     * Blue Hippo's Game Boy Operating System multicarts keep the menu's MBC5 header but
     * add a register that relocates the complete 32 KiB cartridge window to a selected
     * embedded game. A standalone 32 KiB copy of the menu needs no special mapping.
     */
    private static boolean isBhgosMulticart(Rom rom) {
        return "MultiCart".equals(rom.getTitle())
                && rom.getType().isMbc5()
                && rom.getRom().length > 0x8000;
    }

    /** Bung/EMS flashcarts use an MBC5-like bank register plus a configurable OR mask. */
    private static boolean isBungEms(Rom rom) {
        int[] data = rom.getRom();
        if (data.length < 0x150) {
            return false;
        }

        int rawType = data[0x0147];
        // Pocket Voice uses the proposed 0xBE marker for unrelated voice hardware.
        if (rawType == 0xbe && rom.getTitle().startsWith("Pocket Voice")) {
            return false;
        }

        CRC32 crc = new CRC32();
        for (int value : data) {
            crc.update(value);
        }
        switch ((int) crc.getValue()) {
            case 0x2ed509d9: // Green Beret
            case 0xf004440c: // Cube Raider
            case 0xfdc1483a: // Bugs Bunny - Crazy Castle 3 trainer
            case 0x96c29163: // MainBlow: Bung's 32 KiB SRAM despite its MBC5+8 KiB header
                return true;
        }

        return hasNullTerminatedTitle(data, "EMSMENU")
                || hasNullTerminatedTitle(data, "GB16M")
                || rawType == 0xbe
                || (rawType == 0x1b && data[0x014a] == 0xe1);
    }

    private static boolean hasNullTerminatedTitle(int[] data, String title) {
        for (int i = 0; i < title.length(); i++) {
            if (data[0x0134 + i] != title.charAt(i)) {
                return false;
            }
        }
        return data[0x0134 + title.length()] == 0;
    }

    /**
     * Detects the Sachen MMC1/MMC2 multicarts (issues #73/#75) by their scrambled header:
     * the 0x0100-0x01FF page is wired with A0/A6 and A1/A4 swapped, so the logo bytes sit
     * at the scrambled offsets (0xED at 0x144, 0x66 at 0x114); the MMC2 stores the logo
     * served during boot in the 0x0180-0x01FF half of the page (mGBA's detection).
     * Returns 1 for MMC1, 2 for MMC2, -1 for regular carts.
     */
    /**
     * The fixed, cart-independent header the Sachen mapper descrambles for the boot ROM. The
     * raw carts store it scrambled in the 0x0100-0x01FF page (and serve it through the
     * lockout); "cooked" (post-unlock) dumps store it linearised at 0x0104, in place of the
     * Nintendo logo. It is identical across every Sachen title, so it is a reliable marker.
     */
    private static final int[] SACHEN_COOKED_HEADER = {0x11, 0x23, 0xf1, 0x1e, 0x01, 0x22, 0xf0,
            0x00, 0x08, 0x99, 0x78, 0x00, 0x08, 0x11, 0x9a, 0x48};

    /**
     * Post-unlock ("cooked") dumps of the Sachen carts (issues #73/#75). They carry no valid
     * Nintendo logo (the boot ROM's logo check is bypassed for them, as on a flashcart) and
     * hold the Sachen descrambled boot header at 0x0104. They power on at base bank 0 - the
     * menu/game code and their per-game bank switching all run from there; the earlier
     * detection booted them at the logo bank, which left the menu mapped to the wrong bank.
     */
    private static boolean isCookedSachen(Rom rom) {
        int[] data = rom.getRom();
        if (data.length < 0x8000 || hasValidLogo(rom)) {
            return false;
        }
        for (int i = 0; i < SACHEN_COOKED_HEADER.length; i++) {
            if (data[0x104 + i] != SACHEN_COOKED_HEADER[i]) {
                return false;
            }
        }
        return true;
    }

    private static int sachenType(Rom rom) {
        int[] data = rom.getRom();
        if (data.length < 0x10000) {
            return -1;
        }
        if (data[0x104] == 0xce && data[0x144] == 0xed && data[0x114] == 0x66) {
            return 1;
        }
        if (data[0x184] == 0xce && data[0x1c4] == 0xed && data[0x194] == 0x66) {
            return 2;
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
            // Existing MBC implementations expose RAM in 8 KiB banks. Plain ROM+RAM
            // is the exception: its 2 KiB header size is mirrored across A000-BFFF.
            int ramSize = rom.getType() == CartridgeType.ROM_RAM_BATTERY
                    ? rom.getRamSize() : 0x2000 * rom.getRamBanks();
            if (isBungEms(rom)) {
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
