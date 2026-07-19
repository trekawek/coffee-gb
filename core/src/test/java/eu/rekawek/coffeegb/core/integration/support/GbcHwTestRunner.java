package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.cpu.Cpu;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.joypad.Button;
import eu.rekawek.coffeegb.core.joypad.ButtonPressEvent;
import eu.rekawek.coffeegb.core.joypad.ButtonReleaseEvent;
import eu.rekawek.coffeegb.core.memento.Memento;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Runs an AntonioND gbc-hw-tests ROM and captures its SRAM result block. */
public final class GbcHwTestRunner {

    public static final byte[] RESULT_MAGIC = {0x12, 0x34, 0x56, 0x78};

    private static final long FRAME_TICKS = 154L * 456;

    private static final int HDMA_HALT_CHECKPOINT_BYTES = 14;

    private static final Map<BootKey, Memento<Gameboy>> BOOT_MEMENTOS = new HashMap<>();

    private final Gameboy gameboy;

    private final AddressSpace memory;

    private final EventBusImpl eventBus;

    private final StopWake stopWake;

    private boolean stopWakeSent;

    private boolean stopWakeReleased;

    private long registerCheckpointStart = -1;

    private byte[] registerCheckpointSram;

    public GbcHwTestRunner(byte[] rom, GameboyType gameboyType) throws IOException {
        this(rom, gameboyType, List.of(), null);
    }

    public GbcHwTestRunner(byte[] rom, GameboyType gameboyType,
                           Collection<Button> initialButtons) throws IOException {
        this(rom, gameboyType, initialButtons, null);
    }

    public GbcHwTestRunner(byte[] rom, GameboyType gameboyType,
                           Collection<Button> initialButtons, StopWake stopWake) throws IOException {
        Rom parsedRom = new Rom(rom);
        gameboy = configuration(parsedRom, gameboyType)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .build();
        eventBus = new EventBusImpl(null, null, false);
        gameboy.init(eventBus, SerialEndpoint.NULL_ENDPOINT, null);
        gameboy.restoreFromMemento(getBootMemento(rom, gameboyType));
        for (Button button : initialButtons) {
            // Use the normal input event path so the joypad's electrical input/filter
            // state is initialized exactly as it is for a real held controller button.
            eventBus.post(new ButtonPressEvent(button));
        }
        memory = gameboy.getAddressSpace();
        this.stopWake = stopWake;
    }

    private static Gameboy.GameboyConfiguration configuration(Rom rom, GameboyType gameboyType) {
        return new Gameboy.GameboyConfiguration(rom)
                .setGameboyType(gameboyType)
                .setBatteryData(new byte[Math.max(rom.getRamSize(), 0x2000)]);
    }

    private static synchronized Memento<Gameboy> getBootMemento(byte[] rom, GameboyType gameboyType)
            throws IOException {
        BootKey key = bootKey(rom, gameboyType);
        Memento<Gameboy> memento = BOOT_MEMENTOS.get(key);
        if (memento == null) {
            Gameboy booted = configuration(new Rom(rom), gameboyType)
                    .setBootstrapMode(Gameboy.BootstrapMode.FAST_FORWARD)
                    .build();
            booted.init(new EventBusImpl(null, null, false), SerialEndpoint.NULL_ENDPOINT, null);
            memento = booted.saveToMemento();
            BOOT_MEMENTOS.put(key, memento);
            booted.close();
        }
        return memento;
    }

    private static BootKey bootKey(byte[] rom, GameboyType gameboyType) throws IOException {
        if (rom.length <= 0x14d) {
            throw new IOException("ROM is too short to contain a complete cartridge header");
        }
        int cgbFlag = rom[0x143] & 0xff;
        // The CGB compatibility boot ROM selects palettes from the title/license and
        // header checksum. Cache those boot states only across byte-identical observable
        // headers; otherwise parallel test order could choose another ROM's palette and
        // post-boot PPU state nondeterministically.
        String compatibilityHeader = gameboyType == GameboyType.CGB && (cgbFlag & 0x80) == 0
                ? new String(rom, 0x134, 0x1a, StandardCharsets.ISO_8859_1) : "";
        return new BootKey(gameboyType, rom.length, cgbFlag,
                rom[0x147] & 0xff, rom[0x149] & 0xff, compatibilityHeader);
    }

    public TestResult runTest(int expectedLength, long maxTicks) {
        return runTest(expectedLength, maxTicks, CompletionMode.RESULT_MAGIC, -1);
    }

