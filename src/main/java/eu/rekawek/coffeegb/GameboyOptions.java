package eu.rekawek.coffeegb;

import java.io.File;
import java.io.PrintStream;
import java.util.Collection;
import java.util.Collections;

public class GameboyOptions {

    private final File romFile;

    private final boolean forceDmg;

    private final boolean useBootstrap;

    public GameboyOptions(File romFile) {
        this(romFile, Collections.emptyList(), Collections.emptyList());
    }

    public GameboyOptions(File romFile, Collection<String> params, Collection<String> shortParams) {
        this.romFile = romFile;
        this.forceDmg = params.contains("force-classic") || shortParams.contains("f");
        this.useBootstrap = params.contains("use-bootstrap") || shortParams.contains("b");
    }

    public File getRomFile() {
        return romFile;
    }

    public boolean isForceDmg() {
        return forceDmg;
    }

    public boolean isUsingBootstrap() {
        return useBootstrap;
    }

    public static void printUsage(PrintStream stream) {
        stream.println("Usage:");
        stream.println("java -jar coffee-gb.jar [OPTIONS] ROM_FILE");
        stream.println();
        stream.println("Available options:");
        stream.println("  -f --force-classic    Emulate classic GB (DMG) for universal ROMs");
        stream.println("  -b --use-bootstrap    Start with the GB bootstrap");
    }
}
