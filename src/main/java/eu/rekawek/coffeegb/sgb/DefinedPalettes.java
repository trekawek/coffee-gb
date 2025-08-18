package eu.rekawek.coffeegb.sgb;

import eu.rekawek.coffeegb.memory.Bios;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class DefinedPalettes {

    private static final int[] DATA;

    // Source of games with pre-defined colors:
    // https://archive.org/details/Nintendo_Players_Guide_Super_Game_Boy
    //
    // Games were run on a SNES emulator and palette IDs were assigned to data in the SGB BIOS.
    //
    // Palettes for the games not present in the guide above were copied from Gambatte:
    // https://github.com/pokemon-speedrunning/gambatte-core/blob/0606d45caa0ab94c1ba34af4159d39e321a21036/libgambatte/src/initstate.cpp#L122-L247
    private static final Map<String, Integer> GAME_TO_PALETTE = new HashMap<>() {{
        put("ALLEY WAY", 0x15);
        put("BASEBALL", 0x0e);
        put("DR.MARIO", 0x11);
        put("F1RACE", 0x1d);
        put("GBWARS", 0x14);
        put("GOLF", 0x17);
        put("HOSHINOKA-BI", 0x0a);
        put("KAERUNOTAMENI", 0x08);
        put("KID ICARUS", 0x0d);
        put("KIRBY'S PINBALL", 0x02);
        put("KIRBY DREAM LAND", 0x0a);
        put("MARIOLAND2", 0x13);
        put("MARIO & YOSHI", 0x0b);
        put("METROID2", 0x1e);
        put("QIX", 0x18);
        put("SOLARSTRIKER", 0x06);
        put("SUPER MARIOLAND", 0x05);
        put("SUPERMARIOLAND3", 0x01);
        put("TENNIS", 0x16);
        put("TETRIS", 0x10);
        put("X", 0x1b);
        put("YAKUMAN", 0x12);
        put("YOSSY NO COOKIE", 0x03);
        put("YOSSY NO TAMAGO", 0x0b);
        put("YOSHI'S COOKIE", 0x03);
        put("WORLD CUP", 0x00);
        put("ZELDA", 0x04);
    }};

    static {
        try (var is = Bios.class.getResourceAsStream("/sgb-palettes.bin")) {
            if (is == null) {
                throw new IllegalArgumentException("No palette data found");
            }
            var bytes = is.readAllBytes();
            DATA = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                DATA[i] = bytes[i] & 0xff;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static int[] getPalette(int paletteId) {
        var offset = paletteId * 8;
        return new int[]{DATA[offset + 0] | DATA[offset + 1] << 8, DATA[offset + 2] | DATA[offset + 3] << 8, DATA[offset + 4] | DATA[offset + 5] << 8, DATA[offset + 6] | DATA[offset + 7] << 8};
    }

    public static int[] getPalette(String romName) {
        return getPalette(GAME_TO_PALETTE.getOrDefault(romName, 0));
    }

}