    public TestResult runTest(int expectedLength, long maxTicks, CompletionMode completionMode,
                              int completionPc) {
        if (completionMode == CompletionMode.NEXT_STOP_AFTER_WAKE
                && (stopWake == null || completionPc < 0)) {
            throw new IllegalStateException(
                    "NEXT_STOP_AFTER_WAKE requires a scheduled wake and completion PC");
        }
        try {
            int magicOffset = expectedLength - RESULT_MAGIC.length;
            for (long tick = 0; tick < maxTicks; tick++) {
                applyScheduledInput();
                gameboy.tick();
                if ((tick & 3) == 3
                        && isComplete(completionMode, magicOffset, completionPc, tick)) {
                    return new TestResult(true, readResult(expectedLength), dumpCpu());
                }
            }
            return new TestResult(false, readResult(expectedLength), dumpCpu());
        } finally {
            gameboy.close();
        }
    }

    private void applyScheduledInput() {
        if (stopWakeSent && !stopWakeReleased
                && gameboy.getCpu().getState() != Cpu.State.STOPPED) {
            eventBus.post(new ButtonReleaseEvent(Button.A));
            stopWakeReleased = true;
        }
        if (!stopWakeSent && stopWake != null
                && gameboy.getCpu().getState() == Cpu.State.STOPPED
                && (stopWake.line() < 0 || (gameboy.getGpu().getLine() == stopWake.line()
                && gameboy.getGpu().getTicksInLine() >= stopWake.dot()))) {
            eventBus.post(new ButtonPressEvent(Button.A));
            stopWakeSent = true;
        }
    }

    private boolean isComplete(CompletionMode completionMode, int magicOffset, int completionPc,
                               long tick) {
        return switch (completionMode) {
            case RESULT_MAGIC -> hasMagicAt(magicOffset);
            case NEXT_STOP_AFTER_WAKE -> stopWakeReleased
                    && gameboy.getCpu().getState() == Cpu.State.STOPPED
                    && gameboy.getCpu().getRegisters().getPC() == completionPc;
            case PERSISTENT_REGISTER_CHECKPOINT -> persistentRegisterCheckpoint(completionPc, tick);
        };
    }

    private boolean persistentRegisterCheckpoint(int completionPc, long tick) {
        var registers = gameboy.getCpu().getRegisters();
        int loopPc = completionPc & 0xffff;
        boolean inLoop = registers.getPC() >= loopPc && registers.getPC() <= loopPc + 4
                && registers.getB() == ((completionPc >>> 16) & 0xff)
                && registers.getC() == 0x44
                && registers.getHL() == 0xa009;
        byte[] currentSram = readSramPrefix(HDMA_HALT_CHECKPOINT_BYTES);
        if (!inLoop || (registerCheckpointSram != null
                && !Arrays.equals(registerCheckpointSram, currentSram))) {
            registerCheckpointStart = -1;
            registerCheckpointSram = null;
            return false;
        }
        if (registerCheckpointStart < 0) {
            registerCheckpointStart = tick;
            registerCheckpointSram = currentSram;
            return false;
        }
        return tick - registerCheckpointStart >= FRAME_TICKS;
    }

    private byte[] readSramPrefix(int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) memory.getByte(0xa000 + i);
        }
        return result;
    }

    private boolean hasMagicAt(int offset) {
        for (int i = 0; i < RESULT_MAGIC.length; i++) {
            if (memory.getByte(0xa000 + offset + i) != (RESULT_MAGIC[i] & 0xff)) {
                return false;
            }
        }
        return true;
    }

    private byte[] readResult(int length) {
        // Hardware .sav captures are laid out from SRAM bank 0. Several tests leave
        // another MBC5 RAM bank selected after writing a result block to every bank,
        // so select bank 0 before reading the raw-save prefix used by the reference.
        memory.setByte(0x4000, 0);
        byte[] result = new byte[length];
        for (int i = 0; i < result.length; i++) {
            result[i] = (byte) memory.getByte(0xa000 + i);
        }
        return result;
    }

    private String dumpCpu() {
        var registers = gameboy.getCpu().getRegisters();
        return String.format("PC=%04x SP=%04x A=%02x B=%02x C=%02x D=%02x E=%02x H=%02x L=%02x data=%s",
                registers.getPC(), registers.getSP(), registers.getA(), registers.getB(), registers.getC(),
                registers.getD(), registers.getE(), registers.getH(), registers.getL(),
                Arrays.toString(readResult(16)));
    }

    public record TestResult(boolean completed, byte[] actual, String cpuState) {
    }

    public enum CompletionMode {
        RESULT_MAGIC,
        NEXT_STOP_AFTER_WAKE,
        PERSISTENT_REGISTER_CHECKPOINT
    }

    public record StopWake(int line, int dot) {

        public static StopWake immediate() {
            return new StopWake(-1, 0);
        }
    }

    private record BootKey(GameboyType gameboyType, int romLength, int cgbFlag,
                           int cartridgeType, int ramSize, String compatibilityHeader) {
    }
}
