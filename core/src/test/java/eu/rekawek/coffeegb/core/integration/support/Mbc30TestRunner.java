package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBus;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.File;
import java.io.IOException;

/** Reads MBC30Test's ROM and SRAM verdicts from its tilemap. */
public class Mbc30TestRunner {

    private static final int TILEMAP = 0x9800;

    private static final long MAX_TICKS = 80_000_000L;

    private static final String[] ROM_RESULTS = {
            "MBC30 ROM OK!", "MBC3 ROM OK!", "UNKNOWN ROM FAIL"
    };

    private static final String[] SRAM_RESULTS = {
            "MBC30 SRAM OK!", "MBC3 SRAM OK!", "UNKNOWN SRAM FAIL"
    };

    private final Gameboy gb;

    private final AddressSpace memory;

    public Mbc30TestRunner(File romFile) throws IOException {
        EventBus eventBus = new EventBusImpl();
        gb = new Gameboy.GameboyConfiguration(romFile)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(GameboyType.CGB)
                .setSupportBatterySave(false)
                .build();
        gb.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        memory = gb.getAddressSpace();
    }

    public TestResult runTest() {
        String romResult = null;
        for (long ticks = 0; ticks < MAX_TICKS; ticks++) {
            if ((ticks & 3) == 0) {
                String currentRomResult = findResult(ROM_RESULTS);
                if (currentRomResult != null) {
                    romResult = currentRomResult;
                }

                String sramResult = findResult(SRAM_RESULTS);
                if (sramResult != null) {
                    boolean passed = "MBC30 ROM OK!".equals(romResult)
                            && "MBC30 SRAM OK!".equals(sramResult);
                    return new TestResult(passed, false, romResult, sramResult, dumpScreen());
                }
            }
            gb.tick();
        }
        return new TestResult(false, true, romResult, null, dumpScreen());
    }

    private String findResult(String[] results) {
        for (String result : results) {
            if (matches(result)) {
                return result;
            }
        }
        return null;
    }

    private boolean matches(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (memory.getByte(TILEMAP + i) != text.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private String dumpScreen() {
        StringBuilder output = new StringBuilder();
        for (int y = 0; y < 18; y++) {
            StringBuilder line = new StringBuilder();
            for (int x = 0; x < 20; x++) {
                int tile = memory.getByte(TILEMAP + y * 32 + x);
                if (tile == 0) {
                    line.append('.');
                } else if (tile == 1) {
                    line.append('X');
                } else if (tile >= 0x20 && tile <= 0x7e) {
                    line.append((char) tile);
                } else {
                    line.append(' ');
                }
            }
            while (line.length() > 0 && line.charAt(line.length() - 1) == ' ') {
                line.setLength(line.length() - 1);
            }
            output.append(line).append('\n');
        }
        return output.toString().trim();
    }

    public static class TestResult {

        private final boolean passed;

        private final boolean timedOut;

        private final String romResult;

        private final String sramResult;

        private final String screen;

        public TestResult(boolean passed, boolean timedOut, String romResult, String sramResult, String screen) {
            this.passed = passed;
            this.timedOut = timedOut;
            this.romResult = romResult;
            this.sramResult = sramResult;
            this.screen = screen;
        }

        public boolean isPassed() {
            return passed;
        }

        public boolean isTimedOut() {
            return timedOut;
        }

        public String getDiagnostic() {
            return "MBC30Test ROM result: " + romResult
                    + "\nMBC30Test SRAM result: " + sramResult
                    + "\n\n" + screen;
        }
    }
}
