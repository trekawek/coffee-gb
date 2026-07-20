package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static eu.rekawek.coffeegb.core.integration.support.RomTestUtils.isByteSequenceAtPc;

/** Runs Gambatte HWTests that print their result as hexadecimal tiles at 0x9800. */
public final class GambatteHwTestRunner {

    private static final long MAX_TICKS = 5_000_000L;

    // Signature of the generated hexadecimal renderer's zero tile. Checking the
    // renderer data as well as the result map distinguishes the terminal print loop
    // from intentional JR -2 loops used while a timing test waits for an interrupt.
    private static final int[][] HEX_RENDERER_SIGNATURE = {
            {0x8002, 0x7f}, {0x8003, 0x7f},
            {0x8004, 0x41}, {0x8005, 0x41},
            {0x800e, 0x7f}, {0x800f, 0x7f}
    };

    /**
     * Booting an authentic BIOS is part of these tests, but the generated ROMs share
     * a small set of boot-visible headers. Cache an immutable post-boot memento for
     * each header/model pair and restore it into the ROM-specific machine. This keeps
     * the exact BIOS-produced CPU/PPU/peripheral phase without replaying 13-24 million
     * boot ticks for thousands of parameterized cases.
     */
    private static final Map<BootStateKey, Memento<Gameboy>> POST_BOOT_STATES =
            new HashMap<>();

    private final Gameboy gameboy;

    public GambatteHwTestRunner(byte[] rom, GameboyType gameboyType) throws IOException {
        Rom parsedRom = new Rom(rom);
        Gameboy.GameboyConfiguration configuration = new Gameboy.GameboyConfiguration(parsedRom)
                .setGameboyType(gameboyType)
                .setSupportBatterySave(false);
        gameboy = buildAtPostBoot(configuration, bootHeader(parsedRom));
        // The runner is fully synchronous. The default EventBusImpl starts a polling
        // thread; using one per parameterized ROM would retain every completed emulator
        // until JVM exit and make an exhaustive run grow without bound.
        gameboy.init(new EventBusImpl(null, null, false), SerialEndpoint.NULL_ENDPOINT, null);
    }

    private static Gameboy buildAtPostBoot(
            Gameboy.GameboyConfiguration configuration, String bootHeader) {
        BootStateKey key = new BootStateKey(configuration.getGameboyType(), bootHeader);
        Memento<Gameboy> postBoot;
        synchronized (POST_BOOT_STATES) {
            postBoot = POST_BOOT_STATES.get(key);
            if (postBoot == null) {
                Gameboy booted = configuration
                        .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                        .build();
                POST_BOOT_STATES.put(key, booted.saveToMemento());
                return booted;
            }
        }

        // Construction and restore are parameter-local and relatively expensive.
        // Keep them outside the cache lock so independent ROMs can start in parallel.
        Gameboy restored = configuration.forRestore().build();
        restored.restoreFromMemento(postBoot);
        return restored;
    }

    private static String bootHeader(Rom rom) {
        int[] bytes = rom.getRom();
        StringBuilder key = new StringBuilder(0x1a);
        // The boot ROM verifies/uses 0134-014D. The global checksum at 014E-014F
        // varies with test code but is not observed during boot.
        for (int address = 0x0134; address <= 0x014d; address++) {
            key.append((char) bytes[address]);
        }
        return key.toString();
    }

    public String runTest(int outputDigits) {
        try {
            long ticks = 0;
            // Ordinary hexadecimal-result ROMs end in `jr lprint_limbo` (JR -2) after
            // copying the digit tiles and enabling the LCD. The undefined-opcode tests
            // deliberately freeze the CPU after drawing their result, so LOCKED is the
            // other hardware-terminal state.
            while (!isTerminalState(outputDigits)) {
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
        } finally {
            gameboy.close();
        }
    }

    private boolean isTerminalState(int outputDigits) {
        boolean terminalCpuState = gameboy.getCpu().getState() == Cpu.State.LOCKED
                || isByteSequenceAtPc(gameboy, 0x18, 0xfe);
        return isTerminalState(terminalCpuState, gameboy.getGpu().getVideoRam(), outputDigits);
    }

    static boolean isTerminalState(boolean terminalCpuState, AddressSpace videoRam,
                                   int outputDigits) {
        if (!terminalCpuState) {
            return false;
        }
        for (int[] signatureByte : HEX_RENDERER_SIGNATURE) {
            if (videoRam.getByte(signatureByte[0]) != signatureByte[1]) {
                return false;
            }
        }
        for (int i = 0; i < outputDigits; i++) {
            if ((videoRam.getByte(0x9800 + i) & 0xff) > 0x0f) {
                return false;
            }
        }
        return true;
    }

    private record BootStateKey(GameboyType gameboyType, String bootHeader) {
    }
}
