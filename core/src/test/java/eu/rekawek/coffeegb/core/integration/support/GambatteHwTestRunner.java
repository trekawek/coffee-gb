package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.IOException;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.isByteSequenceAtPc;

/** Runs Gambatte HWTests that print their result as hexadecimal tiles at 0x9800. */
public final class GambatteHwTestRunner {

    private static final long MAX_TICKS = 5_000_000L;

    private final Gameboy gameboy;

    public GambatteHwTestRunner(byte[] rom, GameboyType gameboyType) throws IOException {
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(new Rom(rom))
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(gameboyType)
                .setSupportBatterySave(false);
        gameboy = configuration.build();
        gameboy.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
    }

    public String runTest(int outputDigits) {
        long ticks = 0;
        // Every hexadecimal-result ROM ends in `jr lprint_limbo` (JR -2) after it has
        // copied the digit tiles and enabled the LCD.
        while (!isByteSequenceAtPc(gameboy, 0x18, 0xfe)) {
            if (++ticks > MAX_TICKS) {
                throw new AssertionError("Gambatte HWTest did not reach its result loop");
            }
            gameboy.tick();
        }

        StringBuilder output = new StringBuilder(outputDigits);
        for (int i = 0; i < outputDigits; i++) {
            int digit = gameboy.getAddressSpace().getByte(0x9800 + i) & 0x0f;
            output.append(Character.toUpperCase(Character.forDigit(digit, 16)));
        }
        return output.toString();
    }
}
