package eu.rekawek.coffeegb.core.state.bess;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Cartridge buffers and ordered mapper writes in the portable BESS representation. */
public record BessCartridgeState(byte[] ram, List<BessState.MbcWrite> registers,
                                 Map<String, byte[]> extensions) {
    public static byte[] bytes(int[] memory) {
        byte[] result = new byte[memory.length];
        for (int i = 0; i < memory.length; i++) result[i] = (byte) memory[i];
        return result;
    }

    /** BESS requires smaller buffers to be zero-extended and larger buffers to be truncated. */
    public static void restoreRam(byte[] source, int[] target) {
        Arrays.fill(target, 0);
        for (int i = 0; i < Math.min(source.length, target.length); i++) {
            target[i] = source[i] & 0xff;
        }
    }
}
