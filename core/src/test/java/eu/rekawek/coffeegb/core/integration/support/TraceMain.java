package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.Gameboy.GameboyConfiguration;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;
import java.io.PrintWriter;

/**
 * Debugging harness: runs a ROM and logs CPU state transitions, IF changes and DIV values
 * per T-cycle. Usage: TraceMain <rom> <output.log> [maxTicks]
 */
public class TraceMain {

    public static void main(String[] args) throws Exception {
        File romFile = new File(args[0]);
        PrintWriter out = new PrintWriter(args[1]);
        long maxTicks = args.length > 2 ? Long.parseLong(args[2]) : 10_000_000L;

        GameboyConfiguration config = new GameboyConfiguration(romFile)
                .setSupportBatterySave(false)
                .setGameboyType(GameboyType.DMG)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP);
        Gameboy gb = config.build();
        gb.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
        Cpu cpu = gb.getCpu();
        AddressSpace mem = gb.getAddressSpace();

        int prevIf = -1, prevPc = -1, prevDiv = -1;
        Cpu.State prevState = null;

        for (long t = 0; t < maxTicks; t++) {
            gb.tick();
            int pc = cpu.getRegisters().getPC();
            Cpu.State state = cpu.getState();
            int iff = mem.getByte(0xff0f);
            int div = mem.getByte(0xff04);
            if (pc != prevPc || state != prevState || iff != prevIf || div != prevDiv) {
                out.printf("%d %04x %s IF=%02x DIV=%02x%n", t, pc, state, iff, div);
                prevPc = pc;
                prevState = state;
                prevIf = iff;
                prevDiv = div;
            }
            if (RomTestUtils.isByteSequenceAtPc(gb, 0x40)) {
                break;
            }
        }
        out.close();
    }
}
