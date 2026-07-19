package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/** Runs one GBMicrotest ROM and reads its documented HRAM result protocol. */
public final class GbMicrotestRunner {

    private static final Map<BootStateKey, Memento<Gameboy>> DMG_BOOT_MEMENTOS = new HashMap<>();

    private final Gameboy gameboy;

    public GbMicrotestRunner(byte[] rom) throws IOException {
        Gameboy.GameboyConfiguration configuration = configuration(rom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP);
        gameboy = configuration.build();
        gameboy.init(new EventBusImpl(null, null, false), SerialEndpoint.NULL_ENDPOINT, null);
        gameboy.restoreFromMemento(getDmgBootMemento(rom));
    }

    public GbMicrotestRunner(byte[] rom, Gameboy.BootstrapMode bootstrapMode) throws IOException {
        gameboy = configuration(rom)
                .setBootstrapMode(bootstrapMode)
                .build();
        gameboy.init(new EventBusImpl(null, null, false), SerialEndpoint.NULL_ENDPOINT, null);
    }

    private static Gameboy.GameboyConfiguration configuration(byte[] rom) throws IOException {
        return new Gameboy.GameboyConfiguration(new Rom(rom))
                .setGameboyType(GameboyType.DMG)
                .setSupportBatterySave(false);
    }

    private static synchronized Memento<Gameboy> getDmgBootMemento(byte[] rom) throws IOException {
        BootStateKey key = new BootStateKey(rom.length, headerByte(rom, 0x143),
                headerByte(rom, 0x147), headerByte(rom, 0x148), headerByte(rom, 0x149));
        Memento<Gameboy> memento = DMG_BOOT_MEMENTOS.get(key);
        if (memento == null) {
            Gameboy booted = configuration(rom)
                    .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                    .build();
            booted.init(new EventBusImpl(null, null, false), SerialEndpoint.NULL_ENDPOINT, null);
            memento = booted.saveToMemento();
            DMG_BOOT_MEMENTOS.put(key, memento);
            booted.close();
        }
        return memento;
    }

    private static int headerByte(byte[] rom, int address) {
        return address < rom.length ? rom[address] & 0xff : 0;
    }

    public TestResult runTest(long ticks) {
        try {
            int initialStatus = gameboy.getAddressSpace().getByte(0xff82);
            if (initialStatus == 0x01 || initialStatus == 0xff) {
                throw new IllegalStateException(String.format(
                        "GBMicrotest starts with terminal status %02x", initialStatus));
            }
            for (long i = 0; i < ticks; i++) {
                gameboy.tick();
                int status = gameboy.getAddressSpace().getByte(0xff82);
                if (status == 0x01 || status == 0xff) {
                    return readResult();
                }
            }
            return readResult();
        } finally {
            gameboy.close();
        }
    }

    private TestResult readResult() {
        return new TestResult(
                gameboy.getAddressSpace().getByte(0xff80),
                gameboy.getAddressSpace().getByte(0xff81),
                gameboy.getAddressSpace().getByte(0xff82));
    }

    public record TestResult(int actual, int expected, int status) {

        @Override
        public String toString() {
            return String.format("status=%02x, actual=%02x, expected=%02x", status, actual, expected);
        }
    }

    private record BootStateKey(int romLength, int cgbFlag, int cartridgeType,
                                int romSize, int ramSize) {
    }
}
