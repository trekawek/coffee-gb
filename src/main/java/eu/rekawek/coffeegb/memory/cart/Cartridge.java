package eu.rekawek.coffeegb.memory.cart;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.memento.Memento;
import eu.rekawek.coffeegb.memento.Originator;
import eu.rekawek.coffeegb.memory.cart.battery.Battery;
import eu.rekawek.coffeegb.memory.cart.battery.FileBattery;
import eu.rekawek.coffeegb.memory.cart.type.*;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;

public class Cartridge implements AddressSpace, Serializable, Originator<Cartridge> {

    private final MemoryController addressSpace;

    private final Battery battery;

    public Cartridge(Rom rom, boolean supportBatterySaves) {
        this(rom, supportBatterySaves && rom.getFile() != null ? createBattery(rom) : Battery.NULL_BATTERY);
    }

    public Cartridge(Rom rom, Battery battery) {
        this.battery = battery;

        var type = rom.getType();
        if (type.isMbc1()) {
            addressSpace = new Mbc1(rom, battery);
        } else if (type.isMbc2()) {
            addressSpace = new Mbc2(rom, battery);
        } else if (type.isMbc3()) {
            addressSpace = new Mbc3(rom, battery);
        } else if (type.isMbc5()) {
            addressSpace = new Mbc5(rom, battery);
        } else {
            addressSpace = new BasicRom(rom);
        }
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

    private static Battery createBattery(Rom rom) {
        if (rom.getType().isBattery()) {
            int ramBanks = rom.getRamBanks();
            if (ramBanks == 0 && rom.getType().isRam()) {
                ramBanks = 1;
            }
            return new FileBattery(getSaveName(rom.getFile()), 0x2000 * ramBanks);
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
