package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;
import java.io.PrintWriter;

public class OamProbe {
    public static void main(String[] args) throws Exception {
        Gameboy gb = new GameboyConfiguration(new File(args[0]))
                .setSupportBatterySave(false).setGameboyType(GameboyType.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).build();
        gb.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
        PrintWriter out = new PrintWriter(args[1]);
        long from = Long.parseLong(args[2]), to = Long.parseLong(args[3]);
        int prevStat = -1, prevOam = -1;
        for (long t = 0; t < to; t++) {
            gb.tick();
            if (t < from) continue;
            int stat = gb.getAddressSpace().getByte(0xff41);
            int oam = gb.getAddressSpace().getByte(0xfe00);
            if (stat != prevStat || oam != prevOam) {
                out.printf("%d stat=%02x oam=%02x ly=%d til=%d line=%d%n", t, stat, oam,
                        gb.getAddressSpace().getByte(0xff44), gb.getGpu().getTicksInLine(), gb.getGpu().getLine());
                prevStat = stat;
                prevOam = oam;
            }
        }
        out.close();
    }
}
