package eu.rekawek.coffeegb;

import eu.rekawek.coffeegb.gui.SwingGui;

import java.io.File;
import java.io.PrintStream;
import java.util.*;

public class Main {

    public static void main(String[] args) throws Exception {
        ParsedArgs parsedArgs = ParsedArgs.parse(args);
        if (parsedArgs.shortParams.contains("h") || parsedArgs.params.contains("help")) {
            printUsage(System.out);
            return;
        }
        if (parsedArgs.args.size() > 1) {
            printUsage(System.err);
            return;
        }

        CartridgeOptions options = new CartridgeOptions(parsedArgs.params, parsedArgs.shortParams);
        boolean debug = parsedArgs.params.contains("debug");

        File initialRom = null;
        if (parsedArgs.args.size() == 1) {
            initialRom = new File(parsedArgs.args.get(0));
        }
        SwingGui emulator = new SwingGui(options, debug, initialRom);
        emulator.run();
    }

    private static class ParsedArgs {
        private final Set<String> params;
        private final Set<String> shortParams;
        private final List<String> args;

        private ParsedArgs(Set<String> params, Set<String> shortParams, List<String> args) {
            this.params = params;
            this.shortParams = shortParams;
            this.args = args;
        }

        private static ParsedArgs parse(String[] args) {
            Set<String> params = new LinkedHashSet<>();
            Set<String> shortParams = new LinkedHashSet<>();
            List<String> restArgs = new ArrayList<>();
            for (String a : args) {
                if (a.startsWith("--")) {
                    params.add(a.substring(2));
                } else if (a.startsWith("-")) {
                    shortParams.add(a.substring(1));
                } else {
                    restArgs.add(a);
                }
            }
            return new ParsedArgs(params, shortParams, restArgs);
        }
    }

    public static void printUsage(PrintStream stream) {
        stream.println("Usage:");
        stream.println("java -jar coffee-gb.jar [OPTIONS] [ROM_FILE]");
        stream.println();
        stream.println("Available options:");
        stream.println("  -d  --force-dmg                Emulate classic GB (DMG) for universal ROMs");
        stream.println("  -c  --force-cgb                Emulate color GB (CGB) for all ROMs");
        stream.println("  -b  --use-bootstrap            Start with the GB bootstrap");
        stream.println("  -db --disable-battery-saves    Disable battery saves");
        stream.println("  -h  --help                     Displays this info");
        stream.println("      --debug                    Enable debug console");
    }
}
