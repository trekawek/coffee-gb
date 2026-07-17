package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.AddressSpace;
import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;
import eu.rekawek.coffeegb.core.events.EventBusImpl;
import eu.rekawek.coffeegb.core.memory.cart.Rom;
import eu.rekawek.coffeegb.core.serial.SerialEndpoint;

import java.io.IOException;
import java.util.Arrays;

/** Runs an AntonioND gbc-hw-tests ROM and captures its SRAM result block. */
public final class GbcHwTestRunner {

    public static final byte[] RESULT_MAGIC = {0x12, 0x34, 0x56, 0x78};

    private final Gameboy gameboy;

    private final AddressSpace memory;

    public GbcHwTestRunner(byte[] rom, GameboyType gameboyType) throws IOException {
        Rom parsedRom = new Rom(rom);
        gameboy = new Gameboy.GameboyConfiguration(parsedRom)
                .setBootstrapMode(Gameboy.BootstrapMode.SKIP)
                .setGameboyType(gameboyType)
                .setBatteryData(new byte[Math.max(parsedRom.getRamSize(), 0x2000)])
                .build();
        gameboy.init(new EventBusImpl(), SerialEndpoint.NULL_ENDPOINT, null);
        memory = gameboy.getAddressSpace();
    }

    public TestResult runTest(int expectedLength, long maxTicks) {
        int magicOffset = expectedLength - RESULT_MAGIC.length;
        for (long tick = 0; tick < maxTicks; tick++) {
            gameboy.tick();
            if ((tick & 3) == 3 && hasMagicAt(magicOffset)) {
                return new TestResult(true, readResult(expectedLength), dumpCpu());
            }
        }
        return new TestResult(false, readResult(expectedLength), dumpCpu());
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
}
