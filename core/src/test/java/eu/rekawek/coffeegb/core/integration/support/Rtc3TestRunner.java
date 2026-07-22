package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;
import java.io.IOException;
import java.util.Collections;

/** Automates one of RTC3Test's three interactive menus and reads its color-coded results. */
public class Rtc3TestRunner {

    private static final int TILEMAP = 0x9c00;

    private static final int SCREEN_WIDTH = 20;

    private static final int SCREEN_HEIGHT = 18;

    private static final long MAX_TEST_TICKS = 120L * Gameboy.TICKS_PER_SEC;

    private static final int[] RETURN_LABEL = {0xc1, 0x3f, 0x1b, 0x28, 0x37, 0x38, 0x35, 0x31};

    private final Gameboy gb;

    private final AddressSpace memory;

    public Rtc3TestRunner(File romFile) throws IOException {
        EventBus eventBus = new EventBusImpl();
        gb = new Gameboy.GameboyConfiguration(romFile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.DMG)
                .setSupportBatterySave(false)
                .build();
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        memory = gb.getAddressSpace();
    }

    public TestResult runTest(int menuIndex) {
        if (menuIndex < 0 || menuIndex > 2) {
            throw new IllegalArgumentException("RTC3Test menu index must be between 0 and 2");
        }

        runTicks(4L * Gameboy.TICKS_PER_FRAME);
        for (int i = 0; i < menuIndex; i++) {
            click(Button.DOWN);
        }
        click(Button.A);

        long ticks = 0;
        while (!hasReturnLabel()) {
            if (++ticks > MAX_TEST_TICKS) {
                return new TestResult(false, "RTC3Test timed out\n" + dumpScreen());
            }
            tick();
        }

        return new TestResult(!hasFailedResult(), dumpScreen());
    }

    private void click(Button button) {
        // Let WaitForButtonPress reach its released-button polling loop before pressing.
        runTicks(2L * Gameboy.TICKS_PER_FRAME);
        gb.setPressedButtons(Collections.singleton(button));
        runTicks(2L * Gameboy.TICKS_PER_FRAME);
        gb.setPressedButtons(Collections.emptySet());
        runTicks(2L * Gameboy.TICKS_PER_FRAME);
    }

    private void runTicks(long ticks) {
        while (ticks-- > 0) {
            tick();
        }
    }

    private void tick() {
        gb.tick();
    }

    private boolean hasReturnLabel() {
        int address = TILEMAP + 17 * 32 + 6;
        for (int tile : RETURN_LABEL) {
            if (memory.getByte(address++) != tile) {
                return false;
            }
        }
        return true;
    }

    private boolean hasFailedResult() {
        for (int y = 2; y < 17; y++) {
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                int tile = memory.getByte(TILEMAP + y * 32 + x);
                if (tile >= 0x80 && tile < 0xc0) {
                    return true;
                }
            }
        }
        return false;
    }

    private String dumpScreen() {
        StringBuilder result = new StringBuilder();
        for (int y = 0; y < SCREEN_HEIGHT; y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < SCREEN_WIDTH; x++) {
                line.append(decodeTile(memory.getByte(TILEMAP + y * 32 + x)));
            }
            result.append(stripTrailingSpaces(line)).append('\n');
        }
        return result.toString().trim();
    }

    private static char decodeTile(int tile) {
        switch (tile) {
            case 0xc0:
                return '>';
            case 0xc1:
                return '*';
            case 0xc2:
                return ':';
            case 0xc3:
                return '/';
            case 0xc4:
                return '-';
            case 0xff:
                return ' ';
            default:
                int character = tile & 0x3f;
                if (character <= 9) {
                    return (char) ('0' + character);
                } else if (character <= 0x23) {
                    return (char) ('A' + character - 10);
                } else if (character <= 0x3d) {
                    return (char) ('a' + character - 0x24);
                } else if (character == 0x3e) {
                    return '.';
                } else {
                    return ' ';
                }
        }
    }

    private static String stripTrailingSpaces(StringBuilder line) {
        while (line.length() > 0 && line.charAt(line.length() - 1) == ' ') {
            line.setLength(line.length() - 1);
        }
        return line.toString();
    }

    public static class TestResult {

        private final boolean passed;

        private final String output;

        public TestResult(boolean passed, String output) {
            this.passed = passed;
            this.output = output;
        }

        public boolean isPassed() {
            return passed;
        }

        public String getOutput() {
            return output;
        }
    }

}
