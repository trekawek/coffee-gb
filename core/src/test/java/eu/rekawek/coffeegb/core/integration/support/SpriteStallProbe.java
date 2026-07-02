package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.gpu.Mode;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;

/**
 * Measures the emergent mode-3 stall for sprite configurations: places sprites on a line,
 * and reports the tick at which the visible mode changes to 0 (minus the 252 baseline).
 * Usage: SpriteStallProbe <anyRom>
 */
public class SpriteStallProbe {

    public static void main(String[] args) throws Exception {
        int[][] configs = new int[40][];
        int n = 0;
        int[] xs;
        // k sprites at x=0
        for (int k = 1; k <= 10; k++) {
            xs = new int[k];
            configs[n++] = xs;
        }
        // 1 sprite at x
        for (int x : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 11, 12}) {
            configs[n++] = new int[]{x};
        }
        // 2 sprites 8 apart
        for (int x0 : new int[]{0, 1, 2, 4}) {
            configs[n++] = new int[]{x0, x0 + 8};
        }

        for (int c = 0; c < n; c++) {
            System.out.printf("cfg %s -> stall %d%n", java.util.Arrays.toString(configs[c]),
                    measure(args[0], configs[c]));
        }
        System.exit(0);
    }

    private static int measure(String rom, int[] xs) throws Exception {
        Gameboy gb = new GameboyConfiguration(new File(rom))
                .setSupportBatterySave(false).setGameboyType(GameboyType.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).build();
        gb.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
        // park the CPU in a WRAM spin loop
        gb.getAddressSpace().setByte(0xc000, 0x18);
        gb.getAddressSpace().setByte(0xc001, 0xfe);
        gb.getCpu().getRegisters().setPC(0xc000);
        // LCD off
        gb.getAddressSpace().setByte(0xff40, 0x00);
        for (int i = 0; i < 16; i++) gb.tick();
        // clear OAM, then sprites on line 0x42 (y = 0x42 + 16)
        for (int i = 0; i < 0xa0; i++) gb.getAddressSpace().setByte(0xfe00 + i, 0);
        for (int i = 0; i < xs.length; i++) {
            gb.getAddressSpace().setByte(0xfe00 + i * 4, 0x42 + 16);
            gb.getAddressSpace().setByte(0xfe00 + i * 4 + 1, xs[i] + 8);
        }
        // unused sprites off-screen
        for (int i = xs.length; i < 40; i++) {
            gb.getAddressSpace().setByte(0xfe00 + i * 4, 0);
        }
        // LCD on with objects
        gb.getAddressSpace().setByte(0xff40, 0x83);
        // run to line 0x42 of the second frame and find the HBlank transition
        long limit = 70224L * 3;
        Mode prev = null;
        for (long t = 0; t < limit; t++) {
            gb.tick();
            Mode m = gb.getGpu().getMode();
            if (gb.getGpu().getLine() == 0x42 && prev == Mode.PixelTransfer && m == Mode.HBlank && t > 70224) {
                return gb.getGpu().getTicksInLine() - 252;
            }
            prev = m;
        }
        return -999;
    }
}
