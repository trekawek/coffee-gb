package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.gpu.Gpu;
import eu.rekawek.coffeegb.core.gpu.Mode;

import java.io.File;
import java.io.PrintWriter;

/**
 * Debugging harness: runs a ROM and logs the internal PPU mode transitions with
 * ticksInLine/LY. Usage: PpuProbe <rom> <output.log> [maxTicks]
 */
public class PpuProbe {

    public static void main(String[] args) throws Exception {
        File romFile = new File(args[0]);
        PrintWriter out = new PrintWriter(args[1]);
        long maxTicks = args.length > 2 ? Long.parseLong(args[2]) : 1_000_000L;

        GameboyConfiguration config = new GameboyConfiguration(romFile)
                .setSupportBatterySave(false)
                .setGameboyType(GameboyType.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP);
        Gameboy gb = config.build();
        gb.init(new eu.rekawek.coffeegb.core.events.EventBusImpl(),
                eu.rekawek.coffeegb.core.serial.SerialEndpoint.NULL_ENDPOINT, null);
        Gpu gpu = gb.getGpu();

        Mode prevMode = null;
        boolean prevEnabled = true;
        int prevLy = -1;

        for (long t = 0; t < maxTicks; t++) {
            gb.tick();
            Mode mode = gpu.getMode();
            boolean enabled = gpu.isLcdEnabled();
            int ly = gpu.getByte(0xff44);
            if (mode != prevMode || enabled != prevEnabled || ly != prevLy) {
                out.printf("%d en=%b mode=%s ly=%d til=%d%n", t, enabled, mode, ly, gpu.getTicksInLine());
                prevMode = mode;
                prevEnabled = enabled;
                prevLy = ly;
            }
        }
        out.close();
        System.exit(0);
    }
}
