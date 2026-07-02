package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;

public class BootProbe {
    public static void main(String[] args) throws Exception {
        Gameboy gb = new GameboyConfiguration(new File(args[0]))
                .setSupportBatterySave(false).setGameboyType(GameboyType.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.NORMAL).build();
        gb.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
        long t = 0;
        while (gb.getCpu().getRegisters().getPC() != 0x100 && t < 40_000_000) {
            gb.tick();
            t++;
            if (t % 5_000_000 == 0) {
                System.out.printf("t=%d pc=%04x ly=%02x line=%d til=%d lcdEn=%b lcdc=%02x%n", t,
                        gb.getCpu().getRegisters().getPC(), gb.getAddressSpace().getByte(0xff44),
                        gb.getGpu().getLine(), gb.getGpu().getTicksInLine(), gb.getGpu().isLcdEnabled(),
                        gb.getAddressSpace().getByte(0xff40));
            }
        }
        System.out.printf("ticks=%d (0x%x) div=%02x pc=%04x%n", t, t,
                gb.getAddressSpace().getByte(0xff04), gb.getCpu().getRegisters().getPC());
        System.exit(0);
    }
}
