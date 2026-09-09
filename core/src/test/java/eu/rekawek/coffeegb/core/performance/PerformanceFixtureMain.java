package eu.rekawek.coffeegb.core.performance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import static eu.rekawek.coffeegb.core.performance.PerformanceWorkloads.*;

/** Exports only repository-authored workloads, with a visible pattern and an ordinary pulse tone. */
public final class PerformanceFixtureMain {
    private static final int[] BOOT_LOGO = {
        0xce, 0xed, 0x66, 0x66, 0xcc, 0x0d, 0x00, 0x0b,
        0x03, 0x73, 0x00, 0x83, 0x00, 0x0c, 0x00, 0x0d,
        0x00, 0x08, 0x11, 0x1f, 0x88, 0x89, 0x00, 0x0e,
        0xdc, 0xcc, 0x6e, 0xe6, 0xdd, 0xdd, 0xd9, 0x99,
        0xbb, 0xbb, 0x67, 0x63, 0x6e, 0x0e, 0xec, 0xcc,
        0xdd, 0xdc, 0x99, 0x9f, 0xbb, 0xb9, 0x33, 0x3e
    };

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("scenario profile output-file");
        Scenario scenario = Scenario.valueOf(args[0]);
        Profile profile = Profile.valueOf(args[1]);
        Files.write(Path.of(args[2]), visibleImage(scenario, profile), StandardOpenOption.CREATE_NEW);
    }

    public static byte[] visibleImage(Scenario scenario, Profile profile) {
        byte[] bytes = image(scenario, profile);
        for (int i = 0; i < BOOT_LOGO.length; i++) bytes[0x104 + i] = (byte) BOOT_LOGO[i];
        String label = "PERF " + scenario.name();
        byte[] title = label.substring(0, Math.min(label.length(), 15))
                .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(title, 0, bytes, 0x134, title.length);
        int check = 0;
        for (int i = 0x134; i <= 0x14c; i++) check = check - (bytes[i] & 255) - 1;
        bytes[0x14d] = (byte) check;
        int setupAddress = scenario == Scenario.OAM_ROM_DMA ? 0x3000 : 0x4000;
        bytes[0x101] = (byte) setupAddress;
        bytes[0x102] = (byte) (setupAddress >> 8);
        Setup p = new Setup(bytes, setupAddress);
        p.emit(0xf3);
        p.io(0x40, 0x11).io(0x4f, 0);
        for (int row = 0; row < 8; row++) {
            p.write(0x8000 + row * 2, row % 2 == 0 ? 0xaa : 0x55);
            p.write(0x8001 + row * 2, 0);
        }
        p.emit(0x21, 0x00, 0x98, 0x01, 0x00, 0x08);
        int clear = p.position;
        p.emit(0xaf, 0x22, 0x0b, 0x78, 0xb1, 0xc2, clear & 255, clear >> 8);
        for (int sprite = 0; sprite < 40; sprite++) {
            p.write(0xc000 + sprite * 4, 16 + (sprite % 5) * 24);
            p.write(0xc001 + sprite * 4, 8 + (sprite % 10) * 12);
            p.write(0xc002 + sprite * 4, 0);
            p.write(0xc003 + sprite * 4, 0);
        }
        p.io(0x47, 0xe4).io(0x48, 0xe4).io(0x49, 0xe4);
        for (int palette : new int[]{0x68, 0x6a}) {
            p.io(palette, 0x80);
            for (int value : new int[]{0xff, 0x7f, 0x00, 0x7c, 0xe0, 0x03, 0, 0}) {
                p.io(palette + 1, value);
            }
        }
        p.io(0x26, 0x80).io(0x24, 0x77).io(0x25, 0x11);
        p.io(0x11, 0x80).io(0x12, 0xf0).io(0x13, 0xd6).io(0x14, 0x86);
        p.io(0x4a, 48).io(0x4b, 80).io(0x40, 0x93);
        if (scenario == Scenario.STAT_POLL) p.io(0x41, 0x78).io(0x45, 153);
        p.emit(0xc3, 0x50, 0x01);
        return bytes;
    }

    private static final class Setup {
        private final byte[] bytes;
        private int position;
        Setup(byte[] bytes, int position) { this.bytes = bytes; this.position = position; }
        void emit(int... values) { for (int value : values) bytes[position++] = (byte) value; }
        Setup io(int address, int value) { emit(0x3e, value, 0xe0, address); return this; }
        void write(int address, int value) { emit(0x3e, value, 0xea, address & 255, address >> 8); }
    }
}
