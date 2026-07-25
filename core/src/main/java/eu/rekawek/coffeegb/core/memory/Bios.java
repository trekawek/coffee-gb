package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class Bios implements AddressSpace {

    private static final Map<String, int[]> BOOT_ROMS;

    static {
        BOOT_ROMS = new HashMap<>();
        for (String bootRomId : new String[]{"dmg", "cgb", "sgb"}) {
            try (var is = Bios.class.getResourceAsStream(String.format("/bios/%s_boot.bin", bootRomId))) {
                if (is == null) {
                    throw new IllegalArgumentException("No bios found for " + bootRomId);
                }
                var rom = is.readAllBytes();
                var result = new int[rom.length];
                for (int i = 0; i < rom.length; i++) {
                    result[i] = rom[i] & 0xff;
                }
                BOOT_ROMS.put(bootRomId, result);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private final boolean cgbBootRom;

    private final int[] rom;

    public Bios(HardwareProfile profile) {
        HardwareProfile registered = HardwareProfileRegistry.requireRegistered(profile);
        String bootRomId = registered.bootSpec().bootRomId();
        this.cgbBootRom = "cgb".equals(bootRomId);
        this.rom = BOOT_ROMS.get(bootRomId);
        if (rom == null) {
            throw new IllegalArgumentException("No boot ROM registered for profile " + profile.id());
        }
    }

    /** @deprecated Construct from a resolved HardwareProfile. */
    @Deprecated
    public Bios(GameboyType gameboyType) {
        this(HardwareProfileRegistry.fromGameboyType(gameboyType));
    }

    @Override
    public boolean accepts(int address) {
        if (address >= 0x0000 && address < 0x0100) {
            return true;
        } else if (address >= 0x200 && address < 0x0900) {
            return cgbBootRom;
        }
        return false;
    }

    @Override
    public void setByte(int address, int value) {
    }

    @Override
    public int getByte(int address) {
        if (address >= 0x000 && address < 0x0100) {
            return rom[address];
        } else if (address >= 0x200 && address < 0x0900 && cgbBootRom) {
            return rom[address];
        } else {
            return 0xff;
        }
    }
}
