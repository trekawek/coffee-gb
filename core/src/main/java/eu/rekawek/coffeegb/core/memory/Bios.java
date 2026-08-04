package eu.rekawek.coffeegb.core.memory;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.hardware.HardwareProfile;
import eu.rekawek.coffeegb.core.hardware.HardwareProfileRegistry;
import eu.rekawek.coffeegb.core.io.InputStreams;

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
                var rom = InputStreams.readAllBytes(is);
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
        this(profile, true);
    }

    /**
     * Constructs the mapped boot address space. A profile without a bundled boot ROM may use a
     * disabled placeholder only when the owning Gameboy is configured to skip bootstrap.
     */
    public Bios(HardwareProfile profile, boolean bootRomRequired) {
        HardwareProfile registered = HardwareProfileRegistry.requireRegistered(profile);
        String bootRomId = registered.bootSpec().bootRomId();
        this.cgbBootRom = "cgb".equals(bootRomId);
        int[] bundled = BOOT_ROMS.get(bootRomId);
        if (bundled == null && bootRomRequired) {
            throw new IllegalArgumentException(
                    "Coffee GB does not bundle or configure an authentic " + bootRomId
                            + " boot ROM for profile " + profile.id() + "; use skip bootstrap.");
        }
        this.rom = bundled == null ? new int[0x100] : bundled;
    }

    public static boolean hasBundledBootRom(HardwareProfile profile) {
        HardwareProfile registered = HardwareProfileRegistry.requireRegistered(profile);
        return BOOT_ROMS.containsKey(registered.bootSpec().bootRomId());
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
