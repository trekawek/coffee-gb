package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.IOException;

/** Runs one GBMicrotest ROM and reads its documented HRAM result protocol. */
public final class GbMicrotestRunner {

    private final Gameboy gameboy;

    public GbMicrotestRunner(byte[] rom) throws IOException {
        gameboy = new Gameboy.GameboyConfiguration(new Rom(rom))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.DMG)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
    }

    public TestResult runTest(long ticks) {
        for (long i = 0; i < ticks; i++) {
            gameboy.tick();
        }
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
}
