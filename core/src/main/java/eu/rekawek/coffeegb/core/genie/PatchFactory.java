package eu.rekawek.coffeegb.core.genie;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import static eu.rekawek.coffeegb.core.cpu.BitUtils.rotateByteRight;

public final class PatchFactory {

    private static final Pattern GAME_GENIE_CODE = Pattern.compile("^([0-9A-Fa-f]{3})[+-]([0-9A-Fa-f]{3})[+-]([0-9A-Fa-f]{3})$");

    private static final Pattern GAME_GENIE_SHORT_CODE = Pattern.compile("^([0-9A-Fa-f]{3})[+-]([0-9A-Fa-f]{3})$");

    private static final Pattern GAME_SHARK_CODE = Pattern.compile("^([0-9A-Fa-f]{8})$");

    private PatchFactory() {
    }

    public static List<Patch> createPatches(String code) {
        if (code.length() > 11) {
            return Arrays.stream(code.split("\\+")).map(PatchFactory::createPatch).toList();
        } else {
            return List.of(createPatch(code));
        }
    }

    static Patch createPatch(String code) {
        if (GAME_GENIE_CODE.matcher(code).matches()) {
            return parseGameGenieCode(code);
        } else if (GAME_GENIE_SHORT_CODE.matcher(code).matches()) {
            return parseGameGenieShortCode(code);
        } else if (GAME_SHARK_CODE.matcher(code).matches()) {
            return parseGameSharkCode(code);
        } else {
            throw new IllegalArgumentException("Invalid code: " + code);
        }
    }

    private static GameGeniePatch parseGameGenieCode(String code) {
        var c = code.toLowerCase().replace("-", "").toCharArray();
        var newData = parse(c, 0, 1);
        var address = parse(c, 5, 2, 3, 4) ^ 0xf000;
        var oldData = rotateByteRight(parse(c, 6, 8), 2) ^ 0xba;
        return new GameGeniePatch(newData, address, oldData);
    }

    private static GameGeniePatch parseGameGenieShortCode(String code) {
        var c = code.toLowerCase().replace("-", "").toCharArray();
        var newData = parse(c, 0, 1);
        var address = parse(c, 5, 2, 3, 4) ^ 0xf000;
        return new GameGeniePatch(newData, address, -1);
    }

    private static GameSharkPatch parseGameSharkCode(String code) {
        var c = code.toLowerCase().toCharArray();
        var mode = parse(c, 0);
        var bank = parse(c, 1);
        var newData = parse(c, 2, 3);
        var address = parse(c, 6, 7, 4, 5);
        return new GameSharkPatch(mode, bank, address, newData);
    }

    private static int parse(char[] code, int... offsets) {
        var result = new StringBuilder();
        for (int o : offsets) {
            result.append(code[o]);
        }
        return Integer.parseInt(result.toString(), 16);
    }
}
