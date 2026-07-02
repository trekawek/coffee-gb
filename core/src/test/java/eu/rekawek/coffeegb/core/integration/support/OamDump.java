package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;

public class OamDump {
    public static void main(String[] args) throws Exception {
        Gameboy gb = new GameboyConfiguration(new File(args[0]))
                .setSupportBatterySave(false).setGameboyType(GameboyType.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP).build();
        gb.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
        long until = Long.parseLong(args[1]);
        for (long t = 0; t < until; t++) {
            gb.tick();
        }
        // read OAM directly via the gpu-independent path: temporarily use the address space
        // during a safe moment is unreliable; instead run until vblank then dump
        while (gb.getAddressSpace().getByte(0xff44) != 145) {
            gb.tick();
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 0xa0; i++) {
            if (i % 16 == 0) sb.append(String.format("%n%02x: ", i));
            sb.append(String.format("%02x ", gb.getAddressSpace().getByte(0xfe00 + i)));
        }
        System.out.println(sb);
        System.exit(0);
    }
}
