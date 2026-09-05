package eu.rekawek.coffeegb.core.integration.support;

import eu.rekawek.coffeegb.core.Gameboy;
import eu.rekawek.coffeegb.core.GameboyType;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/** Command-line probe used when refreshing the vendored DocBoy test selection. */
public final class DocBoyMain {

    private DocBoyMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) {
            System.err.println("Usage: DocBoyMain <rom-root> [max-ticks]");
            System.exit(2);
        }
        Path root = Path.of(args[0]);
        long maxTicks = args.length == 2 ? Long.parseLong(args[1]) : Gameboy.TICKS_PER_SEC / 2L;
        List<Path> roms;
        try (var paths = Files.walk(root)) {
            roms = paths
                    .filter(Files::isRegularFile)
                    .filter(DocBoyMain::isAutomatedRom)
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }

        int passed = 0;
        int failed = 0;
        int timedOut = 0;
        for (Path rom : roms) {
            Path relative = root.relativize(rom);
            String normalized = rom.toAbsolutePath().normalize().toString().replace('\\', '/');
            String relativePath = relative.toString().replace('\\', '/');
            boolean dmg = relativePath.startsWith("dmg/")
                    || normalized.contains("/roms/dmg/");
            GameboyType type = dmg ? GameboyType.DMG : GameboyType.CGB;
            DocBoyTestRunner.TestResult result = new DocBoyTestRunner(
                    Files.readAllBytes(rom), type, Gameboy.BootstrapMode.SKIP).runTest(maxTicks);
            String outcome;
            if (result.status() == 0x01) {
                outcome = "PASS";
                passed++;
            } else if (result.status() == 0x02) {
                outcome = "FAIL";
                failed++;
            } else {
                outcome = "TIMEOUT";
                timedOut++;
            }
            System.out.printf("%s\t%s\t%s%n", outcome, relative, result);
        }
        System.out.printf("TOTAL\tpass=%d fail=%d timeout=%d%n", passed, failed, timedOut);
        System.exit(failed == 0 && timedOut == 0 ? 0 : 1);
    }

    private static boolean isAutomatedRom(Path path) {
        String name = path.getFileName().toString();
        String normalized = path.toString().replace('\\', '/');
        return (name.endsWith(".gb") || name.endsWith(".gbc"))
                && !name.equals("fail.gb")
                && !name.equals("fail.gbc")
                && !name.contains("two_players")
                && !normalized.contains("/visual/")
                && !normalized.contains("/interactive/")
                && !normalized.contains("/interactive_visual/")
                && !normalized.contains("/boot/");
    }
}
