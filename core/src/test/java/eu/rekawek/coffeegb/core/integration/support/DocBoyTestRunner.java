package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.IOException;

/** Runs one DocBoy test ROM and reads its documented HRAM verdict. */
public final class DocBoyTestRunner {

    private static final int RESULT_ADDRESS = 0xfff0;

    private final Gameboy gameboy;

    public DocBoyTestRunner(byte[] rom, GameboyType gameboyType,
                            Gameboy.BootstrapMode bootstrapMode) throws IOException {
        gameboy = new Gameboy.GameboyConfiguration(new Rom(rom))
                .setGameboyType(gameboyType)
                .setBootstrapMode(bootstrapMode)
                .setSupportBatterySave(false)
                .build();
        gameboy.init(new EventBusImpl(null, null, false), SerialEndpoint.NULL_ENDPOINT, null);
    }

    public TestResult runTest(long maxTicks) {
        try {
            int initialStatus = gameboy.getAddressSpace().getByte(RESULT_ADDRESS);
            if (isTerminal(initialStatus)) {
                throw new IllegalStateException(String.format(
                        "DocBoy test starts with terminal status %02x", initialStatus));
            }
            for (long ticks = 1; ticks <= maxTicks; ticks++) {
                gameboy.tick();
                int status = gameboy.getAddressSpace().getByte(RESULT_ADDRESS);
                if (isTerminal(status)) {
                    return new TestResult(status, ticks);
                }
            }
            return new TestResult(gameboy.getAddressSpace().getByte(RESULT_ADDRESS), maxTicks);
        } finally {
            gameboy.close();
        }
    }

    private static boolean isTerminal(int status) {
        return status == 0x01 || status == 0x02;
    }

    public record TestResult(int status, long ticks) {

        @Override
        public String toString() {
            return String.format("status=%02x after %,d ticks", status, ticks);
        }
    }

}
