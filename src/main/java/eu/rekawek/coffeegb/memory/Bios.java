package eu.rekawek.coffeegb.memory;

import eu.rekawek.coffeegb.AddressSpace;
import eu.rekawek.coffeegb.GameboyType;

import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;

public class Bios implements AddressSpace {

    private static final Map<GameboyType, int[]> BOOT_ROMS;

    static {
        BOOT_ROMS = new EnumMap<>(GameboyType.class);
        for (GameboyType gameboyType : GameboyType.values()) {
            try (var is = Bios.class.getResourceAsStream(String.format("/bios/%s_boot.bin", gameboyType.name().toLowerCase()))) {
                if (is == null) {
                    throw new IllegalArgumentException("No bios found for " + gameboyType);
                }
                var rom = is.readAllBytes();
                var result = new int[rom.length];
                for (int i = 0; i < rom.length; i++) {
                    result[i] = rom[i] & 0xff;
                }
                BOOT_ROMS.put(gameboyType, result);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

    }

    private final GameboyType gameboyType;

    private final int[] rom;

    public Bios(GameboyType gameboyType) {
        this.gameboyType = gameboyType;
        this.rom = BOOT_ROMS.get(gameboyType);
    }

    @Override
    public boolean accepts(int address) {
        if (address >= 0x0000 && address < 0x0100) {
            return true;
        } else if (address >= 0x200 && address < 0x0900) {
            return gameboyType == GameboyType.CGB;
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
        } else if (address >= 0x200 && address < 0x0900 && gameboyType == GameboyType.CGB) {
            return rom[address];
        } else {
            return 0xff;
        }
    }
}
